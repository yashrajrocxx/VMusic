package app.pulse.providers.listentogether.proto

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/*
 * Wire codec for the "Listen Together" protocol.
 *
 * Message envelope and payload field numbers/types mirror — one to one — the
 * canonical schema at github.com/MetrolistGroup/metroproto
 * (`listentogether.proto`). Only the subset of messages a client produces or
 * consumes is implemented; unknown fields received from peers are skipped
 * safely, exactly like a generated protobuf reader would.
 *
 * Framing: every WebSocket *binary* frame is a `listentogether.Envelope`
 * carrying the message `type` (string) plus the payload serialized with the
 * proto3 wire format. JSON transport is not part of the protocol and is
 * rejected explicitly. When compression is negotiated (`client_capabilities`
 * with `supports_compression`), payloads larger than 100 bytes may arrive and
 * leave GZIP compressed with the flag set in the envelope — mirroring
 * metroserver's codec (see internal/server/codec.go in metroserver).
 */

internal const val WIRE_TYPE_VARINT = 0
internal const val WIRE_TYPE_FIXED64 = 1
internal const val WIRE_TYPE_LEN = 2
internal const val WIRE_TYPE_FIXED32 = 5

/** Compression trigger byte size — must stay in sync with metroserver. */
internal const val COMPRESSION_MIN_SIZE = 100

// ---------------------------------------------------------------------------
// Envelope
// ---------------------------------------------------------------------------

/**
 * listentogether.Envelope:
 *   1: string type
 *   2: bytes  payload
 *   3: bool   compressed
 */
data class PbEnvelope(
    val type: String,
    val payload: ByteArray,
    val compressed: Boolean
) {
    override fun equals(other: Any?): Boolean = other is PbEnvelope &&
        other.type == type && other.compressed == compressed && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

// ---------------------------------------------------------------------------
// Protobuf wire primitives
// ---------------------------------------------------------------------------

internal object WireFormat {

    fun writeVarint(buffer: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                buffer.write(v.toInt())
                return
            }
            buffer.write((v and 0x7F or 0x80).toInt())
            v = v ushr 7
        }
    }

    fun writeTag(buffer: ByteArrayOutputStream, field: Int, wireType: Int) {
        writeVarint(buffer, ((field shl 3) or wireType).toLong())
    }

    fun writeVarintField(buffer: ByteArrayOutputStream, field: Int, value: Long, defaultValue: Long = 0L) {
        if (value == defaultValue) return
        writeTag(buffer, field, WIRE_TYPE_VARINT)
        writeVarint(buffer, value)
    }

    fun writeBoolField(buffer: ByteArrayOutputStream, field: Int, value: Boolean) {
        if (!value) return
        writeTag(buffer, field, WIRE_TYPE_VARINT)
        writeVarint(buffer, 1L)
    }

    fun writeFloatField(buffer: ByteArrayOutputStream, field: Int, value: Float, defaultValue: Float = 0f) {
        if (value == defaultValue) return
        writeTag(buffer, field, WIRE_TYPE_FIXED32)
        val bits = value.toRawBits()
        buffer.write(bits and 0xFF)
        buffer.write((bits ushr 8) and 0xFF)
        buffer.write((bits ushr 16) and 0xFF)
        buffer.write((bits ushr 24) and 0xFF)
    }

    fun writeStringField(buffer: ByteArrayOutputStream, field: Int, value: String?) {
        if (value.isNullOrEmpty()) return
        writeBytesField(buffer, field, value.encodeToByteArray())
    }

    fun writeBytesField(buffer: ByteArrayOutputStream, field: Int, value: ByteArray) {
        if (value.isEmpty()) return
        writeTag(buffer, field, WIRE_TYPE_LEN)
        writeVarint(buffer, value.size.toLong())
        buffer.write(value)
    }
}

internal sealed interface TaggedField {
    val field: Int

    data class Varint(override val field: Int, val value: Long) : TaggedField
    data class Fixed32(override val field: Int, val value: Float) : TaggedField
    data class Fixed64(override val field: Int, val value: Long) : TaggedField
    data class Length(override val field: Int, val value: ByteArray) : TaggedField {
        override fun equals(other: Any?): Boolean = other is Length &&
            other.field == field && other.value.contentEquals(value)

