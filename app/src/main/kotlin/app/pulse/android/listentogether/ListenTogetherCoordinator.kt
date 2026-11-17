package app.pulse.android.listentogether

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.pulse.android.BuildConfig
import androidx.media3.common.Timeline
import app.pulse.android.Dependencies
import app.pulse.android.R
import app.pulse.android.service.PlayerService
import app.pulse.providers.innertube.Innertube
import app.pulse.providers.innertube.requests.song
import app.pulse.providers.listentogether.ConnectionState
import app.pulse.providers.listentogether.DriftEngine
import app.pulse.providers.listentogether.DriftHealth
import app.pulse.providers.listentogether.DriftSnapshot
import app.pulse.providers.listentogether.ListenTogetherClient
import app.pulse.providers.listentogether.ListenTogetherConfig
import app.pulse.providers.listentogether.ListenTogetherEvent
import app.pulse.providers.listentogether.Protocol
import app.pulse.providers.listentogether.RoomMember
import app.pulse.providers.listentogether.RoomState
import app.pulse.providers.listentogether.SyncAnchor
import app.pulse.providers.listentogether.TrackInfo
import app.pulse.providers.listentogether.proto.PbPlaybackAction
import app.pulse.providers.listentogether.proto.PbTrackInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TogetherJoinRequest(val userId: String, val username: String)

data class TogetherSuggestion(
    val suggestionId: String,
    val fromUsername: String,
    val track: TrackInfo?,
    /** True when the requester asked to *play* the track, not just queue it. */
    val isPlayRequest: Boolean = false
)

data class TogetherUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val inRoom: Boolean = false,
    val roomCode: String = "",
    val userId: String = "",
    val isHost: Boolean = false,
    val hostId: String? = null,
    val members: List<RoomMember> = emptyList(),
    val track: TrackInfo? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val queue: List<TrackInfo> = emptyList(),
    val drift: DriftSnapshot = DriftSnapshot(),
    val bufferingUsers: List<String> = emptyList(),
    val pendingJoinRequests: List<TogetherJoinRequest> = emptyList(),
    val suggestions: List<TogetherSuggestion> = emptyList(),
    /** True while my own player is priming the room's track. */
    val isBuffering: Boolean = false,
    /**
     * Guest: video id of my play request that is awaiting the host's
     * approval. Null when nothing is pending (the server gives suggesters
     * no ack/id echo, so matching is done against the reply's track).
     */
    val pendingPlayRequest: String? = null,
    val error: String? = null,
    val kickedReason: String? = null,
    /** True while a create-room request is in flight (shows a spinner). */
    val isCreatingRoom: Boolean = false,
    /** True while a join-room request is in flight (shows a spinner). */
    val isJoiningRoom: Boolean = false,
    /** True once the join packet was sent and the host must approve. */
    val isAwaitingJoinApproval: Boolean = false,
    /** True when the active playback on device cannot be shared (local audio file or radio). */
    val isUnsupportedLocalPlayback: Boolean = false
)

private val BENIGN_ERROR_CODES = setOf(
    /** Server liveness errors that do not indicate a real problem. */
    "not_in_room",
    "already_in_room",
    "already_pending",
    "rate_limited",
    /**
     * Playback rejection codes: surfaced noisily they'd spam the room UI —
     * the coordinator turns them into a state re-announce instead.
     */
    "no_track",
    "stale_track"
)

/**
 * App-side singleton gluing the pure [ListenTogetherClient] + [DriftEngine]
 * service layer to the Media3 player and the Android radio stack.
 *
 *  - Host: local playback events (play/pause, seek, transitions) are relayed
 *    as `playback_action` packets; the [DriftEngine] publishes the playhead at
 *    most once per second.
 *  - Guest: `sync_playback` / `sync_state` messages drive the local player
 *    (echo-suppressed), `buffer_ready` is reported when the audio hardware
 *    has primed, and the drift engine keeps the playhead tight.
 *  - Radio transitions trigger a re-handshake inside the configured deadline
 *    using the persisted session token.
 */
object ListenTogetherCoordinator {

    private const val TAG = "ListenTogether"

    /** How long a create-room request may wait for any server reply. */
    private const val CREATE_ROOM_TIMEOUT_MS = 15_000L

    /** How long a guest may wait for the host to approve the join request. */
    private const val JOIN_ROOM_TIMEOUT_MS = 60_000L

    /**
     * RETIRED intent channel, kept for the player-metadata strip only.
     * The marker used to ride in `suggested_by`, then in `album` — the server
     * rejects both ("invalid payload" / "invalid suggest track payload"):
     * it fills `suggested_by` itself and validates the remaining content
     * against what it sent. Play requests are now detected positionally (a
     * suggestion for an already-queued track) with the payload echoed back
     * verbatim — see [requestPlay].
     */
    internal const val PLAY_REQUEST_MARKER = "[play]"

    /**
     * Reject-reason marker meaning "request granted — the track is already in
     * the room and is switching now". Guests match on it to avoid showing a
     * bogus "declined" alert for a successful relay.
     */
    private const val REASON_PLAYING_NOW = "playing_now"

    /** Host-side in-flight relayed play (approve -> QUEUE_ADD -> host plays). */
    private data class RelayedPlay(
        val trackId: String,
        val suggestionId: String,
        val expiresAtMs: Long
    )

    @Volatile
    private var pendingRelayedPlay: RelayedPlay? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    val config = ListenTogetherConfig().apply {
        // metroserver resolves User-Agents by *lowercased substring* against
        // its default allowlist ("okhttp", "ktor-client") or exact package
        // IDs; everything else lands in the advert tier, and a non-allow
        // client may not even host/resume hosting. Keep the okhttp marker so
        // the connection is always in the allow tier on stock servers, while
        // carrying app brand + device details for operators with custom
        // UA_POLICY_FILE allow entries (e.g. "metrolist").
        userAgent = "Metrolist/${ListenTogetherConfig.CLIENT_VERSION}" +
            " (Android; SDK ${android.os.Build.VERSION.SDK_INT};" +
            " ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})" +
            " okhttp/5.3.2"
    }

    val client = ListenTogetherClient(config, scope)

    @Volatile
    private var adapter: ExoPlayerSyncPlayer? = null

    @Volatile
    private var drift: DriftEngine? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var networkCallbackRegistered = false

    private val _state = MutableStateFlow(TogetherUiState())
    val state: StateFlow<TogetherUiState> = _state

    private fun isHostNow(): Boolean = _state.value.isHost &&
        (client.currentSession?.isHost != false)

    fun userName(): String = ListenTogetherPreferences.userName

    fun setUserName(value: String) {
        ListenTogetherPreferences.userName = value
    }

    /**
     * Bind the local player (binder becomes available once the UI is shown).
     * Registers playback listeners that power the host-command broadcaster and
     * the room-state mirrors.
     */
    @Volatile
    private var boundBinder: PlayerService.Binder? = null

    /** Together-screen visibility; the ticker only needs this in the lobby. */
    @Volatile
    private var uiVisible = false

    @Volatile
    private var appForeground = true

    /** Single long-lived UI ticker; cancelled whenever the screen leaves. */
    @Volatile
    private var uiTickerJob: Job? = null

    /** Watchdog for create/join requests that never get a server reply. */
    @Volatile
    private var lobbyTimeoutJob: Job? = null

    fun bind(binder: PlayerService.Binder) {
        val binderChanged = boundBinder !== binder
        boundBinder = binder
        ensureInitialized()

        val previousAdapter = adapter
        val syncPlayer = if (binderChanged || previousAdapter == null) {
            ExoPlayerSyncPlayer { binder.player }.also { adapter = it }
        } else {
            previousAdapter
        }

        // The service can be destroyed and recreated (notification dismissed,
        // stopWhenClosed). Its Binder — and therefore its player instance — is
        // new, so the drift engine must follow the new adapter too.
        if (binderChanged || drift == null) {
            drift?.stopGuestLoop()
            drift?.stopHostLoop()
            drift = DriftEngine(config, syncPlayer)
            pendingEndedTrackId = null
            pendingEndedPositionMs = null
        }

        // Re-anchors when the service swapped its audible player at a
        // crossfade boundary; no-op while the same instance is still current.
        syncPlayer.attachPlayerListener(hostListener)
        registerNetworkCallback()
        maybeStartTicker()

        if (binderChanged && _state.value.inRoom) {
            // Fresh service instance: reattach to the live room from this
            // device's canonical local state instead of leaving stale loops.
            scope.launch {
                if (_state.value.isHost) {
                    // Give PlayerService's persistent-queue restore (posted to
                    // the main thread right after bind) time to populate the
                    // new player before we publish its queue to the room.
                    delay(400)
                    if (!_state.value.inRoom) return@launch
                    lastSyncedTrackId = null
                    lastSyncedIsPlaying = false
                    startHostLoops()
                    startHostSnapshot()
                } else {
                    startGuestLoops()
                    runCatching { client.requestSync() }
                }
            }
        } else if (_state.value.inRoom && _state.value.isHost && _state.value.track == null) {
            val info = syncPlayer.currentTrackInfo()
            if (info != null) startHostSnapshot()
        }
    }

    /**
     * System-wide initialization (Metrolist parity). Call once at app startup
     * from MainApplication.onCreate() to start the WebSocket, event loop,
     * and session restoration immediately — not lazily when the Together screen opens.
     */
    fun initialize() {
        ensureInitialized()
    }

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true

        scope.launch { client.connectionState.collect { connection -> onConnectionChanged(connection) } }
        scope.launch { client.events.collect { onEvent(it) } }
        // NO periodic request polling (Metrolist uses request_sync only on
        // reconnection and missing-canonical moments): every SyncState reply
        // resets mirrored room state, which the UI visibly "refreshes" and the
        // guest playhead jumps are mistaken for playback corrections.

        // Observe app foreground/background to gate heavy loops.
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    /**
     * PlayerService swapped its audible player (crossfade). The adapter
     * re-resolves the instance per call, but LISTENERS need re-anchoring and
     * the room must learn the new track — otherwise the whole room freezes on
     * the paused, silent decoder after the first crossfade.
     */
    fun onAudiblePlayerSwapped() {
        scope.launch {
            val reattached = adapter?.attachPlayerListener(hostListener) ?: return@launch
            if (reattached && isHostNow()) {
                android.util.Log.d(TAG, "audible player swapped -> re-announcing room state")
                startHostSnapshot()
            }
        }
    }

    // --- Host room-ack bookkeeping (self healing) -------------------------

    /** Track id from the newest server echo of our own packets. */
    @Volatile
    private var lastHostEchoTrackId: String? = null

    @Volatile
    private var lastHostEchoAt = 0L

    @Volatile
    private var lastReannounceAt = 0L

    /**
     * Server rejected one of our playback packets (no current track or stale
     * target): force a full CHANGE_TRACK + PLAY re-announcement so the room
     * state recovers instead of sitting paused forever.
     */
    private fun requestHostReannounce() {
        val now = System.currentTimeMillis()
        if (now - lastReannounceAt < 800L) return
        lastReannounceAt = now
        if (!isHostNow()) return
        android.util.Log.w(TAG, "server rejected our playback packet -> re-announcing")
        lastSyncedTrackId = null
        lastSyncedIsPlaying = false
        startHostSnapshot()
    }

    // -------------------------------------------------------------------

    private fun onConnectionChanged(connection: ConnectionState) {
        _state.update {
            it.copy(
                connectionState = connection,
                // Fatal = the client exhausted its reconnect chain. The room
                // UI stays usable; the sync action kicks a fresh attempt.
                error = if (
                    connection == ConnectionState.Fatal && it.inRoom && it.error == null
                ) {
                    Dependencies.application.getString(R.string.together_connection_lost)
                } else it.error
            )
        }
    }

