@file:Suppress("TooManyFunctions")

package app.pulse.providers.listentogether

import app.pulse.providers.listentogether.proto.MetroProtoCodec
import app.pulse.providers.listentogether.proto.PbClientCapabilities
import app.pulse.providers.listentogether.proto.PbPlaybackAction
import app.pulse.providers.listentogether.proto.PbPing
import app.pulse.providers.listentogether.proto.PbTrackInfo
import app.pulse.providers.listentogether.proto.createRoom as encodeCreateRoomPayload
import app.pulse.providers.listentogether.proto.decodeBufferWait
import app.pulse.providers.listentogether.proto.decodeFirstString
import app.pulse.providers.listentogether.proto.decodeHostChanged
import app.pulse.providers.listentogether.proto.decodeJoinApproved
import app.pulse.providers.listentogether.proto.decodeJoinRequest
import app.pulse.providers.listentogether.proto.decodePlaybackAction
import app.pulse.providers.listentogether.proto.decodeReconnected
import app.pulse.providers.listentogether.proto.decodeRoomCreated
import app.pulse.providers.listentogether.proto.decodeServerCapabilities
import app.pulse.providers.listentogether.proto.decodeSuggestionApproved
import app.pulse.providers.listentogether.proto.decodeSuggestionReceived
import app.pulse.providers.listentogether.proto.decodeSyncState
import app.pulse.providers.listentogether.proto.decodeTwoStrings
import app.pulse.providers.listentogether.proto.encodeClientCapabilities
import app.pulse.providers.listentogether.proto.encodeFirstString
import app.pulse.providers.listentogether.proto.encodeJoinRoom
import app.pulse.providers.listentogether.proto.encodePlaybackAction
import app.pulse.providers.listentogether.proto.encodePing
import app.pulse.providers.listentogether.proto.encodeSuggestTrack
import app.pulse.providers.listentogether.proto.encodeTwoStrings
import app.pulse.providers.listentogether.proto.syncStateToRoomState
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * Message type strings — copied verbatim from metroserver
 * (internal/server/protocol.go) so the client and server agree exactly.
 */
object Protocol {
    // Client -> Server
    const val CREATE_ROOM = "create_room"
    const val JOIN_ROOM = "join_room"
    const val LEAVE_ROOM = "leave_room"
    const val APPROVE_JOIN = "approve_join"
    const val REJECT_JOIN = "reject_join"
    const val PLAYBACK_ACTION = "playback_action"
    const val BUFFER_READY = "buffer_ready"
    const val KICK_USER = "kick_user"
    const val TRANSFER_HOST = "transfer_host"
    const val PING = "ping"
    const val REQUEST_SYNC = "request_sync"
    const val RECONNECT = "reconnect"
    const val SUGGEST_TRACK = "suggest_track"
    const val APPROVE_SUGGESTION = "approve_suggestion"
    const val REJECT_SUGGESTION = "reject_suggestion"
    const val CLIENT_CAPABILITIES = "client_capabilities"

    /** Playback actions relayed inside playback_action messages. */
    object Action {
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val SEEK = "seek"
        const val SKIP_NEXT = "skip_next"
        const val SKIP_PREV = "skip_prev"
        const val CHANGE_TRACK = "change_track"
        const val QUEUE_ADD = "queue_add"
        const val QUEUE_REMOVE = "queue_remove"
        const val QUEUE_CLEAR = "queue_clear"
        const val SYNC_QUEUE = "sync_queue"
        const val SET_VOLUME = "set_volume"
    }