        override fun hashCode(): Int = 31 * field + value.contentHashCode()
    }
}

/**
 * Parses one protobuf message into its flat list of tagged fields, skipping
 * nothing — the decoder functions below simply ignore what they don't know.
 */
internal fun decodeFields(data: ByteArray): List<TaggedField> {
    val fields = ArrayList<TaggedField>(16)
    var pos = 0
    while (pos < data.size) {
        val key = readVarint(data, pos).also { pos += it.second }.first.toInt()
        val field = key ushr 3
        val wireType = key and 0x7
        require(field != 0) { "invalid protobuf tag $key" }
        when (wireType) {
            WIRE_TYPE_VARINT -> {
                val value = readVarint(data, pos).also { pos += it.second }.first
                fields.add(TaggedField.Varint(field, value))
            }

            WIRE_TYPE_FIXED32 -> {
                require(pos + 4 <= data.size) { "truncated fixed32" }
                val bits = (data[pos].toInt() and 0xFF) or
                    ((data[pos + 1].toInt() and 0xFF) shl 8) or
                    ((data[pos + 2].toInt() and 0xFF) shl 16) or
                    ((data[pos + 3].toInt() and 0xFF) shl 24)
                pos += 4
                fields.add(TaggedField.Fixed32(field, Float.fromBits(bits)))
            }

            WIRE_TYPE_FIXED64 -> {
                require(pos + 8 <= data.size) { "truncated fixed64" }
                var value = 0L
                repeat(8) {
                    value = value or ((data[pos++].toLong() and 0xFF) shl (8 * it))
                }
                fields.add(TaggedField.Fixed64(field, value))
            }

            WIRE_TYPE_LEN -> {
                val len = readVarint(data, pos).also { pos += it.second }.first.toInt()
                require(len >= 0 && pos + len <= data.size) { "truncated length-delimited field" }
                fields.add(TaggedField.Length(field, data.copyOfRange(pos, pos + len)))
                pos += len
            }

            else -> throw IllegalArgumentException("unsupported wire type $wireType")
        }
    }
    return fields
}

private fun readVarint(data: ByteArray, from: Int): Pair<Long, Int> {
    var value = 0L
    var shift = 0
    var pos = from
    while (pos < data.size) {
        val b = data[pos++].toInt()
        value = value or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return value to (pos - from)
        shift += 7
        require(shift <= 63) { "protobuf varint overflow" }
    }
    error("truncated protobuf varint")
}

private inline fun List<TaggedField>.varint(field: Int, default: Long = 0L): Long =
    asSequence().filterIsInstance<TaggedField.Varint>().firstOrNull { it.field == field }?.value ?: default

private inline fun List<TaggedField>.boolean(field: Int): Boolean = varint(field) != 0L

private inline fun List<TaggedField>.fixed32(field: Int, default: Float = 0f): Float =
    asSequence().filterIsInstance<TaggedField.Fixed32>().firstOrNull { it.field == field }?.value ?: default

private inline fun List<TaggedField>.string(field: Int): String =
    asSequence().filterIsInstance<TaggedField.Length>().firstOrNull { it.field == field }
        ?.value?.decodeToString().orEmpty()

private inline fun List<TaggedField>.bytes(field: Int): ByteArray =
    asSequence().filterIsInstance<TaggedField.Length>().firstOrNull { it.field == field }?.value
        ?: ByteArray(0)

private inline fun List<TaggedField>.strings(field: Int): List<String> =
    filterIsInstance<TaggedField.Length>().filter { it.field == field }.map { it.value.decodeToString() }

private inline fun List<TaggedField>.messages(field: Int): List<ByteArray> =
    filterIsInstance<TaggedField.Length>().filter { it.field == field }.map { it.value }

// ---------------------------------------------------------------------------
// Payload messages (schemas from listentogether.proto)
// ---------------------------------------------------------------------------

/**
 * listentogether.TrackInfo:
 *   1: string id, 2: string title, 3: string artist, 4: string album,
 *   5: int64 duration (ms), 6: string thumbnail, 7: string suggested_by
 */
data class PbTrackInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: Long = 0L,
    val thumbnail: String = "",
    val suggestedBy: String = ""
)