    private fun onEvent(event: ListenTogetherEvent) {
        when (event) {
            is ListenTogetherEvent.Handshake -> {
                // Every fresh socket needs to re-attach to the persisted room;
                // currentSession reflects the *previous* socket and must not
                // gate this (a dropped socket keeps its in-memory session).
                // Suppressed while a create/join lobby is in flight: the
                // stale token would reattach to the old room and thrash
                // against the incoming RoomCreated/JoinApproved.
                if (!_state.value.isCreatingRoom && !_state.value.isJoiningRoom) {
                    ListenTogetherPreferences.sessionToken.let { token ->
                        if (token.isNotBlank()) client.sendReconnect(token)
                    }
                }
            }

            is ListenTogetherEvent.RoomCreated -> {
                stopLobbyTimeout()
                ListenTogetherPreferences.sessionToken = event.sessionToken
                lastSyncedTrackId = null
                lastSyncedIsPlaying = false
                val player = adapter
                val currentTrack = player?.currentTrackInfo()
                val currentPlaying = player?.isPlaying() ?: false
                val currentPos = player?.positionMs() ?: 0L
                val currentQueue = player?.upcomingQueue() ?: emptyList()
                val isUnsupported = player?.currentMediaItemIsUnsupported() ?: false
                _state.update {
                    it.copy(
                        inRoom = true,
                        roomCode = event.roomCode,
                        userId = event.userId,
                        isHost = true,
                        isCreatingRoom = false,
                        isJoiningRoom = false,
                        isAwaitingJoinApproval = false,
                        track = currentTrack,
                        isPlaying = currentPlaying,
                        positionMs = currentPos,
                        queue = currentQueue,
                        isUnsupportedLocalPlayback = isUnsupported,
                        // Seed the roster with myself — the server's UserJoined
                        // broadcasts only cover joiners, never the host, and a
                        // blank host roster renders the members card empty.
                        members = listOf(
                            RoomMember(
                                userId = event.userId,
                                username = ListenTogetherPreferences.userName,
                                isHost = true
                            )
                        ),
                        error = null,
                        kickedReason = null
                    )
                }
                maybeStartTicker()
                startHostLoops()
                startHostSnapshot()
            }

            is ListenTogetherEvent.JoinRequest -> {
                _state.update {
                    it.copy(
                        pendingJoinRequests = (
                            it.pendingJoinRequests.filterNot { r -> r.userId == event.userId } +
                                TogetherJoinRequest(event.userId, event.username)
                            )
                    )
                }
                if (isHostNow()) {
                    TogetherNotifications.showJoinRequest(
                        Dependencies.application,
                        event.userId,
                        event.username
                    )
                }
            }

            is ListenTogetherEvent.JoinApproved -> {
                stopLobbyTimeout()
                ListenTogetherPreferences.sessionToken = event.sessionToken
                resetRevision("join approved")
                // Seed the staleness baseline from the server's snapshot
                // (Metrolist): every packet predating the join is stale.
                event.state.revision.takeIf { it > 0L }?.let { lastAppliedRevision = it }
                _state.update {
                    it.copy(
                        inRoom = true,
                        roomCode = event.roomCode,
                        userId = event.userId,
                        isHost = false,
                        isCreatingRoom = false,
                        isJoiningRoom = false,
                        isAwaitingJoinApproval = false,
                        // Seed self — servers with an empty roster snapshot
                        // would otherwise render the members card empty.
                        members = listOf(
                            RoomMember(
                                userId = event.userId,
                                username = ListenTogetherPreferences.userName,
                                isConnected = true
                            )
                        ),
                        error = null,
                        kickedReason = null
                    )
                }
                maybeStartTicker()
                applyRoomSnapshot(event.state)
            }

            is ListenTogetherEvent.JoinRejected -> {
                stopLobbyTimeout()
                _state.update {
                    it.copy(
                        error = joinRejectionMessage(event.reason),
                        isCreatingRoom = false,
                        isJoiningRoom = false,
                        isAwaitingJoinApproval = false
                    )
                }
            }

            is ListenTogetherEvent.UserJoined ->
                upsertMember(RoomMember(event.userId, event.username, isConnected = true))

            is ListenTogetherEvent.UserLeft -> removeMember(event.userId)

            is ListenTogetherEvent.UserReconnected ->
                upsertMember(RoomMember(event.userId, event.username, isConnected = true))

            is ListenTogetherEvent.UserDisconnected ->
                upsertMember(RoomMember(event.userId, event.username, isConnected = false))

            is ListenTogetherEvent.PlaybackSync -> applyPlaybackSync(event)

            is ListenTogetherEvent.BufferWait -> _state.update {
                it.copy(bufferingUsers = event.waitingFor)
            }

            is ListenTogetherEvent.BufferComplete -> {
                _state.update { it.copy(bufferingUsers = emptyList()) }
                android.util.Log.d(TAG, "buffer_complete track=${event.trackId}")
                // The server gate opened — release the deferred playback, but
                // only for the track the pending is actually for (stale
                // completes for superseded tracks must not fire).
                if (!isHostNow()) {
                    // Metrolist race slot: the complete can arrive BEFORE the
                    // pending payload is stored; register it and let the
                    // payload's writer flush.
                    bufferCompleteReceivedForTrack = event.trackId
                    applyPendingSync(event.trackId, force = true)
                }
            }

            is ListenTogetherEvent.Error -> {
                android.util.Log.e(TAG, "server error code=${event.code} message=${event.message}")
                val relay = pendingRelayedPlay
                val relayFailure = relay != null &&
                    (event.code == "queue_full" ||
                        event.code == "duplicate_track" ||
                        event.code == "suggestion_not_found")
                val guestRequestFailure = _state.value.pendingPlayRequest != null &&
                    (event.code == "suggestions_full" ||
                        event.code == "invalid_track_info" ||
                        event.code == "missing_track_info" ||
                        event.code == "invalid_payload" ||
                        event.code == "duplicate_track" ||
                        "invalid" in event.code ||
                        "payload" in event.code)
                if (relayFailure) {
                    // A relayed play whose server approval failed: release the
                    // relay and cleanse the (still-pending) suggestion card.
                    pendingRelayedPlay = null
                    scope.launch {
                        runCatching { client.rejectSuggestion(relay.suggestionId, "declined") }
                    }
                    if (event.code == "duplicate_track") {
                        // Raced insert — the track IS in the room now, still
                        // honor the request by switching directly.
                        _state.value.queue.firstOrNull { it.id == relay.trackId }
                            ?.let { playQueueItem(it) }
                    }
                }
                val isTerminalSessionError = event.code == "session_not_found" ||
                    event.code == "invalid_session" ||
                    event.code == "invalid_token" ||
                    event.code == "room_closed" ||
                    event.code == "room_not_found"
                if (isTerminalSessionError && _state.value.inRoom) {
                    teardownRoom(stopPlayback = true)
                }
                val formattedError = humanizeError(event.code, event.message)
                _state.update {
                    when {
                        relayFailure -> it.copy(error = formattedError)
                        guestRequestFailure -> it.copy(
                            pendingPlayRequest = null,
                            error = formattedError
                        )
                        event.code in BENIGN_ERROR_CODES -> it.copy(
                            isCreatingRoom = false,
                            isJoiningRoom = false,
                            isAwaitingJoinApproval = false
                        )
                        else -> it.copy(
                            error = formattedError,
                            isCreatingRoom = false,
                            isJoiningRoom = false,
                            isAwaitingJoinApproval = false
                        )
                    }
                }
                if (event.code == "no_track" || event.code == "stale_track") {
                    if (isHostNow()) {
                        // One of OUR playback packets fermented: the room's
                        // track state is behind (PLAY raced change_track).
                        requestHostReannounce()
                    } else if (bufferingTrackId != null) {
                        // Our primed track was already superseded server-side:
                        // purge the buffer state and follow the room again.
                        clearBufferState()
                        scope.launch { client.requestSync() }
                    }
                }
            }

            is ListenTogetherEvent.HostChanged -> {
                val wasHost = _state.value.isHost
                val becameHost = event.newHostId == _state.value.userId
                _state.update {
                    it.copy(
                        isHost = becameHost,
                        hostId = event.newHostId,
                        members = it.members.map { member ->
                            member.copy(isHost = member.userId == event.newHostId)
                        }
                    )
                }
                when {
                    becameHost && !wasHost -> {
                        startHostLoops()
                        // The room must learn the new host's current playback.
                        lastSyncedTrackId = null
                        lastSyncedIsPlaying = false
                        startHostSnapshot()
                    }
                    // Lost the host role (transfer/steal dispatch): stop my
                    // broadcaster — a non-host must not keep emitting PLAYBACK actions.
                    !becameHost && wasHost -> startGuestLoops()
                }
            }

            is ListenTogetherEvent.Kicked -> {
                _state.update { it.copy(kickedReason = event.reason) }
                teardownRoom(stopPlayback = true)
            }

            is ListenTogetherEvent.SyncState -> {
                // Snapshot gate (Metrolist handleSyncState): only statuses
                // newer than everything applied so far.
                if (event.state.revision > 0L && event.state.revision < lastAppliedRevision) {
                    android.util.Log.d(TAG, "drop stale sync_state rev=${event.state.revision}")
                } else {
                    lastAppliedRevision = maxOf(lastAppliedRevision, event.state.revision)
                    applyRoomSnapshot(event.state)
                }
            }

            is ListenTogetherEvent.Reconnected -> {
                resetRevision("reconnected")
                // Seed from the server snapshot like a fresh join.
                event.state.revision.takeIf { it > 0L }?.let { lastAppliedRevision = it }
                _state.update {
                    it.copy(
                        inRoom = true,
                        roomCode = event.roomCode,
                        // Restore our own identity — host detection compares
                        // hostId against it, so a missing id breaks the role.
                        userId = event.userId,
                        isHost = event.isHost,
                        members = listOf(
                            RoomMember(
                                userId = event.userId,
                                username = ListenTogetherPreferences.userName,
                                isHost = event.isHost,
                                isConnected = true
                            )
                        ),
                        kickedReason = null,
                        error = null
                    )
                }
                maybeStartTicker()
                applyRoomSnapshot(event.state, bypass = true)
                if (event.isHost) {
                    startHostLoops()
                    // My playback is authoritative — re-publish it.
                    lastSyncedTrackId = null
                    lastSyncedIsPlaying = false
                    startHostSnapshot()
                } else {
                    startGuestLoops()
                }
            }

            is ListenTogetherEvent.SuggestionReceived -> {
                val track = event.track
                // No in-band marker survives the server: it fills
                // `suggested_by` itself and rejects client-set values, and
                // it validates the rest of the track content (a marker tag
                // in any field comes back as "invalid suggest track
                // payload"). Detection is therefore positional: the Ask to
                // play button exists only on queue rows, so a suggestion for
                // a track that is already in the room IS a play request.
                // (Tiny race: a track queued between tap and delivery shows
                // as a request — approving it just plays it directly, which
                // is the self-healing outcome anyway.)
                val snapshot = _state.value
                val isPlayRequest = track != null &&
                    (track.id == snapshot.track?.id ||
                        snapshot.queue.any { it.id == track.id })
                android.util.Log.d(
                    TAG,
                    "suggestion ${event.suggestionId} from=${event.fromUsername} " +
                        "playRequest=$isPlayRequest track=${track?.id}"
                )
                val suggestion = TogetherSuggestion(
                    suggestionId = event.suggestionId,
                    fromUsername = event.fromUsername,
                    track = track,
                    isPlayRequest = isPlayRequest
                )
                _state.update {
                    it.copy(suggestions = it.suggestions + suggestion)
                }
                // Host prompt that escapes the Together screen: heads-up
                // notification with Approve/Reject, wherever the host is.
                if (isHostNow()) {
                    TogetherNotifications.showSuggestion(Dependencies.application, suggestion)
                }
            }

            is ListenTogetherEvent.SuggestionApproved -> {
                // The server inserts the track into the room queue and
                // broadcasts a QUEUE_ADD separately; the approval event's job
                // is to clear the suggestion card — and, on the requester
                // side, the pending play-request marker. (Approvals are
                // delivered only to the suggester, so the track id alone
                // identifies my own request.)
                val requestedTrackId = event.track?.id.orEmpty()
                if (requestedTrackId.isNotBlank() &&
                    requestedTrackId == _state.value.pendingPlayRequest
                ) clearPlayRequestTimeout()
                _state.update { snapshot ->
                    snapshot.copy(
                        suggestions = snapshot.suggestions.filterNot { s -> s.suggestionId == event.suggestionId },
                        pendingPlayRequest = snapshot.pendingPlayRequest
                            ?.takeIf { it != requestedTrackId }
                    )
                }
            }

            is ListenTogetherEvent.SuggestionRejected -> {
                // Rejections go to the suggester with no requester identity,
                // so a pending request is resolved on a "best match" basis:
                // "playing_now" is the host's grant confirmation for a track
                // that was already queued (played directly, no queue insert);
                // anything else is a plain decline.
                clearPlayRequestTimeout()
                _state.update { snapshot ->
                    val declineAlert = snapshot.pendingPlayRequest != null &&
                        !event.reason.startsWith(REASON_PLAYING_NOW)
                    snapshot.copy(
                        suggestions = snapshot.suggestions.filterNot { s -> s.suggestionId == event.suggestionId },
                        pendingPlayRequest = null,
                        error = if (declineAlert) {
                            Dependencies.application.getString(R.string.together_request_declined)
                        } else {
                            snapshot.error
                        }
                    )
                }
            }
            is ListenTogetherEvent.FatalDisconnected -> Unit
        }
    }