    // Server -> Client
    const val ROOM_CREATED = "room_created"
    const val JOIN_REQUEST = "join_request"
    const val JOIN_APPROVED = "join_approved"
    const val JOIN_REJECTED = "join_rejected"
    const val USER_JOINED = "user_joined"
    const val USER_LEFT = "user_left"
    const val SYNC_PLAYBACK = "sync_playback"
    const val BUFFER_WAIT = "buffer_wait"
    const val BUFFER_COMPLETE = "buffer_complete"
    const val ERROR = "error"
    const val PONG = "pong"
    const val HOST_CHANGED = "host_changed"
    const val KICKED = "kicked"
    const val SYNC_STATE = "sync_state"
    const val RECONNECTED = "reconnected"
    const val USER_RECONNECTED = "user_reconnected"
    const val USER_DISCONNECTED = "user_disconnected"
    const val SUGGESTION_RECEIVED = "suggestion_received"
    const val SUGGESTION_APPROVED = "suggestion_approved"
    const val SUGGESTION_REJECTED = "suggestion_rejected"
    const val SERVER_CAPABILITIES = "server_capabilities"
}

/** Local identity inside a room; carries the reconnect token. */
data class RoomSession(
    val roomCode: String,
    val userId: String,
    val sessionToken: String,
    val isHost: Boolean
)

/**
 * The transport layer of the "Listen Together" service.
 *
 * Owns the binary WS socket, Protobuf(+GZIP) envelope codec, the mandatory
 * capabilities handshake that keeps metroserver out of its "advertise to
 * unmatched client" fallback, protocol ping/pong RTT sampling, and 5-tier
 * exponential backoff. All decoding and consequence handling runs on
 * [workerContext], never the caller's thread.
 *
 * Topology: `ws(s)://<host>/ws` (see metroserver internal/server/run.go).
 */