fun encodeTrackInfo(track: PbTrackInfo): ByteArray {
    val out = ByteArrayOutputStream(24)
    WireFormat.writeStringField(out, 1, track.id)
    WireFormat.writeStringField(out, 2, track.title)
    WireFormat.writeStringField(out, 3, track.artist)
    WireFormat.writeStringField(out, 4, track.album)
    WireFormat.writeVarintField(out, 5, track.duration)
    WireFormat.writeStringField(out, 6, track.thumbnail)
    WireFormat.writeStringField(out, 7, track.suggestedBy)
    return out.toByteArray()
}

/**
 * listentogether.SuggestTrackPayload:
 *   1: TrackInfo track_info
 *
 * The track travels NESTED at field 1 — never bare. A bare TrackInfo parses
 * as garbage here (its field-1 id string is read as an embedded message,
 * whose first content byte is almost never a legal tag), and metroserver
 * answers "invalid suggest track payload" while the host never receives the
 * suggestion. This mirrors the receive side, where every payload carrying a
 * track nests it (SuggestionReceived#4, SuggestionApproved#2).
 */
fun encodeSuggestTrack(track: PbTrackInfo): ByteArray {
    val out = ByteArrayOutputStream(32)
    WireFormat.writeBytesField(out, 1, encodeTrackInfo(track))
    return out.toByteArray()
}

fun decodeTrackInfo(data: ByteArray): PbTrackInfo? {
    if (data.isEmpty()) return null
    val fields = decodeFields(data)
    return PbTrackInfo(
        id = fields.string(1),
        title = fields.string(2),
        artist = fields.string(3),
        album = fields.string(4),
        duration = fields.varint(5),
        thumbnail = fields.string(6),
        suggestedBy = fields.string(7)
    )
}

/**
 * listentogether.UserInfo:
 *   1: string user_id, 2: string username, 3: bool is_host, 4: bool is_connected
 */
data class PbUserInfo(
    val userId: String,
    val username: String = "",
    val isHost: Boolean = false,
    val isConnected: Boolean = false
)

fun decodeUserInfo(data: ByteArray): PbUserInfo? {
    if (data.isEmpty()) return null
    val fields = decodeFields(data)
    return PbUserInfo(
        userId = fields.string(1),
        username = fields.string(2),
        isHost = fields.boolean(3),
        isConnected = fields.boolean(4)
    )
}

/**
 * listentogether.RoomState:
 *   1: room_code, 2: host_id, 3: repeated users, 4: current_track,
 *   5: bool is_playing, 6: int64 position, 7: int64 last_update,
 *   8: float volume, 9: repeated queue, 10: uint64 revision
 */
data class PbRoomState(
    val roomCode: String = "",
    val hostId: String = "",
    val users: List<PbUserInfo> = emptyList(),
    val currentTrack: PbTrackInfo? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val lastUpdate: Long = 0L,
    val volume: Float = 0f,
    val queue: List<PbTrackInfo> = emptyList(),
    val revision: Long = 0L
)

fun decodeRoomState(data: ByteArray): PbRoomState {
    val fields = decodeFields(data)
    return PbRoomState(
        roomCode = fields.string(1),
        hostId = fields.string(2),
        users = fields.messages(3).mapNotNull(::decodeUserInfo),
        currentTrack = fields.messages(4).firstOrNull()?.let(::decodeTrackInfo),
        isPlaying = fields.boolean(5),
        position = fields.varint(6),
        lastUpdate = fields.varint(7),
        volume = fields.fixed32(8),
        queue = fields.messages(9).mapNotNull(::decodeTrackInfo),
        revision = fields.varint(10)
    )
}

/**
 * listentogether.PlaybackActionPayload:
 *   1: string action, 2: string track_id, 3: int64 position (ms),
 *   4: TrackInfo track_info, 5: bool insert_next, 6: repeated TrackInfo queue,
 *   7: string queue_title, 8: float volume, 9: int64 server_time,
 *   10: uint64 revision, 11: int64 captured_at_server_time
 */