    private fun applyRoomSnapshot(snapshot: RoomState, bypass: Boolean = false) {
        // The host's local player is authoritative: a reconnect snapshot is
        // frozen at disconnect time, so blindly applying it would leave the
        // room card showing "playing" while the local player is paused (or
        // the wrong track). Guests still follow the server exactly.
        val before = _state.value
        val isHostNow = if (snapshot.hostId.isNullOrBlank()) {
            before.isHost
        } else {
            snapshot.hostId == before.userId
        }
        val localPlayer = adapter?.takeIf { isHostNow }
        val localTrack = localPlayer?.currentTrackInfo()
        val localQueue = localPlayer?.upcomingQueue() ?: emptyList()
        val localPlaying = localPlayer?.isPlaying() ?: false
        val localPosition = localPlayer?.positionMs()?.coerceAtLeast(0L) ?: 0L

        // Snapshots carry the server's authoritative roster & queue; empty
        // rosters (slug servers) fall back to what we already know.
        val effectiveHostId = snapshot.hostId ?: before.hostId
        val rawMembers = if (snapshot.members.isNotEmpty()) snapshot.members else before.members
        val mappedMembers = rawMembers.map { m ->
            m.copy(isHost = m.isHost || (effectiveHostId != null && m.userId == effectiveHostId))
        }
        val hasSelf = mappedMembers.any { it.userId == before.userId }
        val finalMembers = if (!hasSelf && before.userId.isNotBlank()) {
            mappedMembers + RoomMember(
                userId = before.userId,
                username = ListenTogetherPreferences.userName,
                isHost = isHostNow,
                isConnected = true
            )
        } else {
            mappedMembers
        }

        _state.update {
            it.copy(
                roomCode = snapshot.roomCode.ifBlank { it.roomCode },
                hostId = snapshot.hostId ?: it.hostId,
                isHost = isHostNow,
                members = finalMembers,
                // The host's local player is authoritative — including an
                // empty queue/track. A reconnected snapshot is frozen at
                // disconnect time and must never resurrect stale room media.
                // Guests follow the server exactly.
                track = if (isHostNow) localTrack else snapshot.track,
                isPlaying = if (isHostNow) localPlaying else snapshot.isPlaying,
                positionMs = if (isHostNow) localPosition else snapshot.positionMs,
                queue = if (isHostNow) localQueue else snapshot.queue
            )
        }

        val engine = drift
        if (!isHostNow) {
            snapshot.track?.let { track ->
                engine?.updateAnchor(
                    SyncAnchor(
                        trackId = track.id,
                        isPlaying = snapshot.isPlaying,
                        positionMs = snapshot.positionMs,
                        capturedAtMs = System.currentTimeMillis()
                    )
                )
            }
        }

        val amHost = _state.value.isHost
        if (amHost) {
            startHostLoops()
        } else {
            // Mirror the room queue into the local player so guests actually
            // hold the room's upcoming tracks locally.
            adapter?.replaceUpcoming(_state.value.queue)
            val track = snapshot.track
            when {
                track == null -> {
                    // Idle room (Metrolist no-current-track branch): end any
                    // stale buffer cycle and stop private playback.
                    clearBufferState()
                    lastObservedQueueIds = null
                    adapter?.let { p -> if (p.isPlaying()) p.pause() }
                }
                bufferingTrackId == track.id && pendingSync.get() != null -> {
                    // Priming already in flight for this track: just refresh
                    // the deferred payload.
                    pendingSync.set(
                        PendingSync(track.id, snapshot.isPlaying, snapshot.positionMs, System.currentTimeMillis())
                    )
                }
                else -> ensureGuestTrackLoaded(
                    track,
                    playing = snapshot.isPlaying,
                    startAt = snapshot.positionMs,
                    // Reconnection applies directly without the buffer gate
                    // (Metrolist Reconnected -> bypassBuffer = true).
                    bypass = bypass
                )
            }
        }
    }

    /** Deferred child-side playback applied after the server's buffer gate. */
    private data class PendingSync(
        val trackId: String,
        val playing: Boolean,
        val positionMs: Long,
        val capturedAtMs: Long
    )

    @Volatile
    private var bufferingTrackId: String? = null

    private val pendingSync = java.util.concurrent.atomic.AtomicReference<PendingSync?>(null)

    /** BufferComplete slot for the early-arrival race (before pending exists). */
    @Volatile
    private var bufferCompleteReceivedForTrack: String? = null

    /** Last trackId a buffer_ready was announced for (dedup on re-announce). */
    @Volatile
    private var lastBufferReadyTrackId: String? = null

    /** Queue observer state: signature of the last broadcast upcoming queue. */
    @Volatile
    private var lastObservedQueueIds: List<String>? = null

    @Volatile
    private var lastQueueBroadcastAt = 0L

    /** Bumps on every guest track load — stale load loops die on re-check. */
    private val loadGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private fun clearBufferState() {
        pendingSync.set(null)
        bufferCompleteReceivedForTrack = null
        bufferingTrackId = null
        _state.update { it.copy(isBuffering = false) }
    }

    /** Newest playback revision applied — drops out-of-order server packets
     *  that would otherwise relaunch loads of superseded tracks. */
    @Volatile
    private var lastAppliedRevision = 0L

    private fun resetRevision(reason: String) {
        lastAppliedRevision = 0L
        android.util.Log.d(TAG, "revision reset: $reason")
    }

    /** Seek threshold when (re)starting playback or while paused — Metrolist. */
    private const val SOFT_SYNC_THRESHOLD_MS = 50L

    /** Never hard-seek an ONGOING playback below this delta (heartbeats!). */
    private const val ACTIVE_PLAYBACK_SYNC_TOLERANCE_MS = 2_000L

    /**
     * Metrolist `positionAtServerTime` analog (arrival-time compensation):
     * a packet's position was captured when the host sent it; by the time it
     * lands, the host's real playhead advanced by the transit time. Estimated
     * as half the measured keep-alive round-trip, clamped, and only for
     * actions where the playhead is actually moving (PAUSE freezes it).
     */
    private fun transitCompensated(action: String, positionMs: Long): Long =
        if (action == Protocol.Action.PAUSE) positionMs
        else positionMs + (client.lastRttMs() / 2).coerceIn(0L, 250L)

    /**
     * Tracks server edits to the room queue by revision — action packets from
     * metroserver carry it; guests gate staleness on it.
     */
    private fun applyPlaybackSync(event: ListenTogetherEvent.PlaybackSync) {
        // Metrolist pattern: ignore packets older than the newest applied
        // revision. Late-arriving replies for a superseded track must not
        // relaunch loads (guests were stuck reloading A after B started).
        if (event.revision > 0L && event.revision < lastAppliedRevision) {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG, "drop stale playback rev=${event.revision} < $lastAppliedRevision")
            return
        }
        if (event.revision > 0L) lastAppliedRevision = event.revision
        if (BuildConfig.DEBUG) android.util.Log.d(
            TAG,
            "playback_sync action=${event.action} rev=${event.revision} " +
                "track=${event.trackId} pos=${event.positionMs}"
        )

        // Hosts receive their own packets back (the server broadcasts to every
        // client): treat the echo as the room's acknowledgement for the
        // room-ack watchdog (no echo within the window -> re-announce).
        if (isHostNow()) {
            val echoedTrack = event.trackId.ifBlank { event.track?.id.orEmpty() }
            if (echoedTrack.isNotBlank() || event.action != Protocol.Action.SYNC_QUEUE) {
                lastHostEchoTrackId = echoedTrack.ifBlank { lastHostEchoTrackId }
                lastHostEchoAt = System.currentTimeMillis()
            }
        }

        // Everyone (guest + host) mirrors the server's canonical queue; the
        // host is excluded from *playback* commands below.
        when (event.action) {
            Protocol.Action.QUEUE_ADD -> {
                addQueued(event)
                dispatchRelayedPlay(event)
                return
            }

            Protocol.Action.QUEUE_REMOVE -> {
                removeQueued(event.trackId)
                return
            }

            Protocol.Action.QUEUE_CLEAR -> {
                replaceRoomQueue(emptyList(), event.originUserId)
                return
            }

            Protocol.Action.SYNC_QUEUE -> {
                // Canonical snapshot: an empty queue is meaningful (host
                // cleared it) and must clear the local mirror too.
                replaceRoomQueue(event.queue, event.originUserId)
                return
            }
        }

        _state.update {
            it.copy(
                track = event.track ?: it.track,
                isPlaying = when (event.action) {
                    Protocol.Action.PLAY -> true
                    // The server resets the room to paused on every track
                    // change; PLAY re-arms it. Without this a guest that saw
                    // CHANGE_TRACK without a following PLAY would keep the
                    // paused UI showing "playing".
                    Protocol.Action.PAUSE,
                    Protocol.Action.CHANGE_TRACK -> false
                    else -> it.isPlaying
                }
            )
        }

        val engine = drift
        if (engine != null && (event.trackId.isNotBlank() || event.track != null)) {
            val anchorTrack = event.track?.id?.takeIf { it.isNotBlank() } ?: event.trackId
            val playing = when (event.action) {
                Protocol.Action.PLAY -> true
                Protocol.Action.PAUSE,
                Protocol.Action.CHANGE_TRACK -> false
                else -> _state.value.isPlaying
            }
            engine.updateAnchor(
                SyncAnchor(
                    trackId = anchorTrack,
                    isPlaying = playing,
                    // Anchored with transit compensation so later
                    // expectedPositionMs() reads mirror the host's real
                    // playhead, not the packet's send-time snapshot.
                    positionMs = transitCompensated(event.action, event.positionMs),
                    capturedAtMs = System.currentTimeMillis()
                )
            )
        }

        // Host is the command source; server echoes are state-only.
        if (isHostNow()) return

        val player = adapter ?: return
        val localId = player.currentTrackId()
        val actionTrack = event.track
        val targetTrackId = event.trackId.ifBlank { event.track?.id.orEmpty() }
        val buffering = bufferingTrackId != null