class ListenTogetherClient(
    val config: ListenTogetherConfig,
    private val socketScope: CoroutineScope,
    private val workerContext: CoroutineContext = Dispatchers.IO,
    private val socketFactory: ListenTogetherSocketFactory = OkHttpListenTogetherSocketFactory()
) {
    private val _events = MutableSharedFlow<ListenTogetherEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ListenTogetherEvent> = _events

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val session = AtomicReference<RoomSession?>(null)
    val currentSession: RoomSession? get() = session.get()

    private val codec = MetroProtoCodec()
    private val pingSequence = AtomicLong(0)

    /** Latest keep-alive round-trip sample; -1 until the first pong arrives. */
    private val rttMs = AtomicLong(-1L)
    fun lastRttMs(): Long = rttMs.get()

    /** When the in-flight ping was sent (one keep-alive outstanding at a time). */
    private val lastPingSentAt = AtomicLong(0L)

    /**
     * Inbound frames must be decoded in arrival order — spawning a coroutine
     * per frame would let them interleave (e.g. join_approved decode racing
     * room_created). One ordered worker keeps FIFO guarantees and avoids
     * per-frame launch overhead.
     */
    private val inboundFrames = kotlinx.coroutines.channels.Channel<ByteArray>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )

    @Volatile
    private var inboundWorkerRunning = false

    /**
     * Outbound frames go through one ordered writer, mirroring the inbound
     * worker: several coroutines (ticker, drift loop, listeners, watchdogs)
     * call send() concurrently and OkHttp's stream leaves the write ACQUIRE
     * order to thread timing — a [sendPlaybackAction] pair like
     * CHANGE_TRACK -> PLAY could land inverted. One consumer makes frame
     * order == call order.
     */
    private val writeQueue = kotlinx.coroutines.channels.Channel<ByteArray>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )

    @Volatile
    private var writeWorkerRunning = false

    @Volatile
    private var socket: ListenTogetherSocket? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var pingJob: Job? = null

    @Volatile
    private var wantsConnection = false

    /** Session token captured at room_created/join_approved for mobility. */
    @Volatile
    private var reconnectToken: String? = null

    fun setReconnectToken(token: String?) {
        reconnectToken = token
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun connect() {
        if (wantsConnection && _connectionState.value != ConnectionState.Fatal) return
        // A previous exhausted backoff latched Fatal — clear it so a user
        // retry (or coordinator reconnect) gets a fresh attempt chain.
        reconnectJob?.cancel()
        wantsConnection = true
        attemptNumber.set(0)
        openSocket()
        startPingLoop()
        startInboundWorker()
        startWriteWorker()
    }

    private fun startInboundWorker() {
        if (inboundWorkerRunning) return
        inboundWorkerRunning = true
        socketScope.launch(workerContext) {
            for (frame in inboundFrames) {
                runCatching { decodeAndDispatch(frame) }
            }
        }
    }

    /** Radio/Wi-Fi transition: tear down and re-attach inside the deadline. */
    fun onNetworkChanged() {
        if (!wantsConnection) return
        reconnectJob?.cancel()
        attemptNumber.set(0)
        flushWriteQueue()
        socket?.closeQuietly()
        socket = null
        _connectionState.value = ConnectionState.Disconnected
        openSocket(recovery = true)
    }

    fun disconnect() {
        wantsConnection = false
        reconnectJob?.cancel()
        pingJob?.cancel()
        flushWriteQueue()
        socket?.closeQuietly()
        socket = null
        session.set(null)
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Outbound hook target so hosts/guests can intercept send paths too. */
    private val attemptNumber = AtomicLong(0)

    private fun openSocket(recovery: Boolean = false) {
        if (socket != null || _connectionState.value == ConnectionState.Connected) return
        _connectionState.value = ConnectionState.Connecting

        socket = socketFactory.create(
            url = config.serverUrl,
            headers = config.handshakeHeaders,
            listener = object : ListenTogetherSocketListener {
                override fun onOpen() {
                    // The client_capabilities frame MUST be the first frame on
                    // every new socket. Enqueue it synchronously from the
                    // socket callback: server_capabilities may arrive (and
                    // trigger a reconnect send) before a launched coroutine
                    // gets to run, which produced capabilities_too_late.
                    codec.setCompressionEnabled(false)
                    send(
                        Protocol.CLIENT_CAPABILITIES,
                        encodeClientCapabilities(
                            PbClientCapabilities(
                                supportsProtobuf = true,
                                supportsCompression = config.negotiateCompression,
                                clientVersion = config.clientVersion
                            )
                        )
                    )
                    socketScope.launch(workerContext) {
                        _connectionState.value = ConnectionState.Connected
                        attemptNumber.set(0)
                    }
                }

                override fun onMessage(frame: ByteArray) {
                    // UNLIMITED channel: trySend never fails on the socket thread.
                    inboundFrames.trySend(frame)
                }

                override fun onFailure(error: Throwable) {
                    socket = null
                    flushWriteQueue()
                    socketScope.launch(workerContext) { scheduleReconnect(recovery, error) }
                }

                override fun onClosed(cleanly: Boolean) {
                    socket = null
                    flushWriteQueue()
                    socketScope.launch(workerContext) { scheduleReconnect(recovery, null) }
                }
            }
        )
    }

    /** Exponential backoff: 2^retry * 500ms, ceiling 10s, 5 attempts then fatal. */
    private fun scheduleReconnect(recovery: Boolean, failure: Throwable?) {
        if (!wantsConnection) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }
        if (reconnectJob != null && reconnectJob?.isActive == true) return

        val attempt = attemptNumber.getAndIncrement()
        if (attempt >= config.RECONNECT_MAX_ATTEMPTS) {
            _connectionState.value = ConnectionState.Fatal
            _events.tryEmit(
                ListenTogetherEvent.FatalDisconnected(failure?.message ?: "handshake exhausted")
            )
            return
        }

        val backoff = (config.RECONNECT_BASE_MS shl attempt.toInt()).coerceAtMost(config.RECONNECT_CEILING_MS)
        reconnectJob = socketScope.launch {
            delay(backoff)
            if (wantsConnection) {
                _connectionState.value = ConnectionState.Connecting
                openSocket(recovery)
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = socketScope.launch(workerContext) {
            while (isActive) {
                delay(config.PING_INTERVAL_MS)
                if (_connectionState.value == ConnectionState.Connected) emitPing()
            }
        }
    }

    // ------------------------------------------------------------------
    // Inbound
    // ------------------------------------------------------------------

    @Volatile
    private var roomCode: String? = null

    internal fun decodeAndDispatch(bytes: ByteArray) {
        val envelope = runCatching { codec.decode(bytes) }.getOrElse {
            _events.tryEmit(ListenTogetherEvent.Error("invalid_message", "malformed frame"))
            return
        }

        val payload = envelope.payload

        when (envelope.type) {
            Protocol.SERVER_CAPABILITIES -> {
                val server = decodeServerCapabilities(payload)
                codec.setCompressionEnabled(config.negotiateCompression && server.supportsCompression)
                // Do not send the reconnect token here: the capabilities frame
                // is queued first, and the app coordinator reacts to Handshake.
                // Sending from this thread used to race the capabilities frame.
                _events.tryEmit(ListenTogetherEvent.Handshake(server.clientVersion))
            }

            Protocol.ROOM_CREATED -> {
                val created = decodeRoomCreated(payload)
                reconnectToken = created.sessionToken
                roomCode = created.roomCode
                session.set(
                    RoomSession(created.roomCode, created.userId, created.sessionToken, isHost = true)
                )
                _events.tryEmit(
                    ListenTogetherEvent.RoomCreated(created.roomCode, created.userId, created.sessionToken)
                )
            }

            Protocol.JOIN_REQUEST -> decodeJoinRequest(payload).let {
                _events.tryEmit(ListenTogetherEvent.JoinRequest(it.userId, it.username))
            }

            Protocol.JOIN_APPROVED -> {
                val approved = decodeJoinApproved(payload)
                val restored = approved.state?.let(::pbToRoomState)
                reconnectToken = approved.sessionToken
                roomCode = approved.roomCode
                session.set(
                    RoomSession(approved.roomCode, approved.userId, approved.sessionToken, isHost = false)
                )
                _events.tryEmit(
                    ListenTogetherEvent.JoinApproved(
                        approved.roomCode,
                        approved.userId,
                        approved.sessionToken,
                        restored ?: RoomState(approved.roomCode)
                    )
                )
            }

            Protocol.JOIN_REJECTED -> _events.tryEmit(
                ListenTogetherEvent.JoinRejected(decodeFirstString(payload))
            )

            Protocol.USER_JOINED -> decodeJoinRequest(payload).let {
                _events.tryEmit(ListenTogetherEvent.UserJoined(it.userId, it.username))
            }

            Protocol.USER_LEFT -> decodeJoinRequest(payload).let {
                _events.tryEmit(ListenTogetherEvent.UserLeft(it.userId, it.username))
            }

            Protocol.PONG -> {
                // Keep-alive acknowledgement: the payload is not needed, the
                // arrival time alone yields a usable RTT sample.
                val sentAt = lastPingSentAt.get()
                if (sentAt > 0L) {
                    rttMs.set((System.currentTimeMillis() - sentAt).coerceAtLeast(0L))
                }
            }

            Protocol.SYNC_PLAYBACK -> {
                val pb = decodePlaybackAction(payload)
                _events.tryEmit(
                    ListenTogetherEvent.PlaybackSync(
                        action = pb.action,
                        trackId = pb.trackId,
                        positionMs = pb.position,
                        track = pb.trackInfo?.let(::pbTrackToDomain),
                        insertNext = pb.insertNext,
                        queue = pb.queue.map(::pbTrackToDomain),
                        queueTitle = pb.queueTitle,
                        volume = pb.volume,
                        serverTimeMs = pb.serverTime,
                        revision = pb.revision,
                        capturedAtServerTimeMs = pb.capturedAtServerTime,
                        replaceQueue = pb.replaceQueue,
                        originUserId = pb.originUserId
                    )
                )
            }

            Protocol.BUFFER_WAIT -> decodeBufferWait(payload).let {
                _events.tryEmit(ListenTogetherEvent.BufferWait(it.trackId, it.waitingFor))
            }

            Protocol.BUFFER_COMPLETE -> _events.tryEmit(
                ListenTogetherEvent.BufferComplete(decodeFirstString(payload))
            )

            Protocol.ERROR -> decodeTwoStrings(payload).let { (code, message) ->
                _events.tryEmit(ListenTogetherEvent.Error(code, message))
            }

            Protocol.HOST_CHANGED -> decodeHostChanged(payload).let { changed ->
                session.get()?.let { current ->
                    session.set(current.copy(isHost = changed.newHostId == current.userId))
                }
                _events.tryEmit(ListenTogetherEvent.HostChanged(changed.newHostId, changed.newHostName))
            }

            Protocol.KICKED -> _events.tryEmit(ListenTogetherEvent.Kicked(decodeFirstString(payload)))

            Protocol.SYNC_STATE -> {
                // sync_state is its own schema (field numbers differ from
                // RoomState); decode with the dedicated parser.
                val pbState = decodeSyncState(payload)
                val state = pbToRoomState(syncStateToRoomState(pbState))
                _events.tryEmit(ListenTogetherEvent.SyncState(state))
            }

            Protocol.RECONNECTED -> decodeReconnected(payload).let { reconnected ->
                val finalState = reconnected.state?.let(::pbToRoomState) ?: RoomState(reconnected.roomCode)
                roomCode = reconnected.roomCode
                session.get()?.let { existing ->
                    session.set(
                        existing.copy(roomCode = reconnected.roomCode, isHost = reconnected.isHost)
                    )
                }
                _events.tryEmit(
                    ListenTogetherEvent.Reconnected(
                        reconnected.roomCode,
                        reconnected.userId,
                        finalState,
                        reconnected.isHost
                    )
                )
            }

            Protocol.USER_RECONNECTED -> decodeJoinRequest(payload).let {
                _events.tryEmit(ListenTogetherEvent.UserReconnected(it.userId, it.username))
            }

            Protocol.USER_DISCONNECTED -> decodeJoinRequest(payload).let {
                _events.tryEmit(ListenTogetherEvent.UserDisconnected(it.userId, it.username))
            }

            Protocol.SUGGESTION_RECEIVED -> decodeSuggestionReceived(payload).let {
                _events.tryEmit(
                    ListenTogetherEvent.SuggestionReceived(
                        suggestionId = it.suggestionId,
                        fromUserId = it.fromUserId,
                        fromUsername = it.fromUsername,
                        track = it.trackInfo?.let(::pbTrackToDomain)
                    )
                )
            }

            Protocol.SUGGESTION_APPROVED -> decodeSuggestionApproved(payload).let {
                _events.tryEmit(
                    ListenTogetherEvent.SuggestionApproved(
                        suggestionId = it.suggestionId,
                        track = it.trackInfo?.let(::pbTrackToDomain)
                    )
                )
            }

            // {1: suggestion_id, 2: reason} — same shape as error payloads.
            Protocol.SUGGESTION_REJECTED -> decodeTwoStrings(payload).let { (id, reason) ->
                _events.tryEmit(ListenTogetherEvent.SuggestionRejected(id, reason))
            }

            else -> {
                // Unknown types are logged-and-dropped, keeping forward compat.
            }
        }
    }

    private fun pbTrackToDomain(pb: PbTrackInfo): TrackInfo = TrackInfo(
        id = pb.id,
        title = pb.title,
        artist = pb.artist,
        album = pb.album,
        duration = pb.duration,
        thumbnail = pb.thumbnail,
        suggestedBy = pb.suggestedBy
    )

    private fun pbToRoomState(pb: app.pulse.providers.listentogether.proto.PbRoomState): RoomState = RoomState(
        roomCode = pb.roomCode,
        hostId = pb.hostId,
        members = pb.users.map {
            RoomMember(it.userId, it.username, it.isHost, it.isConnected)
        },
        track = pb.currentTrack?.let(::pbTrackToDomain),
        isPlaying = pb.isPlaying,
        positionMs = pb.position,
        lastUpdateMs = pb.lastUpdate,
        volume = pb.volume,
        queue = pb.queue.map(::pbTrackToDomain),
        revision = pb.revision
    )

    // ------------------------------------------------------------------
    // Outbound
    // ------------------------------------------------------------------

    private fun send(type: String, payload: ByteArray? = null) {
        val frame = runCatching { codec.encode(type, payload) }.getOrElse {
            _events.tryEmit(ListenTogetherEvent.Error("encode_failed", "$it"))
            return
        }
        // Drop (not park) frames while disconnected — a stale replay after
        // reconnect would misrepresent state (Metrolist drops them too).
        if (socket == null) return
        writeQueue.trySend(frame)
    }

    private fun startWriteWorker() {
        if (writeWorkerRunning) return
        writeWorkerRunning = true
        socketScope.launch(workerContext) {
            for (frame in writeQueue) {
                // The socket may die between enqueue and write: skip instead
                // of pumping the abandoned frame into a future session.
                val target = socket ?: continue
                runCatching { target.send(okio.ByteString.of(*frame)) }
            }
        }
    }

    /** Discard anything already queued (socket teardown). */
    private fun flushWriteQueue() {
        while (true) {
            writeQueue.tryReceive().getOrNull() ?: break
        }
    }

    fun createRoom(username: String) = send(Protocol.CREATE_ROOM, encodeCreateRoomPayload(username))

    fun joinRoom(roomCode: String, username: String) =
        send(Protocol.JOIN_ROOM, encodeJoinRoom(roomCode, username))

    fun leave() {
        send(Protocol.LEAVE_ROOM)
        session.set(null)
        reconnectToken = null
    }

    fun sendPlaybackAction(action: PbPlaybackAction) = send(
        Protocol.PLAYBACK_ACTION,
        encodePlaybackAction(action)
    )

    fun bufferReady(trackId: String) = send(Protocol.BUFFER_READY, encodeFirstString(trackId))

    fun approveJoin(userId: String) = send(Protocol.APPROVE_JOIN, encodeFirstString(userId))

    fun rejectJoin(userId: String, reason: String) = send(
        Protocol.REJECT_JOIN,
        encodeTwoStrings(userId, reason)
    )

    fun kickUser(userId: String, reason: String) = send(Protocol.KICK_USER, encodeTwoStrings(userId, reason))

    fun transferHost(newHostId: String) = send(Protocol.TRANSFER_HOST, encodeFirstString(newHostId))

    fun requestSync() = send(Protocol.REQUEST_SYNC)

    /** Protocol-level keep-alive. The pong is only timed, never decoded. */
    fun ping(): Long {
        val sequence = pingSequence.incrementAndGet()
        lastPingSentAt.set(System.currentTimeMillis())
        send(Protocol.PING, encodePing(PbPing(System.currentTimeMillis(), sequence)))
        return sequence
    }

    private fun emitPing() {
        ping()
    }

    fun sendReconnect(token: String) = send(Protocol.RECONNECT, encodeFirstString(token))

    fun suggestTrack(track: TrackInfo) = send(
        Protocol.SUGGEST_TRACK,
        encodeSuggestTrack(
            PbTrackInfo(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                duration = track.duration,
                thumbnail = track.thumbnail,
                suggestedBy = track.suggestedBy
            )
        )
    )

    fun approveSuggestion(suggestionId: String) =
        send(Protocol.APPROVE_SUGGESTION, encodeFirstString(suggestionId))

    fun rejectSuggestion(suggestionId: String, reason: String) =
        send(Protocol.REJECT_SUGGESTION, encodeTwoStrings(suggestionId, reason))
}