data class PbPlaybackAction(
    val action: String = "",
    val trackId: String = "",
    val position: Long = 0L,
    val trackInfo: PbTrackInfo? = null,
    val insertNext: Boolean = false,
    val queue: List<PbTrackInfo> = emptyList(),
    val queueTitle: String = "",
    val volume: Float = 0f,
    val serverTime: Long = 0L,
    val revision: Long = 0L,
    val capturedAtServerTime: Long = 0L,
    /** change_track: replace the room queue even when [queue] is empty. */
    val replaceQueue: Boolean = false,
    /** Server -> client: user whose action produced this broadcast. */
    val originUserId: String = "",
    /** Client -> server: monotonic host observation sequence. */
    val hostSequence: Long = 0L
)

fun encodePlaybackAction(action: PbPlaybackAction): ByteArray {
    val out = ByteArrayOutputStream(48)
    WireFormat.writeStringField(out, 1, action.action)
    WireFormat.writeStringField(out, 2, action.trackId)
    WireFormat.writeVarintField(out, 3, action.position)
    action.trackInfo?.let { WireFormat.writeBytesField(out, 4, encodeTrackInfo(it)) }
    WireFormat.writeBoolField(out, 5, action.insertNext)
    action.queue.forEach { WireFormat.writeBytesField(out, 6, encodeTrackInfo(it)) }
    WireFormat.writeStringField(out, 7, action.queueTitle)
    WireFormat.writeFloatField(out, 8, action.volume)
    WireFormat.writeVarintField(out, 9, action.serverTime)
    WireFormat.writeVarintField(out, 10, action.revision)
    WireFormat.writeVarintField(out, 11, action.capturedAtServerTime)
    WireFormat.writeBoolField(out, 12, action.replaceQueue)
    WireFormat.writeStringField(out, 13, action.originUserId)
    WireFormat.writeVarintField(out, 14, action.hostSequence)
    return out.toByteArray()
}

fun decodePlaybackAction(data: ByteArray): PbPlaybackAction {
    val fields = decodeFields(data)
    return PbPlaybackAction(
        action = fields.string(1),
        trackId = fields.string(2),
        position = fields.varint(3),
        trackInfo = fields.messages(4).firstOrNull()?.let(::decodeTrackInfo),
        insertNext = fields.boolean(5),
        queue = fields.messages(6).mapNotNull(::decodeTrackInfo),
        queueTitle = fields.string(7),
        volume = fields.fixed32(8),
        serverTime = fields.varint(9),
        revision = fields.varint(10),
        capturedAtServerTime = fields.varint(11),
        replaceQueue = fields.boolean(12),
        originUserId = fields.string(13),
        hostSequence = fields.varint(14)
    )
}

/**
 * listentogether.SyncStatePayload:
 *   1: TrackInfo current_track, 2: bool is_playing, 3: int64 position,
 *   4: int64 last_update, 5: repeated TrackInfo queue, 6: float volume,
 *   7: uint64 revision
 */
data class PbSyncState(
    val currentTrack: PbTrackInfo? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val lastUpdate: Long = 0L,
    val queue: List<PbTrackInfo> = emptyList(),
    val volume: Float = 0f,
    val revision: Long = 0L
)

/**
 * listentogether.PingPayload (1: client_time, 2: uint64 sequence). The pong
 * reply is intentionally not decoded: no consumer needs the RTT sample.
 */
data class PbPing(val clientTime: Long = 0L, val sequence: Long = 0L)

fun encodePing(ping: PbPing): ByteArray {
    val out = ByteArrayOutputStream(8)
    WireFormat.writeVarintField(out, 1, ping.clientTime)
    WireFormat.writeVarintField(out, 2, ping.sequence)
    return out.toByteArray()
}

/**
 * listentogether.SyncStatePayload — NOTE: field numbers diverge from RoomState
 * (track=1, is_playing=2, position=3, last_update=4, queue=5, volume=6,
 * revision=7), so it needs its own decoder.
 */
fun decodeSyncState(data: ByteArray): PbSyncState {
    val fields = decodeFields(data)
    return PbSyncState(
        currentTrack = fields.messages(1).firstOrNull()?.let(::decodeTrackInfo),
        isPlaying = fields.boolean(2),
        position = fields.varint(3),
        lastUpdate = fields.varint(4),
        queue = fields.messages(5).mapNotNull(::decodeTrackInfo),
        volume = fields.fixed32(6),
        revision = fields.varint(7)
    )
}

/**
 * listentogether.ClientCapabilities (1/2 bool, 3: string client_version),
 * ServerCapabilities (1/2 bool, 3: string server_version)
 */