        when (event.action) {
            Protocol.Action.PLAY -> {
                // Metrolist: buffering for another track purges the buffer
                // protocol and reconciles the local player first.
                if (routeGuestPlayback(player, localId, targetTrackId, actionTrack, playing = true, positionMs = event.positionMs)) {
                    return
                }
                // Landing target with transit compensation (Metrolist
                // positionAtServerTime): the host playhead moved while the
                // packet flew, so guests aim at "now", not "then".
                val adjustedPos = transitCompensated(Protocol.Action.PLAY, event.positionMs)
                val diff = kotlin.math.abs(player.positionMs() - adjustedPos)
                if (player.isPlaying()) {
                    // Periodic PLAY is the host heartbeat; only meaningful
                    // drift on a READY player distorts ongoing playback
                    // (shouldSeekDuringActivePlayback, Metrolist).
                    if (diff > ACTIVE_PLAYBACK_SYNC_TOLERANCE_MS && player.isReady()) {
                        player.seekTo(adjustedPos)
                    }
                } else {
                    if (diff > SOFT_SYNC_THRESHOLD_MS) player.seekTo(adjustedPos)
                    player.play()
                }
            }

            Protocol.Action.PAUSE -> {
                if (routeGuestPlayback(player, localId, targetTrackId, actionTrack, playing = false, positionMs = event.positionMs)) {
                    return
                }
                if (player.isPlaying()) player.pause()
                val diff = kotlin.math.abs(player.positionMs() - event.positionMs)
                // Paused playheads must land exactly on the host's (soft sync).
                if (diff > SOFT_SYNC_THRESHOLD_MS) player.seekTo(event.positionMs)
            }

            Protocol.Action.SEEK -> {
                val playing = _state.value.isPlaying
                val cleanTargetId = ExoPlayerSyncPlayer.trackIdOf(targetTrackId) ?: targetTrackId
                if (buffering && (cleanTargetId.isBlank() || cleanTargetId == bufferingTrackId)) {
                    // SEEK during own buffering merges into the pending payload.
                    pendingSync.set(
                        PendingSync(
                            pendingSync.get()?.trackId ?: cleanTargetId,
                            playing,
                            event.positionMs,
                            System.currentTimeMillis()
                        )
                    )
                    return
                }
                if (buffering) {
                    clearBufferState()
                    reconcileGuestToHostTrack(cleanTargetId, playing = playing)
                    return
                }
                if (cleanTargetId.isNotBlank() && localId != cleanTargetId) {
                    val trackToLoad = actionTrack ?: _state.value.track?.takeIf { it.id == cleanTargetId }
                    if (trackToLoad != null) {
                        ensureGuestTrackLoaded(
                            trackToLoad,
                            playing = player.isPlaying(),
                            startAt = event.positionMs,
                            bypass = true
                        )
                    } else {
                        reconcileGuestToHostTrack(cleanTargetId, playing = player.isPlaying())
                    }
                    return
                }
                // A seek into running playback compensates transit; a seek on
                // a paused room is applied verbatim (Metrolist).
                val seekTarget = if (_state.value.isPlaying && !player.isPlaying()) {
                    transitCompensated(Protocol.Action.SEEK, event.positionMs)
                } else {
                    event.positionMs
                }
                val diff = kotlin.math.abs(player.positionMs() - seekTarget)
                if (diff > SOFT_SYNC_THRESHOLD_MS) player.seekTo(seekTarget)
                // Metrolist: a SEEK into playing playback arms drift correction.
                if (_state.value.isPlaying) drift?.startGuestLoop(scope)
            }

            Protocol.Action.CHANGE_TRACK -> {
                val track = actionTrack ?: return
                // A canonical queue may ride on the change_track packet;
                // replace_queue=true makes an EMPTY queue authoritative too.
                if (event.replaceQueue || event.queue.isNotEmpty()) {
                    replaceRoomQueue(event.queue, event.originUserId)
                }
                val cleanId = ExoPlayerSyncPlayer.trackIdOf(track.id) ?: track.id
                val cleanTrack = track.copy(id = cleanId)
                if (cleanId != player.currentTrackId()) {
                    ensureGuestTrackLoaded(cleanTrack, playing = false, startAt = 0L)
                }
            }

            Protocol.Action.SET_VOLUME, Protocol.Action.SKIP_NEXT, Protocol.Action.SKIP_PREV -> {
                // SET_VOLUME is handled by app settings; SKIP_* never leave the
                // server (ms-playback returns early, no broadcast) — the host's
                // media transition produces the canonical change_track anyway.
                Unit
            }
        }
    }

    /**
     * Shared PLAY/PAUSE prelude: a guest either merges the packet into its
     * in-flight buffer protocol, reconciles a diverged local track, or falls
     * through to the caller's playhead work. Returns true when handled.
     */
    private fun routeGuestPlayback(
        player: ExoPlayerSyncPlayer,
        localId: String?,
        targetTrackId: String,
        actionTrack: TrackInfo?,
        playing: Boolean,
        positionMs: Long
    ): Boolean {
        val cleanTargetId = ExoPlayerSyncPlayer.trackIdOf(targetTrackId) ?: targetTrackId
        if (bufferingTrackId != null) {
            if (cleanTargetId.isNotBlank() && cleanTargetId != bufferingTrackId) {
                // Buffering for another track: purge the protocol first.
                clearBufferState()
                val trackToLoad = actionTrack ?: _state.value.track?.takeIf { it.id == cleanTargetId }
                if (trackToLoad != null) {
                    ensureGuestTrackLoaded(trackToLoad, playing, positionMs, bypass = true)
                } else {
                    reconcileGuestToHostTrack(cleanTargetId, playing)
                }
            } else {
                // Same track we are priming — update the pending payload; the
                // release happens on buffer_complete (or once the player is ready).
                pendingSync.set(
                    PendingSync(cleanTargetId, playing, positionMs, System.currentTimeMillis())
                )
                if (player.isReady() && player.currentTrackId() == cleanTargetId) {
                    applyPendingSync(cleanTargetId, force = true)
                } else {
                    applyPendingSync(cleanTargetId)
                }
            }
            return true
        }
        if (cleanTargetId.isNotBlank() && localId != cleanTargetId) {
            val trackToLoad = actionTrack ?: _state.value.track?.takeIf { it.id == cleanTargetId }
            if (trackToLoad != null) {
                ensureGuestTrackLoaded(trackToLoad, playing, positionMs, bypass = true)
            } else {
                reconcileGuestToHostTrack(cleanTargetId, playing)
            }
            return true
        }
        return false
    }

    /**
     * Server action disagrees with the local track. The room snapshot is
     * authoritative when it already carries that track; otherwise ask the
     * server for a fresh one (Metrolist reconcile pattern).
     */
    private fun reconcileGuestToHostTrack(trackId: String, playing: Boolean) {
        val roomTrack = _state.value.track
        val expected = drift?.expectedPositionMs()?.takeIf { it > 0L }
            ?: _state.value.positionMs
        when {
            roomTrack != null && roomTrack.id == trackId -> ensureGuestTrackLoaded(
                roomTrack,
                playing = playing,
                startAt = expected
            )

            adapter?.currentTrackId() != trackId -> runCatching { client.requestSync() }
        }
    }

    private fun removeQueued(trackId: String) {
        if (trackId.isBlank()) return
        _state.update { it.copy(queue = it.queue.filterNot { q -> q.id == trackId }) }
        // Guest's local playlist mirrors the room's upcoming tracks.
        if (!isHostNow()) adapter?.replaceUpcoming(_state.value.queue)
    }

    private fun replaceRoomQueue(queue: List<TrackInfo>, originUserId: String = "") {
        _state.update { it.copy(queue = queue) }
        val externalEdit = !isHostNow() ||
            (originUserId.isNotBlank() && originUserId != _state.value.userId)
        if (externalEdit) adapter?.replaceUpcoming(queue)
    }

    private fun addQueued(event: ListenTogetherEvent.PlaybackSync) {
        val roomTrack = event.track
        val canonical = event.queue.isNotEmpty() && event.revision > 0L
        if (canonical) {
            // Canonical snapshot — replaceRoomQueue mirrors it for guests.
            replaceRoomQueue(event.queue)
            // The host is excluded there (its local player is authoritative),
            // so mirror the approved insertion explicitly.
            if (isHostNow()) adapter?.replaceUpcoming(_state.value.queue)
        } else if (roomTrack != null) {
            _state.update {
                if (event.insertNext) {
                    it.copy(queue = listOf(roomTrack) + it.queue.filterNot { q -> q.id == roomTrack.id })
                } else {
                    it.copy(queue = it.queue + roomTrack)
                }
            }
            adapter?.replaceUpcoming(_state.value.queue)
        }
    }

    /**
     * Guest track loading (Metrolist buffer protocol): prime the item *paused*,
     * report `buffer_ready`, then wait for the server's `buffer_complete` —
     * applied via [applyPendingSync], which seeks to the extrapolated host
     * playhead so every device starts at the same instant.
     *
     * [bypass] (= Metrolist `bypassBuffer`) skips the room's buffer gate for
     * RECONCILIATIONS only (own divergence, reconnect): the guest aligns
     * directly without bugging the server for a release that was never
     * coordinated for it.
     */
    private fun ensureGuestTrackLoaded(
        track: TrackInfo,
        playing: Boolean,
        startAt: Long = 0L,
        bypass: Boolean = false
    ) {
        val cleanId = ExoPlayerSyncPlayer.trackIdOf(track.id) ?: track.id
        val cleanTrack = track.copy(id = cleanId)
        // The card mirrors the incoming track while the player primes it.
        _state.update { it.copy(track = cleanTrack, isPlaying = playing, isBuffering = true) }
        val player = adapter ?: return

        if (player.currentTrackId() != cleanId) {
            // Warp correction must not fight a paused, priming player.
            drift?.stopGuestLoop()
            val generation = loadGeneration.incrementAndGet()
            if (bypass) {
                pendingSync.set(null)
                bufferCompleteReceivedForTrack = null
                lastBufferReadyTrackId = null
                bufferingTrackId = null
            } else {
                pendingSync.set(PendingSync(cleanId, playing, startAt, System.currentTimeMillis()))
                bufferingTrackId = cleanId
                lastBufferReadyTrackId = null
            }
            android.util.Log.d(TAG, "load $cleanId bypass=$bypass")
            // Preserve the room's canonical upcoming queue in the local
            // player too: guests otherwise lose the playlist on every track
            // switch (their notification/queue UI would go empty).
            val upcoming = _state.value.queue
                .filter { it.id.isNotBlank() && it.id != cleanId }
                .distinctBy { it.id }
            player.loadTrackWithQueue(cleanTrack, upcoming, autoPlay = false)
            scope.launch {
                // Prime barrier: wait for STATE_READY on the matching track before acting.
                var ready = false
                var waited = 0
                while (!ready && waited < 40) {
                    delay(200)
                    waited++
                    if (generation != loadGeneration.get()) return@launch
                    val currentId = player.currentTrackId()
                    if (currentId == cleanId && player.isReady()) {
                        ready = true
                    }
                }
                if (generation != loadGeneration.get()) return@launch
                if (!ready) {
                    android.util.Log.w(TAG, "guest prime timeout for $cleanId — proceeding anyway")
                }

                if (bypass) {
                    // Align + play immediately; no server round-trip.
                    _state.update {
                        it.copy(track = cleanTrack, isPlaying = playing, isBuffering = false)
                    }
                    if (kotlin.math.abs(player.positionMs() - startAt) > SOFT_SYNC_THRESHOLD_MS) {
                        player.seekTo(startAt.coerceAtLeast(0L))
                    }
                    if (playing && !player.isPlaying()) {
                        player.play()
                        drift?.startGuestLoop(scope)
                    } else if (!playing && player.isPlaying()) {
                        player.pause()
                    }
                } else {
                    _state.update { it.copy(isBuffering = false) }
                    android.util.Log.d(TAG, "guest primed $cleanId ready=$ready -> buffer_ready & apply")
                    if (lastBufferReadyTrackId != cleanId) {
                        lastBufferReadyTrackId = cleanId
                        client.bufferReady(cleanId)
                    }
                    // Apply immediately so guest playback starts in sync with host!
                    applyPendingSync(cleanId, force = true)
                }
            }
        } else {
            _state.update { it.copy(isBuffering = false) }
            val pending = pendingSync.get()
            if (pending != null && (ExoPlayerSyncPlayer.trackIdOf(pending.trackId) ?: pending.trackId) == cleanId) {
                applyPendingSync(cleanId, force = true)
            } else {
                // Fully synchronized: reconcile locally only. Bugging the
                // server with buffer_ready here would relay SEEK/PLAY/complete
                // every cycle — the "constant refresh" the members saw.
                if (playing) {
                    if (!player.isPlaying()) {
                        player.seekTo(startAt.coerceAtLeast(0L))
                        player.play()
                    } else if (kotlin.math.abs(player.positionMs() - startAt) >
                        ACTIVE_PLAYBACK_SYNC_TOLERANCE_MS
                    ) {
                        player.seekTo(startAt)
                    }
                } else if (player.isPlaying()) {
                    player.pause()
                    if (kotlin.math.abs(player.positionMs() - startAt) >
                        SOFT_SYNC_THRESHOLD_MS
                    ) {
                        player.seekTo(startAt)
                    }
                }
                drift?.startGuestLoop(scope)
            }
        }
    }

    /**
     * Applies the deferred pending playback once the server's buffer gate
     * opened for the same track (or the guard forces it).
     */
    private fun applyPendingSync(trackId: String? = null, force: Boolean = false) {
        val pending = pendingSync.getAndSet(null) ?: return
        val cleanPendingId = ExoPlayerSyncPlayer.trackIdOf(pending.trackId) ?: pending.trackId
        val cleanTargetId = trackId?.let { ExoPlayerSyncPlayer.trackIdOf(it) ?: it }
        if (cleanTargetId != null && cleanTargetId != cleanPendingId) {
            android.util.Log.d(TAG, "pending $cleanPendingId != complete $cleanTargetId — ignored")
            // Keep the pending: this complete belongs to another track cycle;
            // compare-and-set so a concurrent newer pending wins the race.
            pendingSync.compareAndSet(null, pending)
            return
        }
        if (!force && bufferCompleteReceivedForTrack != cleanPendingId) {
            // Gate still closed — wait for the complete (or the guard).
            pendingSync.compareAndSet(null, pending)
            return
        }
        bufferCompleteReceivedForTrack = null
        bufferingTrackId = null
        _state.update { it.copy(isBuffering = false) }
        val player = adapter ?: return
        val currentLocalId = player.currentTrackId()
        if (currentLocalId != null && currentLocalId != cleanPendingId) {
            android.util.Log.d(TAG, "pending skipped — local track moved on ($currentLocalId vs $cleanPendingId)")
            return
        }
        val shouldPlay = pending.playing
        android.util.Log.d(TAG, "apply pending: track=$cleanPendingId playing=$shouldPlay")
        val expected = when {
            shouldPlay ->
                // Extrapolate the host's playhead to NOW at release time.
                drift?.expectedPositionMs()?.takeIf { it > 0L } ?: pending.positionMs

            else -> pending.positionMs
        }.coerceAtLeast(0L)

        if (kotlin.math.abs(player.positionMs() - expected) > SOFT_SYNC_THRESHOLD_MS) {
            player.seekTo(expected)
        }
        // Apply play state only when it differs (Metrolist).
        if (shouldPlay && !player.isPlaying()) {
            player.play()
            drift?.startGuestLoop(scope)
        } else if (!shouldPlay && player.isPlaying()) {
            player.pause()
        }
    }

    // -------------------------------------------------------------------
    // Local (host) playback broadcasting
    // -------------------------------------------------------------------

    /** The last track id I told the room about; dedups duplicate transitions. */
    @Volatile
    private var lastSyncedTrackId: String? = null

    @Volatile
    private var lastSyncedIsPlaying = false

    /** Set true while the coordinator drives the host player programmatically. */
    @Volatile
    private var isSyncing = false

    /** Last host playhead sample; used when the queue is cleared without a
     *  chance to query the player (menu dismiss, service shutdown). */
    @Volatile
    private var lastHostPositionMs = 0L

    /** Exact track/position captured immediately BEFORE the host clears its
     *  media items. The media transition fires after the player has already
     *  reset its position, so this is the only reliable pause target. */
    @Volatile
    private var pendingEndedTrackId: String? = null

    @Volatile
    private var pendingEndedPositionMs: Long? = null

    @Volatile
    private var hostSnapshotJob: Job? = null

    @Volatile
    private var hostIdleCheckJob: Job? = null

    /**
     * Coalesce the burst of player-listener callbacks a single user action
     * produces (playWhenReady -> isPlaying -> playbackState -> metadata).
     * Without this every pause/play emitted 3-4 identical packets.
     */
    private fun scheduleHostSnapshot(delayMs: Long = 60L) {
        if (!isHostNow() || isSyncing) return
        hostSnapshotJob?.cancel()
        hostSnapshotJob = scope.launch {
            delay(delayMs)
            if (!isHostNow() || isSyncing) return@launch
            startHostSnapshot()
        }
    }

    /**
     * A media item transition may briefly report null while Media3 replaces a
     * playlist. Wait for the player to settle before declaring the room idle;
     * a real clear leaves no current item after the delay.
     */
    private fun scheduleHostIdleCheck(delayMs: Long = 250L, attempt: Int = 0) {
        if (!isHostNow() || isSyncing) return
        hostIdleCheckJob?.cancel()
        hostIdleCheckJob = scope.launch {
            delay(delayMs)
            if (!isHostNow() || isSyncing) return@launch
            val player = adapter
            val current = player?.currentTrackId()
            if (current != null || player?.currentTrackInfo() != null) {
                pendingEndedTrackId = null
                pendingEndedPositionMs = null
                startHostSnapshot()
                return@launch
            }
            if (player?.lastQueryTimedOut == true && attempt < 4) {
                // Local jank, not an idle player: re-check instead of
                // pausing the whole room over a slow main thread.
                scheduleHostIdleCheck(delayMs = 500L, attempt = attempt + 1)
                return@launch
            }
            if (!hasRoomPlaybackToStop()) {
                pendingEndedTrackId = null
                pendingEndedPositionMs = null
                return@launch
            }
            broadcastHostIdle()
        }
    }

    private fun hasRoomPlaybackToStop(): Boolean {
        val snapshot = _state.value
        return pendingEndedTrackId != null ||
            lastSyncedTrackId != null ||
            snapshot.track != null ||
            snapshot.queue.isNotEmpty() ||
            lastObservedQueueIds?.isNotEmpty() == true
    }

    /**
     * Called by the player UI immediately before it clears the media queue
     * (mini-player swipe-down / player dismiss). Captures the host's real
     * playhead while the current item is still loaded, so the room pauses at
     * the right position instead of starting the next track or freezing.
     */
    fun onLocalPlaybackEnding() {
        if (!isHostNow()) return
        val player = adapter
        val trackId = player?.currentTrackId()?.takeIf { it.isNotBlank() }
            ?: _state.value.track?.id?.takeIf { it.isNotBlank() }
            ?: lastSyncedTrackId
        val position = player?.positionMs() ?: lastHostPositionMs
        if (trackId != null) {
            pendingEndedTrackId = trackId
            pendingEndedPositionMs = position.coerceAtLeast(0L)
            lastHostPositionMs = position.coerceAtLeast(0L)
        }
        scheduleHostIdleCheck()
    }

    /**
     * PlayerService is shutting down (notification dismissed, stop on close,
     * sleep timer). The socket outlives the service, so without a final PAUSE
     * members would keep playing forever while the host is silent.
     */
    fun onPlayerServiceDestroyed() {
        val snapshot = _state.value
        if (snapshot.inRoom && isHostNow()) {
            // Read the live playhead while the player is still alive: the
            // service calls this before stopping/releasing its ExoPlayers.
            val player = adapter
            val liveTrackId = player?.currentTrackId()?.let {
                ExoPlayerSyncPlayer.trackIdOf(it) ?: it
            }
            val livePosition = player?.positionMs()?.coerceAtLeast(0L)
            announceHostPaused(
                trackId = liveTrackId
                    ?: pendingEndedTrackId
                    ?: lastSyncedTrackId
                    ?: snapshot.track?.id?.takeIf { it.isNotBlank() },
                positionMs = livePosition
                    ?: pendingEndedPositionMs
                    ?: lastHostPositionMs,
                clearRoom = false
            )
        }
        hostSnapshotJob?.cancel()
        hostIdleCheckJob?.cancel()
        pendingEndedTrackId = null
        pendingEndedPositionMs = null
        // Invalidate any in-flight prime loops bound to the dead adapter.
        loadGeneration.incrementAndGet()
        clearBufferState()
        boundBinder = null
        adapter = null
        drift?.stopGuestLoop()
        drift?.stopHostLoop()
        drift = null
    }

    /** Push a pause for the room's current (or last) track without touching
     *  its queue. [clearRoom] additionally clears the host's local room card
     *  and the server queue after the host wiped its media queue. */
    private fun announceHostPaused(trackId: String?, positionMs: Long, clearRoom: Boolean) {
        if (!isHostNow()) return
        val snapshot = _state.value
        val position = positionMs.coerceAtLeast(0L)
        val hadQueue = snapshot.queue.isNotEmpty() || lastObservedQueueIds?.isNotEmpty() == true
        lastHostPositionMs = position
        lastSyncedIsPlaying = false

        if (clearRoom) {
            _state.update {
                it.copy(
                    track = null,
                    isPlaying = false,
                    positionMs = 0L,
                    queue = emptyList(),
                    isBuffering = false
                )
            }
            _playhead.value = 0L
            lastSyncedTrackId = null
            lastObservedQueueIds = emptyList()
            lastQueueBroadcastAt = System.currentTimeMillis()
        } else {
            _state.update { it.copy(isPlaying = false, positionMs = position) }
            _playhead.value = position
        }

        scope.launch {
            if (!trackId.isNullOrBlank()) {
                client.sendPlaybackAction(
                    PbPlaybackAction(
                        action = Protocol.Action.PAUSE,
                        trackId = trackId,
                        position = position
                    )
                )
            }
            if (clearRoom && hadQueue) {
                client.sendPlaybackAction(
                    PbPlaybackAction(
                        action = Protocol.Action.SYNC_QUEUE,
                        trackId = "",
                        position = 0L,
                        queue = emptyList()
                    )
                )
            }
        }
    }

    /**
     * Push the host's current track state so the room mirrors it.
     *
     * The canonical upcoming queue always rides on CHANGE_TRACK
     * (`replace_queue=true`), because the room can only ever have ONE queue:
     * switching tracks must not leave the previous playlist behind. Queue-only
     * edits use SYNC_QUEUE and include empty queues — clearing the host player
     * is a real queue edit. PLAY doubles as the heartbeat; PAUSE only goes out
     * on an edge so idle rooms stay silent.
     */
    private fun startHostSnapshot() {
        val player = adapter ?: return
        val info = player.currentTrackInfo()
        val rawTrackId = player.currentTrackId()
        val upcoming = runCatching { player.upcomingQueue() }.getOrDefault(emptyList())
        val playing = player.isPlaying()
        val pos = player.positionMs().coerceAtLeast(0L)

        if (info == null || rawTrackId == null) {
            // A null item is only a *real* idle room after an explicit clear
            // captured below (mini-player dismiss / player close). Media3 also
            // reports transient nulls while rebuilding playlists — those must
            // never wipe the room's track or queue.
            if (pendingEndedTrackId != null || pendingEndedPositionMs != null) {
                broadcastHostIdle()
            } else {
                // Unsupported media (radio/local) or a playlist rebuild:
                // re-check once the player settles before declaring idle.
                scheduleHostIdleCheck()
            }
            return
        }

        val trackId = ExoPlayerSyncPlayer.trackIdOf(rawTrackId) ?: rawTrackId
        val cleanInfo = info.copy(id = trackId)
        lastHostPositionMs = pos
        val queueTracks = upcoming
            .filter { it.id.isNotBlank() && it.id != trackId }
            .distinctBy { it.id }
        val queueIds = queueTracks.map { it.id }
        val trackChanged = trackId != lastSyncedTrackId
        val queueChanged = queueIds != lastObservedQueueIds
        if (BuildConfig.DEBUG) android.util.Log.d(
            TAG,
            "host snapshot: track=$trackId playing=$playing pos=$pos " +
                "upcoming=${queueTracks.size} queueChanged=$queueChanged"
        )

        // The host's local player is the room's source of truth — mirror it
        // immediately (including an emptied queue) so the UI never lags.
        _state.update { current ->
            current.copy(
                track = cleanInfo,
                isPlaying = playing,
                positionMs = pos,
                queue = queueTracks
            )
        }

        scope.launch {
            if (trackChanged || queueChanged) {
                lastObservedQueueIds = queueIds
                lastQueueBroadcastAt = System.currentTimeMillis()
            }

            if (trackChanged) {
                lastSyncedTrackId = trackId
                // Server resets IsPlaying=false on track change; PLAY re-arms.
                lastSyncedIsPlaying = false
                client.sendPlaybackAction(
                    PbPlaybackAction(
                        action = Protocol.Action.CHANGE_TRACK,
                        trackId = trackId,
                        position = 0L,
                        trackInfo = toPb(cleanInfo),
                        queue = queueTracks.map(::toPb),
                        replaceQueue = true
                    )
                )
            } else if (queueChanged) {
                // Empty queues are meaningful: the host emptied its player.
                client.sendPlaybackAction(
                    PbPlaybackAction(
                        action = Protocol.Action.SYNC_QUEUE,
                        trackId = "",
                        position = 0L,
                        queue = queueTracks.map(::toPb)
                    )
                )
            }

            if (playing) {
                // PLAY is also the heartbeat — send unconditionally.
                lastSyncedIsPlaying = true
                client.sendPlaybackAction(
                    PbPlaybackAction(
                        action = Protocol.Action.PLAY,
                        trackId = trackId,
                        position = pos,
                        trackInfo = toPb(cleanInfo)
                    )
                )
            } else if (lastSyncedIsPlaying || trackChanged) {
                // PAUSE on edge or when track changed while paused.
                lastSyncedIsPlaying = false
                client.sendPlaybackAction(
                    PbPlaybackAction(
                        action = Protocol.Action.PAUSE,
                        trackId = trackId,
                        position = pos,
                        trackInfo = toPb(cleanInfo)
                    )
                )
            }
        }
    }

    private val hostListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!isHostNow() || isSyncing) return
            scheduleHostSnapshot()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isHostNow() || isSyncing) return
            scheduleHostSnapshot()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isHostNow() || isSyncing) return
            if (playbackState == Player.STATE_IDLE) {
                // Covers clearMediaItems() from any path that did not hook
                // onLocalPlaybackEnding(): settle, then declare the room idle.
                scheduleHostIdleCheck()
            } else {
                scheduleHostSnapshot()
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (!isHostNow() || isSyncing) return
            scheduleHostSnapshot()
        }

        override fun onPositionDiscontinuity(
            positionInfo: Player.PositionInfo,
            newPositionInfo: Player.PositionInfo,
            reason: Int
        ) {
            if (!isHostNow()) return
            if (reason == Player.DISCONTINUITY_REASON_REMOVE) {
                // The current item was removed by the queue UI: capture the
                // playhead it had before the player reset to the next item.
                lastHostPositionMs = positionInfo.positionMs.coerceAtLeast(0L)
                if (pendingEndedTrackId == null) {
                    pendingEndedTrackId = lastSyncedTrackId
                        ?: _state.value.track?.id?.takeIf { it.isNotBlank() }
                    pendingEndedPositionMs = lastHostPositionMs
                }
                scheduleHostIdleCheck()
                return
            }
            // Only user-initiated seeks; live, immediate — matches Metrolist.
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (isSyncing) return
            val player = adapter ?: return
            val trackId = player.currentTrackId() ?: return
            val position = newPositionInfo.positionMs.coerceAtLeast(0L)
            lastHostPositionMs = position
            _playhead.value = position
            _state.update { it.copy(positionMs = position) }
            scope.launch {
                client.sendPlaybackAction(
                    PbPlaybackAction(
                        action = Protocol.Action.SEEK,
                        trackId = trackId,
                        position = position,
                        trackInfo = player.currentTrackInfo()?.let(::toPb)
                    )
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isHostNow()) return
            if (mediaItem == null) {
                // The host wiped its player (or the last item vanished).
                // Remember which track/position to pause before anything
                // resets, then wait for the playlist to settle.
                android.util.Log.d(TAG, "host media item cleared -> idle check")
                if (pendingEndedTrackId == null) {
                    pendingEndedTrackId = lastSyncedTrackId
                        ?: _state.value.track?.id?.takeIf { it.isNotBlank() }
                    pendingEndedPositionMs = lastHostPositionMs
                }
                scheduleHostIdleCheck()
                return
            }
            // A load driven BY the coordinator is not a user transition.
            if (isSyncing) return
            val newId = ExoPlayerSyncPlayer.trackIdOf(mediaItem.mediaId)
            if (newId == null) {
                // Radio / local file: not shareable. Reconcile the room once
                // the player settles (the unsupported-item warning handles UI).
                scheduleHostIdleCheck()
                return
            }
            if (newId == lastSyncedTrackId && _state.value.track?.id == newId) return
            android.util.Log.d(TAG, "host transition -> $newId")
            scheduleHostSnapshot(delayMs = 0L)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // Playlist edits (guest-approved insertions, removals, reorders)
            // do not always produce a media-item transition. Debounce the
            // burst into one snapshot so the room gets the canonical queue.
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            if (!isHostNow() || isSyncing) return
            scheduleHostSnapshot()
        }
    }

    /**
     * Explicit host idle path: the player was emptied. Pause the room on the
     * last known track (guests would otherwise keep playing stale audio),
     * clear the mirrored queue, and drop the stale now-playing card.
     */
    private fun broadcastHostIdle() {
        val trackId = pendingEndedTrackId
            ?: lastSyncedTrackId
            ?: _state.value.track?.id?.takeIf { it.isNotBlank() }
        val position = pendingEndedPositionMs ?: lastHostPositionMs
        pendingEndedTrackId = null
        pendingEndedPositionMs = null
        announceHostPaused(trackId, position, clearRoom = true)
    }

    private fun toPb(track: TrackInfo): PbTrackInfo =
        PbTrackInfo(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            // Metrolist parity: an unresolved duration is broadcast as a
            // 3-minute default so the server clamp and guest seek bars stay
            // usable from the first packet (the ticker back-fills the real
            // duration locally once the stream resolves).
            duration = if (track.duration > 0L) track.duration else 180_000L,
            thumbnail = track.thumbnail,
            suggestedBy = track.suggestedBy
        )

    // -------------------------------------------------------------------
    // Loops
    // -------------------------------------------------------------------

    private fun startHostLoops() {
        adapter?.setPauseAtEndOfMediaItems(false)
        val engine = drift ?: return
        engine.stopGuestLoop()
        // A member promoted from guest may still carry a drift warp (0.98x /
        // 1.02x). Clear it so the new host broadcasts at normal speed.
        engine.reset()
        // Metrolist heartbeat: PLAY every 8s, ONLY while playing a ready
        // track — never PAUSE spam (the play/pause EDGES go through the
        // host listener), never packets for an idle player.
        engine.startHostLoop(scope) { positionMs, playing, trackId ->
            client.sendPlaybackAction(
                PbPlaybackAction(
                    action = Protocol.Action.PLAY,
                    trackId = trackId.orEmpty(),
                    position = positionMs,
                    // Periodic snapshot still carries metadata so late-joining
                    // guests can reconcile without waiting for request_sync.
                    trackInfo = adapter?.currentTrackInfo()?.let(::toPb)
                )
            )
        }
        _state.update { it.copy(isHost = true) }
    }

    private fun startGuestLoops() {
        adapter?.setPauseAtEndOfMediaItems(true)
        val engine = drift ?: return
        engine.stopHostLoop()
        engine.startGuestLoop(scope)
        _state.update { it.copy(isHost = false) }
    }

    /** One-second ticker updating the visible playhead while in a room. */
    private fun startUiTicker() {
        if (uiTickerJob?.isActive == true) return
        uiTickerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val snapshot = _state.value
                if (!snapshot.inRoom) continue
                val player = adapter ?: continue
                // One main-thread round-trip per tick carries position,
                // duration, playing/buffering flags, track id and
                // unsupported-media state.
                val local = player.playbackSnapshot()

                // Playhead lives in its OWN hot flow — the main room state
                // must not churn once a second (the whole screen would
                // recompose members, suggestions and the queue each tick).
                _playhead.value = local.positionMs
                // Only a health CHANGE belongs in the main room state:
                // publishing the raw per-poll delta re-emitted the whole
                // screen every second, defeating the dedicated playhead flow.
                val engineDrift = drift?.drift?.value
                if (engineDrift != null && engineDrift.health != snapshot.drift.health) {
                    _state.update { it.copy(drift = engineDrift) }
                }

                // The track card needs a usable duration; sync packets can
                // carry 0 or 180s fallback (the host's player hadn't resolved it yet) — the
                // local player knows better once primed, both roles.
                val currentTrack = _state.value.track
                if (local.unsupported != snapshot.isUnsupportedLocalPlayback) {
                    _state.update { it.copy(isUnsupportedLocalPlayback = local.unsupported) }
                }
                if (currentTrack != null) {
                    val known = local.durationMs
                    if (known > 0L && (currentTrack.duration <= 0L || currentTrack.duration == 180_000L)) {
                        _state.update { s ->
                            val track = s.track ?: return@update s
                            if (track.duration <= 0L || track.duration == 180_000L) {
                                s.copy(track = track.copy(duration = known))
                            } else s
                        }
                    }
                }

                if (snapshot.isHost) {
                    // The same batched read feeds the card reconciliation
                    // and the echo watchdog below.
                    val localTrackId = local.trackId
                    if (snapshot.track == null) {
                        val info = player.currentTrackInfo()
                        if (info != null) {
                            startHostSnapshot()
                        } else if (hasRoomPlaybackToStop()) {
                            scheduleHostIdleCheck()
                        }
                    } else if (localTrackId == null) {
                        // The card still shows a song but the player was
                        // emptied out-of-band — reconcile the room to idle.
                        scheduleHostIdleCheck()
                    } else {
                        val actualPlaying = local.isPlaying
                        if (actualPlaying != snapshot.isPlaying) {
                            _state.update { it.copy(isPlaying = actualPlaying) }
                        }
                    }

                    // Queue edits are pushed by hostListener.onTimelineChanged
                    // and startHostSnapshot(); polling the queue here duplicated
                    // that work (and allocated a TrackInfo list every second).

                    // Room-ack watchdog: while we (think we) are playing, the
                    // server must keep echoing our playhead packets. A silent
                    // room means our packets were rejected or lost — restart
                    // the whole announcement.
                    if (
                        snapshot.isPlaying && localTrackId != null && bufferingTrackId == null &&
                        (lastHostEchoTrackId != localTrackId ||
                            System.currentTimeMillis() - lastHostEchoAt > 20_000L)
                    ) {
                        android.util.Log.w(TAG, "no server echo for $localTrackId — re-announcing room")
                        requestHostReannounce()
                    }
                } else {
                    enforceGuestRoomControl(snapshot, local)
                }
            }
        }
    }

    /**
     * Metrolist `shouldBlockPlaybackChanges` analog. Guest-local playback is
     * not part of the protocol (the server rejects guest actions outright),
     * so a member's own play in the app must not derail the room: the local
     * player is pulled back onto the room's track and the user is informed.
     */
    private fun enforceGuestRoomControl(snapshot: TogetherUiState, local: PlaybackSnapshot) {
        if (bufferingTrackId != null) return
        // A load/seek is mid-flight (the buffer state machine or our own
        // previous correction): logarithmic loops of snap-back + reload are
        // exactly the "constant refresh" the members complained about.
        if (local.isBuffering) return
        val roomTrack = snapshot.track
        val localId: String? = local.trackId
        val now = System.currentTimeMillis()
        when {
            roomTrack != null && roomTrack.id.isNotBlank() &&
                localId != null && localId != roomTrack.id -> {
                if (now - lastGuestGuardAt < 3_000L) return
                lastGuestGuardAt = now
                android.util.Log.w(
                    TAG,
                    "guest diverged ($localId vs room ${roomTrack.id}) -> snapping back"
                )
                notifyGuestBlocked()
                ensureGuestTrackLoaded(
                    roomTrack,
                    playing = snapshot.isPlaying,
                    startAt = drift?.expectedPositionMs()?.takeIf { it > 0L } ?: _playhead.value,
                    // Own divergence — reconcile directly, no room gate
                    // (Metrolist reconcileGuestToHostTrack -> bypassBuffer).
                    bypass = true
                )
            }

            roomTrack == null && localId != null && local.isPlaying -> {
                // The room is idle — members may not start private playback.
                if (now - lastGuestGuardAt < 3_000L) return
                lastGuestGuardAt = now
                android.util.Log.w(TAG, "guest started playback in an idle room -> pause")
                notifyGuestBlocked()
                adapter?.pause()
            }
        }
    }

    private var lastGuestGuardAt = 0L

    private fun notifyGuestBlocked() {
        // Rate-limited by the guard itself (3s); posted on the main thread.
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(
                    Dependencies.application,
                    "Listen together: the host controls playback — use Ask to play",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // -------------------------------------------------------------------
    // Connectivity re-handshake
    // -------------------------------------------------------------------

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        try {
            val context = Dependencies.application
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (_state.value.inRoom) {
                            client.onNetworkChanged()
                        }
                    }
                }
            )
        } catch (_: SecurityException) {
            networkCallbackRegistered = false
        }
    }

    // -------------------------------------------------------------------
    // Lifecycle awareness (ProcessLifecycleOwner) — the coordinator itself
    // lives in a singleton, but the one-second room ticker (playhead, guest
    // guard, host echo watchdog) must not wake the CPU in the background.
    // -------------------------------------------------------------------

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appForeground = true
            maybeStartTicker()
        }

        override fun onStop(owner: LifecycleOwner) {
            appForeground = false
            stopUiTicker()
        }
    }

    private fun maybeStartTicker() {
        // Run while the screen is open, or while a room is active off-screen
        // (guest guard + host echo watchdog), but never in the background.
        val shouldRun = appForeground && (uiVisible || _state.value.inRoom)
        if (shouldRun) startUiTicker() else stopUiTicker()
    }

    private fun stopUiTicker() {
        uiTickerJob?.cancel()
        uiTickerJob = null
    }

    /** Call from the TogetherScreen when it appears/disappears. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        maybeStartTicker()
    }

    // -------------------------------------------------------------------
    // Public commands
    // -------------------------------------------------------------------

    private suspend fun ensureConnected(): Boolean = when (client.connectionState.value) {
        ConnectionState.Connected -> true
        ConnectionState.Fatal -> {
            // Backoff exhausted earlier (server outage). A new user action
            // restarts the whole attempt chain from zero.
            client.disconnect()
            client.connect()
            awaitConnectionEstablished()
        }

        else -> {
            client.connect()
            awaitConnectionEstablished()
        }
    }

    private suspend fun awaitConnectionEstablished(): Boolean {
        var waited = 0
        while (client.connectionState.value != ConnectionState.Connected && waited < 10_000) {
            if (client.connectionState.value == ConnectionState.Fatal) return false
            delay(200)
            waited += 200
        }
        return client.connectionState.value == ConnectionState.Connected
    }

    fun createRoom(username: String = ListenTogetherPreferences.userName) {
        if (_state.value.inRoom) return
        _state.update {
            it.copy(
                isCreatingRoom = true,
                isJoiningRoom = false,
                isAwaitingJoinApproval = false,
                error = null
            )
        }
        scope.launch {
            if (!ensureConnected()) {
                _state.update {
                    it.copy(
                        isCreatingRoom = false,
                        isJoiningRoom = false,
                        isAwaitingJoinApproval = false,
                        error = Dependencies.application.getString(R.string.together_connect_failed)
                    )
                }
                return@launch
            }
            client.createRoom(username)
            startLobbyTimeout(CREATE_ROOM_TIMEOUT_MS, create = true)
        }
    }

    fun extractRoomCode(input: String): String {
        val trimmed = input.trim().uppercase()
        val sanitized = trimmed.replace(Regex("[^A-Z0-9]"), "")
        if (sanitized.length in 6..10) return sanitized

        val prefixMatch = Regex("""\b(?:ROOM\s*CODE|CODE|ROOM)[:\s]+([A-Z0-9]{6,10})""").find(trimmed)
        if (prefixMatch != null) return prefixMatch.groupValues[1]

        val urlMatch = Regex("""(?:TOGETHER|JOIN)/([A-Z0-9]{6,10})""").find(trimmed)
        if (urlMatch != null) return urlMatch.groupValues[1]

        val tokens = Regex("""\b[A-Z0-9]{6,10}\b""").findAll(trimmed).map { it.value }.toList()
        val digitTokens = tokens.filter { t -> t.any { c -> c.isDigit() } }
        if (digitTokens.isNotEmpty()) return digitTokens.last()

        return sanitized
    }

    fun joinRoom(roomCode: String, username: String = ListenTogetherPreferences.userName) {
        if (_state.value.inRoom) return
        val code = extractRoomCode(roomCode)
        if (code.isBlank()) {
            _state.update {
                it.copy(error = Dependencies.application.getString(R.string.together_invalid_room_code))
            }
            return
        }
        _state.update {
            it.copy(
                isJoiningRoom = true,
                isAwaitingJoinApproval = false,
                isCreatingRoom = false,
                error = null
            )
        }
        scope.launch {
            if (!ensureConnected()) {
                _state.update {
                    it.copy(
                        isCreatingRoom = false,
                        isJoiningRoom = false,
                        isAwaitingJoinApproval = false,
                        error = Dependencies.application.getString(R.string.together_connect_failed)
                    )
                }
                return@launch
            }
            client.joinRoom(code, username)
            _state.update { it.copy(isAwaitingJoinApproval = true) }
            startLobbyTimeout(JOIN_ROOM_TIMEOUT_MS, create = false)
        }
    }

    /** Fail create/join if the server never answers (no ack, no error). */
    private fun startLobbyTimeout(timeoutMs: Long, create: Boolean) {
        stopLobbyTimeout()
        lobbyTimeoutJob = scope.launch {
            delay(timeoutMs)
            val snapshot = _state.value
            if (snapshot.inRoom) return@launch
            if (create && !snapshot.isCreatingRoom) return@launch
            if (!create && !snapshot.isJoiningRoom) return@launch
            _state.update {
                it.copy(
                    isCreatingRoom = false,
                    isJoiningRoom = false,
                    isAwaitingJoinApproval = false,
                    error = Dependencies.application.getString(
                        if (create) R.string.together_action_timeout
                        else R.string.together_join_timeout
                    )
                )
            }
        }
    }

    private fun stopLobbyTimeout() {
        lobbyTimeoutJob?.cancel()
        lobbyTimeoutJob = null
    }

    /** Map server error codes to human-readable localized copy. */
    private fun humanizeError(code: String, message: String): String {
        val resources = Dependencies.application.resources
        return when {
            code == "room_not_found" || code == "invalid_room" ->
                resources.getString(R.string.together_room_not_found)
            code == "room_full" ->
                resources.getString(R.string.together_room_full)
            code == "declined" || code == "join_declined" ->
                resources.getString(R.string.together_join_declined)
            code == "connect_failed" ->
                resources.getString(R.string.together_connect_failed)
            code == "action_timeout" ->
                resources.getString(R.string.together_action_timeout)
            code == "request_timeout" ->
                resources.getString(R.string.together_request_timeout)
            code == "play_failed" ->
                resources.getString(R.string.together_play_failed)
            code == "session_not_found" || code == "invalid_session" || code == "invalid_token" || code == "room_closed" ->
                resources.getString(R.string.together_connection_lost)
            message.isNotBlank() && !message.equals("null", ignoreCase = true) -> message
            else -> code
        }
    }

    /** Server rejection reasons -> human copy (raw codes never reach the UI). */
    private fun joinRejectionMessage(reason: String): String {
        val resources = Dependencies.application.resources
        return when {
            reason.contains("not_found", ignoreCase = true) ||
                reason.contains("invalid", ignoreCase = true) ||
                reason.contains("unknown", ignoreCase = true) ->
                resources.getString(R.string.together_room_not_found)
            reason.contains("full", ignoreCase = true) ->
                resources.getString(R.string.together_room_full)
            reason.contains("reject", ignoreCase = true) ||
                reason.contains("declin", ignoreCase = true) ||
                reason.contains("denied", ignoreCase = true) ->
                resources.getString(R.string.together_join_declined)
            reason.isBlank() -> resources.getString(R.string.together_action_timeout)
            else -> reason
        }
    }

    /** Restore the persisted session (app relaunch inside a room). */
    fun restoreSession() {
        val token = ListenTogetherPreferences.sessionToken
        if (token.isBlank()) return
        client.setReconnectToken(token)
        scope.launch {
            if (!ensureConnected()) {
                _state.update {
                    it.copy(error = Dependencies.application.getString(R.string.together_connect_failed))
                }
                return@launch
            }
            client.sendReconnect(token)
        }
    }

    fun leave() {
        scope.launch {
            runCatching { client.leave() }
        }
        teardownRoom()
    }

    fun approveJoin(userId: String) {
        client.approveJoin(userId)
        TogetherNotifications.cancel(Dependencies.application, TogetherNotifications.idForJoin(userId))
        _state.update {
            it.copy(pendingJoinRequests = it.pendingJoinRequests.filterNot { r -> r.userId == userId })
        }
    }

    fun rejectJoin(userId: String) {
        client.rejectJoin(userId, "declined")
        TogetherNotifications.cancel(Dependencies.application, TogetherNotifications.idForJoin(userId))
        _state.update {
            it.copy(pendingJoinRequests = it.pendingJoinRequests.filterNot { r -> r.userId == userId })
        }
    }

    /** Notification-action entry points (host may act from any screen). */
    fun approveJoinById(userId: String) {
        if (_state.value.pendingJoinRequests.any { it.userId == userId }) approveJoin(userId)
        else TogetherNotifications.cancel(Dependencies.application, TogetherNotifications.idForJoin(userId))
    }

    fun rejectJoinById(userId: String) {
        if (_state.value.pendingJoinRequests.any { it.userId == userId }) rejectJoin(userId)
        else TogetherNotifications.cancel(Dependencies.application, TogetherNotifications.idForJoin(userId))
    }

    fun kickUser(userId: String) = client.kickUser(userId, "removed by host")

    fun transferHost(userId: String) = client.transferHost(userId)

    /** Host: jump the whole room to a queue item, synchronized. */
    fun playQueueItem(track: TrackInfo) {
        if (track.id.isBlank()) return
        scope.launch {
            if (!isHostNow()) return@launch
            val player = adapter ?: return@launch
            val cleanId = ExoPlayerSyncPlayer.trackIdOf(track.id) ?: track.id
            val cleanTrack = track.copy(id = cleanId)
            if (player.currentTrackId() == cleanId) {
                // Replay the room's current track from the top (1ms hack — a
                // 0 position is indistinguishable from a missing payload).
                val wasPlaying = player.isPlaying()
                client.sendPlaybackAction(
                    PbPlaybackAction(
                        action = Protocol.Action.SEEK,
                        trackId = cleanId,
                        position = 1L,
                        trackInfo = toPb(cleanTrack)
                    )
                )
                if (!wasPlaying) {
                    // A paused room must actually start: SEEK alone keeps it
                    // frozen on every guest.
                    lastSyncedTrackId = cleanId
                    lastSyncedIsPlaying = true
                    client.sendPlaybackAction(
                        PbPlaybackAction(
                            action = Protocol.Action.PLAY,
                            trackId = cleanId,
                            position = 1L,
                            trackInfo = toPb(cleanTrack)
                        )
                    )
                    _state.update { it.copy(isPlaying = true, positionMs = 0L) }
                }
                player.seekTo(0L)
                if (!wasPlaying) player.play()
                return@launch
            }

            // Remaining canonical queue = everything AFTER the tapped entry
            // (server advanceQueue / Metrolist suffix semantics). An entry not
            // in the queue (e.g. an approved suggestion) keeps the queue.
            val roomQueue = _state.value.queue
            val selectedIndex = roomQueue.indexOfFirst { it.id == cleanId }
            val remaining = (if (selectedIndex >= 0) {
                roomQueue.drop(selectedIndex + 1)
            } else {
                roomQueue
            })
                .filter { it.id.isNotBlank() && it.id != cleanId }
                .distinctBy { it.id }
            lastObservedQueueIds = remaining.map { it.id }
            lastQueueBroadcastAt = System.currentTimeMillis()
            lastSyncedTrackId = cleanId
            lastSyncedIsPlaying = true

            // The queue rides on the track change: replace_queue=true keeps
            // the room from showing the previous playlist under the new song.
            client.sendPlaybackAction(
                PbPlaybackAction(
                    action = Protocol.Action.CHANGE_TRACK,
                    trackId = cleanId,
                    position = 0L,
                    trackInfo = toPb(cleanTrack),
                    queue = remaining.map(::toPb),
                    replaceQueue = true
                )
            )
            client.sendPlaybackAction(
                PbPlaybackAction(
                    action = Protocol.Action.PLAY,
                    trackId = cleanId,
                    position = 0L,
                    trackInfo = toPb(cleanTrack)
                )
            )
            _state.update { it.copy(track = cleanTrack, isPlaying = true, queue = remaining, positionMs = 0L) }
            _playhead.value = 0L
            // Command source: arm our own player with the same canonical shape.
            isSyncing = true
            try {
                player.loadTrackWithQueue(cleanTrack, remaining, autoPlay = true)
            } finally {
                delay(600)
                isSyncing = false
            }
        }
    }

    /** Host: seek the live room's playhead (seek bar). */
    fun seekRoom(positionMs: Long) {
        scope.launch {
            if (!isHostNow()) return@launch
            if (positionMs < 0L) return@launch
            val player = adapter ?: return@launch
            val roomTrack = _state.value.track
            if (player.currentTrackId() == null && roomTrack != null) {
                // The room card outlived the local player (service handoff):
                // reload the track (with the room queue) before seeking, or
                // the seek lands nowhere.
                val clean = roomTrack.copy(
                    id = ExoPlayerSyncPlayer.trackIdOf(roomTrack.id) ?: roomTrack.id
                )
                player.loadTrackWithQueue(
                    clean,
                    _state.value.queue.filter { it.id != clean.id },
                    autoPlay = _state.value.isPlaying
                )
                var waited = 0
                while (player.currentTrackId() == null && waited < 10) {
                    delay(100)
                    waited++
                }
            }
            // Optimistic UI: the seek bar should snap immediately instead of
            // waiting for the next ticker pass. The SEEK discontinuity listener
            // then broadcasts the action to the room (do NOT set isSyncing
            // here — that suppresses the broadcast).
            _playhead.value = positionMs
            _state.update { it.copy(positionMs = positionMs) }
            player.seekTo(positionMs)
        }
    }

    /** Host: play/pause the whole room (not just the local player). */
    fun setRoomPlaying(playing: Boolean) {
        scope.launch {
            if (!isHostNow()) return@launch
            val player = adapter ?: return@launch
            val roomTrack = _state.value.track?.takeIf { it.id.isNotBlank() }
            val track = roomTrack ?: player.currentTrackInfo() ?: return@launch
            val trackId = ExoPlayerSyncPlayer.trackIdOf(track.id) ?: track.id
            val cleanTrack = track.copy(id = trackId)
            val localId = player.currentTrackId()
            val roomKnowsTrack = roomTrack?.id == trackId
            // Prefer the live playhead; fall back to the card's position when
            // the local player lost its item and is about to be reloaded.
            val position = if (localId == trackId) {
                player.positionMs().coerceAtLeast(0L)
            } else {
                _state.value.positionMs.coerceAtLeast(0L)
            }
            val sendPosition = if (roomKnowsTrack) position else 0L

            if (localId != trackId) {
                // Re-arm a player that no longer holds the room's track,
                // restoring the canonical queue alongside it.
                player.loadTrackWithQueue(cleanTrack, _state.value.queue, autoPlay = false)
                if (roomKnowsTrack) player.seekTo(position)
            }
            if (!roomKnowsTrack) {
                // The server only accepts transport actions for the track it
                // knows — announce the canonical track (and queue) first.
                lastObservedQueueIds = _state.value.queue.map { it.id }
                lastQueueBroadcastAt = System.currentTimeMillis()
                client.sendPlaybackAction(
                    PbPlaybackAction(
                        action = Protocol.Action.CHANGE_TRACK,
                        trackId = trackId,
                        position = 0L,
                        trackInfo = toPb(cleanTrack),
                        queue = _state.value.queue.map(::toPb),
                        replaceQueue = true
                    )
                )
            }
            lastSyncedTrackId = trackId
            lastSyncedIsPlaying = playing
            client.sendPlaybackAction(
                PbPlaybackAction(
                    action = if (playing) Protocol.Action.PLAY else Protocol.Action.PAUSE,
                    trackId = trackId,
                    position = sendPosition,
                    trackInfo = if (playing) toPb(cleanTrack) else null
                )
            )
            _state.update { it.copy(track = cleanTrack, isPlaying = playing, positionMs = sendPosition) }
            if (!roomKnowsTrack) _playhead.value = 0L
            // Act as the command source: suppress the duplicate edge the local
            // player listener would otherwise emit for this programmatic call.
            isSyncing = true
            try {
                if (playing) player.play() else player.pause()
            } finally {
                isSyncing = false
            }
        }
    }

    /** Member: propose a song to the host — inserted next on approval. */
    fun suggestSong(input: String) {
        val videoId = normalizeVideoId(input)
        if (videoId.isBlank()) return
        scope.launch {
            if (!ensureConnected()) return@launch
            client.suggestTrack(resolveTrack(videoId))
        }
    }

    /**
     * Resolve a video id to shareable metadata. metroserver's
     * `sanitizeTrackInfo` rejects tracks without a title, so failures still
     * produce an id-titled fallback rather than being dropped.
     */
    private suspend fun resolveTrack(videoId: String): TrackInfo {
        // Result<Innertube.SongItem?>? — null on cancellation, failure, or no cue.
        val song = runCatching { Innertube.song(videoId) }.getOrNull()?.getOrNull()
        return song?.let {
            TrackInfo(
                id = videoId,
                title = it.info?.name.orEmpty().ifBlank { videoId },
                artist = it.authors.orEmpty()
                    .joinToString(", ") { a -> a.name.orEmpty() }
                    .ifBlank { "YouTube" },
                duration = it.durationText?.let(ExoPlayerSyncPlayer::parseDurationText) ?: 0L,
                thumbnail = it.thumbnail?.url.orEmpty()
            )
        } ?: TrackInfo(id = videoId, title = videoId, artist = "")
    }

    fun approveSuggestion(suggestion: TogetherSuggestion) {
        TogetherNotifications.cancel(
            Dependencies.application,
            TogetherNotifications.idForSuggestion(suggestion.suggestionId)
        )
        val track = suggestion.track
        if (suggestion.isPlayRequest && track != null && track.id.isNotBlank()) {
            approvePlayRequest(suggestion, track)
            return
        }
        client.approveSuggestion(suggestion.suggestionId)
        _state.update {
            it.copy(suggestions = it.suggestions.filterNot { s -> s.suggestionId == suggestion.suggestionId })
        }
    }

    /**
     * Guest: ask the host to *play* a queued track right now. Implemented as
     * a relay over the suggestion pipeline — the only guest→host channel the
     * protocol offers. The track is echoed back VERBATIM (only
     * `suggested_by` is cleared, exactly like a stock client sends): the
     * server validates suggest content and rejects anything it did not
     * itself produce ("invalid suggest track payload"), so the payload must
     * be byte-equivalent to what the server sent us. The host detects the
     * intent positionally (suggested track already in the room — see
     * SuggestionReceived). Events suggesters receive carry no requester
     * identity, so the pending marker (by track id) is ours.
     */
    fun requestPlay(track: TrackInfo) {
        if (track.id.isBlank()) return
        scope.launch {
            if (isHostNow() || !ensureConnected()) return@launch
            // One request in flight; the marker doubles as the "waiting" flag.
            if (_state.value.pendingPlayRequest != null) return@launch
            android.util.Log.d(
                TAG,
                "requestPlay(${track.id}) relay echo " +
                    "title=${track.title} album=${track.album} " +
                    "suggestedBy=${track.suggestedBy} duration=${track.duration}"
            )
            _state.update { it.copy(pendingPlayRequest = track.id) }
            armPlayRequestTimeout(track.id)
            client.suggestTrack(track.copy(suggestedBy = ""))
        }
    }

    /** Re-enables the request button if the host never answers. */
    private var playRequestTimeoutJob: Job? = null

    private fun armPlayRequestTimeout(trackId: String) {
        playRequestTimeoutJob?.cancel()
        playRequestTimeoutJob = scope.launch {
            delay(90_000)
            _state.update {
                if (it.pendingPlayRequest == trackId) {
                    it.copy(
                        pendingPlayRequest = null,
                        error = Dependencies.application.getString(R.string.together_request_timeout)
                    )
                } else {
                    it
                }
            }
        }
    }

    private fun clearPlayRequestTimeout() {
        playRequestTimeoutJob?.cancel()
        playRequestTimeoutJob = null
    }

    /**
     * Host: a member asked to play this track NOW. Grants via the suggestion
     * pipeline and then executes the room switch locally (only the host can
     * act on playback).
     *
     * Duplicate note: the server rejects approvals with `duplicate_track`
     * when the track is already queued or playing — which is exactly the
     * usual case for queue-item requests — so the local check must happen
     * BEFORE approving, playing directly and cleansing the pending card
     * instead of fooling the server into an error state.
     */
    private fun approvePlayRequest(suggestion: TogetherSuggestion, track: TrackInfo) {
        scope.launch {
            if (!isHostNow()) return@launch
            val state = _state.value
            val alreadyInRoom = state.track?.id == track.id ||
                state.queue.any { it.id == track.id }
            if (alreadyInRoom) {
                // Track is in the room: grant + switch directly via the
                // shared direct-grant path.
                playSuggestionNow(suggestion)
                return@launch
            }
            // Fresh track: approve — the server inserts it next and the
            // QUEUE_ADD broadcast completes the relay in dispatchRelayedPlay.
            pendingRelayedPlay = RelayedPlay(
                trackId = track.id,
                suggestionId = suggestion.suggestionId,
                expiresAtMs = System.currentTimeMillis() + 15_000
            )
            android.util.Log.d(TAG, "relay grant: approving ${track.id}, awaiting QUEUE_ADD")
            client.approveSuggestion(suggestion.suggestionId)
            _state.update {
                it.copy(suggestions = it.suggestions.filterNot { s -> s.suggestionId == suggestion.suggestionId })
            }
            armRelayedPlayGuard()
        }
    }

    /**
     * Relay safety valve: if the QUEUE_ADD never lands (dropped frame,
     * server hiccup), attempt the switch from local knowledge anyway.
     */
    private fun armRelayedPlayGuard() {
        scope.launch {
            delay(15_000)
            val relay = pendingRelayedPlay ?: return@launch
            pendingRelayedPlay = null
            android.util.Log.w(TAG, "relay guard fired for ${relay.trackId}")
            val relayTrack = _state.value.queue.firstOrNull { it.id == relay.trackId }
            if (relayTrack != null) {
                playQueueItem(relayTrack)
            } else {
                runCatching { client.rejectSuggestion(relay.suggestionId, "unavailable") }
                _state.update { it.copy(error = Dependencies.application.getString(R.string.together_play_failed)) }
            }
        }
    }

    /**
     * Completes an approved play-request relay: the server's QUEUE_ADD
     * (insert_next) just landed with our track at the front of the queue —
     * jump the whole room to it.
     */
    private fun dispatchRelayedPlay(event: ListenTogetherEvent.PlaybackSync) {
        val relay = pendingRelayedPlay ?: return
        if (!isHostNow()) return
        val relayTrack = event.track?.takeIf { it.id == relay.trackId }
            ?: _state.value.queue.firstOrNull { it.id == relay.trackId }
            ?: return
        pendingRelayedPlay = null
        android.util.Log.d(TAG, "relay complete: switching room to ${relay.trackId}")
        playQueueItem(relayTrack)
    }

    fun rejectSuggestion(suggestion: TogetherSuggestion) {
        client.rejectSuggestion(suggestion.suggestionId, "declined")
        TogetherNotifications.cancel(
            Dependencies.application,
            TogetherNotifications.idForSuggestion(suggestion.suggestionId)
        )
        _state.update {
            it.copy(suggestions = it.suggestions.filterNot { s -> s.suggestionId == suggestion.suggestionId })
        }
    }

    /** Notification-action entry points (host may act from any screen). */
    fun approveSuggestionById(suggestionId: String) {
        _state.value.suggestions.firstOrNull { it.suggestionId == suggestionId }
            ?.let(::approveSuggestion)
            ?: TogetherNotifications.cancel(
                Dependencies.application,
                TogetherNotifications.idForSuggestion(suggestionId)
            )
    }

    fun rejectSuggestionById(suggestionId: String) {
        _state.value.suggestions.firstOrNull { it.suggestionId == suggestionId }
            ?.let(::rejectSuggestion)
            ?: TogetherNotifications.cancel(
                Dependencies.application,
                TogetherNotifications.idForSuggestion(suggestionId)
            )
    }

    internal fun normalizeVideoId(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.contains("watch?v=") -> trimmed.substringAfter("watch?v=").substringBefore('&').substringBefore('?')
            trimmed.contains("youtu.be/") -> trimmed.substringAfter("youtu.be/").substringBefore('&').substringBefore('?')
            trimmed.contains("shorts/") -> trimmed.substringAfter("shorts/").substringBefore('&').substringBefore('?')
            trimmed.length == 11 -> trimmed
            else -> ""
        }
    }

    /**
     * Host or guest: play this YouTube id in the room RIGHT NOW.
     *
     * Host switches the room directly ([playQueueItem] handles queued and
     * fresh tracks). Guest relays an ask-to-play; room-queue metadata is
     * reused verbatim when available so the payload is provably valid.
     */
    fun playNowInRoom(videoId: String) {
        val cleanId = videoId.substringBefore('?').trim()
        if (cleanId.isBlank()) return
        scope.launch {
            if (!ensureConnected() || !_state.value.inRoom) return@launch
            val roomTrack = _state.value.queue.firstOrNull { it.id == cleanId }
                ?: _state.value.track?.takeIf { it.id == cleanId }
            if (isHostNow()) {
                playQueueItem(roomTrack ?: resolveTrack(cleanId))
            } else {
                val target = roomTrack ?: resolveTrack(cleanId)
                if (target.id.isNotBlank()) requestPlay(target)
            }
        }
    }

    /**
     * Host or guest: play this YouTube id NEXT in the room.
     *
     * Host inserts it at the front of the room queue ([hostInsertNext]).
     * Guest sends a normal suggestion, which the server inserts next on
     * approval — the long-press replacement for link pasting.
     */
    fun playNextInRoom(videoId: String) {
        val cleanId = videoId.substringBefore('?').trim()
        if (cleanId.isBlank()) return
        scope.launch {
            if (!ensureConnected() || !_state.value.inRoom) return@launch
            if (isHostNow()) {
                val target = _state.value.queue.firstOrNull { it.id == cleanId }
                    ?: _state.value.track?.takeIf { it.id == cleanId }
                    ?: resolveTrack(cleanId)
                hostInsertNext(target)
            } else {
                suggestSong(cleanId)
            }
        }
    }

    /**
     * Host: insert a track at the front of the room queue without
     * disturbing playback. The local player queue is edited in place so the
     * next host snapshot broadcasts the new order through the usual
     * SYNC_QUEUE path — no bespoke protocol needed.
     */
    private fun hostInsertNext(track: TrackInfo) {
        if (!isHostNow() || track.id.isBlank()) return
        adapter?.insertNext(track)
        scope.launch { startHostSnapshot() }
    }

    /**
     * Host: play this suggested track in the room immediately, consuming
     * the card. Used by the suggestion card's Play-now button and the
     * already-queued grant path alike: reject with the "playing now"
     * reason (so the suggester's card clears as a grant, not a decline)
     * and switch the room straight to the track.
     */
    fun playSuggestionNow(suggestion: TogetherSuggestion) {
        val track = suggestion.track ?: return
        if (track.id.isBlank()) return
        scope.launch {
            if (!isHostNow()) return@launch
            pendingRelayedPlay = null
            android.util.Log.d(TAG, "direct grant: playing ${track.id} now")
            runCatching { client.rejectSuggestion(suggestion.suggestionId, REASON_PLAYING_NOW) }
            TogetherNotifications.cancel(
                Dependencies.application,
                TogetherNotifications.idForSuggestion(suggestion.suggestionId)
            )
            _state.update {
                it.copy(suggestions = it.suggestions.filterNot { s -> s.suggestionId == suggestion.suggestionId })
            }
            playQueueItem(track)
        }
    }

    /**
     * Host: resolve and immediately start playing a track by its YouTube video ID or link.
     */
    fun playTrackById(input: String) {
        val videoId = normalizeVideoId(input)
        if (videoId.isBlank()) return
        scope.launch {
            if (!isHostNow()) return@launch
            playQueueItem(resolveTrack(videoId))
        }
    }

    fun requestSyncNow() {
        scope.launch {
            if (!ensureConnected()) {
                _state.update {
                    it.copy(error = Dependencies.application.getString(R.string.together_connect_failed))
                }
                return@launch
            }
            client.requestSync()
        }
    }

    fun suggestTrack(track: TrackInfo) {
        scope.launch {
            if (ensureConnected()) client.suggestTrack(track)
        }
    }

    fun clearError() = _state.update { it.copy(error = null, kickedReason = null) }

    /**
     * Playhead-only flow: the 1s ticker writes here instead of the main
     * room state, so the moving position recomposes ONLY the track card
     * (seek bar + timestamp) — never the members/suggestions/queue
     * sections.
     */
    private val _playhead = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val playhead: kotlinx.coroutines.flow.StateFlow<Long> = _playhead

    private fun teardownRoom(stopPlayback: Boolean = false) {
        stopLobbyTimeout()
        drift?.stopGuestLoop()
        drift?.stopHostLoop()
        drift?.reset()
        if (stopPlayback) {
            // Kicked / room closed / session invalidated: nothing on this
            // device may keep playing room audio after the room is gone.
            isSyncing = true
            try {
                adapter?.pause()
            } finally {
                isSyncing = false
            }
        }
        adapter?.setPauseAtEndOfMediaItems(false)
        clearBufferState()
        loadGeneration.incrementAndGet()
        lastSyncedTrackId = null
        lastSyncedIsPlaying = false
        // Queue-observer state must not leak into a future room session.
        lastObservedQueueIds = null
        lastQueueBroadcastAt = 0L
        lastHostPositionMs = 0L
        pendingEndedTrackId = null
        pendingEndedPositionMs = null
        hostSnapshotJob?.cancel()
        hostIdleCheckJob?.cancel()
        clearPlayRequestTimeout()
        TogetherNotifications.cancelAll()
        resetRevision("left room")
        pendingRelayedPlay = null
        ListenTogetherPreferences.sessionToken = ""
        _state.update {
            it.copy(
                inRoom = false,
                roomCode = "",
                userId = "",
                isHost = false,
                members = emptyList(),
                track = null,
                isPlaying = false,
                queue = emptyList(),
                bufferingUsers = emptyList(),
                suggestions = emptyList(),
                isBuffering = false,
                pendingPlayRequest = null,
                pendingJoinRequests = emptyList(),
                isCreatingRoom = false,
                isJoiningRoom = false,
                isAwaitingJoinApproval = false,
                drift = DriftSnapshot(health = DriftHealth.INSYNC)
            )
        }
        stopUiTicker()
    }

    private fun upsertMember(member: RoomMember) {
        _state.update { snapshot ->
            snapshot.copy(
                members = snapshot.members.filterNot { it.userId == member.userId } + member
            )
        }
    }

    private fun removeMember(userId: String) {
        _state.update { snapshot ->
            snapshot.copy(members = snapshot.members.filterNot { it.userId == userId })
        }
    }
}