data class PbClientCapabilities(
    val supportsProtobuf: Boolean = false,
    val supportsCompression: Boolean = false,
    val clientVersion: String = ""
)

fun encodeClientCapabilities(capabilities: PbClientCapabilities): ByteArray {
    val out = ByteArrayOutputStream(8)
    WireFormat.writeBoolField(out, 1, capabilities.supportsProtobuf)
    WireFormat.writeBoolField(out, 2, capabilities.supportsCompression)
    WireFormat.writeStringField(out, 3, capabilities.clientVersion)
    return out.toByteArray()
}

fun decodeServerCapabilities(data: ByteArray): PbClientCapabilities {
    val fields = decodeFields(data)
    return PbClientCapabilities(
        supportsProtobuf = fields.boolean(1),
        supportsCompression = fields.boolean(2),
        clientVersion = fields.string(3)
    )
}

// Simple string-only payloads shared by several message types ---------------

private fun simpleStrings(vararg pairs: Pair<Int, String>): ByteArray {
    val out = ByteArrayOutputStream(16)
    pairs.forEach { (field, value) -> WireFormat.writeStringField(out, field, value) }
    return out.toByteArray()
}

data class PbRoomCreated(val roomCode: String, val userId: String, val sessionToken: String)
data class PbJoinRequest(val userId: String, val username: String)
data class PbBufferWait(val trackId: String, val waitingFor: List<String>)
data class PbHostChanged(val newHostId: String, val newHostName: String)
data class PbJoinApproved(
    val roomCode: String,
    val userId: String,
    val sessionToken: String,
    val state: PbRoomState?
)

data class PbReconnected(val roomCode: String, val userId: String, val state: PbRoomState?, val isHost: Boolean)

fun encodeJoinRoom(roomCode: String, username: String): ByteArray =
    simpleStrings(1 to roomCode, 2 to username)

fun encodeFirstString(value: String): ByteArray = simpleStrings(1 to value)

fun encodeTwoStrings(first: String, second: String): ByteArray =
    simpleStrings(1 to first, 2 to second)

fun createRoom(username: String): ByteArray = simpleStrings(1 to username)

fun decodeRoomCreated(data: ByteArray): PbRoomCreated {
    val fields = decodeFields(data)
    return PbRoomCreated(fields.string(1), fields.string(2), fields.string(3))
}

fun decodeJoinRequest(data: ByteArray): PbJoinRequest {
    val fields = decodeFields(data)
    return PbJoinRequest(fields.string(1), fields.string(2))
}

fun decodeTwoStrings(data: ByteArray): Pair<String, String> {
    val fields = decodeFields(data)
    return fields.string(1) to fields.string(2)
}

fun decodeFirstString(data: ByteArray): String = decodeFields(data).string(1)

fun decodeHostChanged(data: ByteArray): PbHostChanged {
    val (newId, newName) = decodeTwoStrings(data)
    return PbHostChanged(newId, newName)
}

fun decodeBufferWait(data: ByteArray): PbBufferWait {
    val fields = decodeFields(data)
    return PbBufferWait(
        trackId = fields.string(1),
        waitingFor = fields.strings(2)
    )
}

/**
 * listentogether.SuggestionReceivedPayload (host only):
 *   1: string suggestion_id, 2: string from_user_id, 3: string from_username,
 *   4: TrackInfo track_info
 */
data class PbSuggestionReceived(
    val suggestionId: String,
    val fromUserId: String = "",
    val fromUsername: String = "",
    val trackInfo: PbTrackInfo? = null
)

fun decodeSuggestionReceived(data: ByteArray): PbSuggestionReceived {
    val fields = decodeFields(data)
    return PbSuggestionReceived(
        suggestionId = fields.string(1),
        fromUserId = fields.string(2),
        fromUsername = fields.string(3),
        trackInfo = fields.messages(4).firstOrNull()?.let(::decodeTrackInfo)
    )
}

/**
 * listentogether.SuggestionApprovedPayload (suggester only):
 *   1: string suggestion_id, 2: TrackInfo track_info
 */
data class PbSuggestionApproved(
    val suggestionId: String,
    val trackInfo: PbTrackInfo? = null
)

fun decodeSuggestionApproved(data: ByteArray): PbSuggestionApproved {
    val fields = decodeFields(data)
    return PbSuggestionApproved(
        suggestionId = fields.string(1),
        trackInfo = fields.messages(2).firstOrNull()?.let(::decodeTrackInfo)
    )
}

fun decodeJoinApproved(data: ByteArray): PbJoinApproved {
    val fields = decodeFields(data)
    return PbJoinApproved(
        roomCode = fields.string(1),
        userId = fields.string(2),
        sessionToken = fields.string(3),
        state = fields.messages(4).firstOrNull()?.let(::decodeRoomState)
    )
}

fun decodeReconnected(data: ByteArray): PbReconnected {
    val fields = decodeFields(data)
    return PbReconnected(
        roomCode = fields.string(1),
        userId = fields.string(2),
        state = fields.messages(3).firstOrNull()?.let(::decodeRoomState),
        isHost = fields.boolean(4)
    )
}

fun syncStateToRoomState(state: PbSyncState): PbRoomState = PbRoomState(
    roomCode = "",
    hostId = "",
    currentTrack = state.currentTrack,
    isPlaying = state.isPlaying,
    position = state.position,
    lastUpdate = state.lastUpdate,
    volume = state.volume,
    queue = state.queue,
    revision = state.revision
)

// codec ---------------------------------------------------------------------

/** GZIP for the payload section only, matching metroserver expectations. */
internal fun gzipCompress(data: ByteArray): ByteArray {
    val baos = ByteArrayOutputStream(data.size / 2 + 16)
    GZIPOutputStream(baos).use { it.write(data) }
    return baos.toByteArray()
}

internal fun gzipDecompress(data: ByteArray, limit: Int = 1 shl 20): ByteArray {
    GZIPInputStream(data.inputStream()).use { stream ->
        val output = ByteArrayOutputStream(minOf(limit, data.size * 8 + 32))
        val chunk = ByteArray(4096)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            require(total <= limit) { "decompressed payload too large" }
            output.write(chunk, 0, read)
        }
        return output.toByteArray()
    }
}

/**
 * Client-side codec: envelopes + negotiated GZIP compression, mirroring the
 * metroserver MessageCodec semantics ([COMPRESSION_MIN_SIZE] honoured).
 */
class MetroProtoCodec {
    @Volatile
    var compressionEnabled: Boolean = false
        private set

    fun setCompressionEnabled(enabled: Boolean) {
        compressionEnabled = enabled
    }

    fun encode(type: String, payload: ByteArray? = null): ByteArray {
        var body = payload ?: ByteArray(0)
        var compressed = false
        if (compressionEnabled && body.size > COMPRESSION_MIN_SIZE) {
            val zipped = runCatching { gzipCompress(body) }.getOrNull()
            if (zipped != null && zipped.size < body.size) {
                body = zipped
                compressed = true
            }
        }
        val out = ByteArrayOutputStream(body.size + 16)
        WireFormat.writeStringField(out, 1, type)
        WireFormat.writeBytesField(out, 2, body) // empty body writes nothing — correct
        if (compressed) WireFormat.writeBoolField(out, 3, true)
        return out.toByteArray()
    }

    fun decode(data: ByteArray): PbEnvelope {
        val envelope = parseEnvelopeRaw(data)
        val compressedFlag = envelope.compressed
        // Belt-and-braces: some servers may compress without raising the flag;
        // the GZIP magic number is unambiguous (0x1f 0x8b).
        val magicCompressed = envelope.payload.size > 2 &&
            envelope.payload[0] == 0x1F.toByte() &&
            envelope.payload[1] == 0x8B.toByte()
        val payload = if (compressedFlag || magicCompressed) gzipDecompress(envelope.payload) else envelope.payload
        return PbEnvelope(envelope.type, payload, compressedFlag)
    }

    private fun parseEnvelopeRaw(data: ByteArray): PbEnvelope {
        require(data.isNotEmpty()) { "empty frame received" }
        val fields = decodeFields(data)
        val type = fields.string(1)
        require(type.isNotEmpty()) { "envelope without type" }
        val payload = fields.bytes(2)
        val compressed = fields.boolean(3)
        return PbEnvelope(type, payload, compressed)
    }
}
