package app.pulse.providers.listentogether

/**
 * Server events decoded from the wire, transport-agnostic. Payloads follow
 * metroproto; message type strings match metroserver's protocol constants.
 */
sealed interface ListenTogetherEvent {

    /** Socket attached and `server_capabilities` negotiated. */
    data class Handshake(val serverVersion: String) : ListenTogetherEvent

    data class RoomCreated(
        val roomCode: String,
        val userId: String,
        val sessionToken: String
    ) : ListenTogetherEvent

    /** Guests', wait for host approval — host sees this. */
    data class JoinRequest(val userId: String, val username: String) : ListenTogetherEvent

    data class JoinApproved(
        val roomCode: String,
        val userId: String,
        val sessionToken: String,
        val state: RoomState
    ) : ListenTogetherEvent

    data class JoinRejected(val reason: String) : ListenTogetherEvent

    data class UserJoined(val userId: String, val username: String) : ListenTogetherEvent

    data class UserLeft(val userId: String, val username: String) : ListenTogetherEvent

    /** Host commands, relayed by the server to every room member (incl. host). */
    data class PlaybackSync(
        val action: String,
        val trackId: String,
        val positionMs: Long,
        val track: TrackInfo?,
        val insertNext: Boolean,
        val queue: List<TrackInfo>,
        val queueTitle: String,
        val volume: Float,
        val serverTimeMs: Long,
        val revision: Long,
        val capturedAtServerTimeMs: Long,
        /** change_track: [queue] is a full replacement, even when empty. */
        val replaceQueue: Boolean = false,
        /** Server -> client: user whose request produced this broadcast. */
        val originUserId: String = ""
    ) : ListenTogetherEvent

    /** Users still buffering before playback resumes. */
    data class BufferWait(val trackId: String, val waitingFor: List<String>) : ListenTogetherEvent

    data class BufferComplete(val trackId: String) : ListenTogetherEvent

    data class Error(val code: String, val message: String) : ListenTogetherEvent

    data class HostChanged(val newHostId: String, val newHostName: String) : ListenTogetherEvent

    data class Kicked(val reason: String) : ListenTogetherEvent

    /** Fully synchronized room snapshot, sent on `request_sync`. */
    data class SyncState(val state: RoomState) : ListenTogetherEvent

    data class Reconnected(
        val roomCode: String,
        val userId: String,
        val state: RoomState,
        val isHost: Boolean
    ) : ListenTogetherEvent

    data class UserReconnected(val userId: String, val username: String) : ListenTogetherEvent

    data class UserDisconnected(val userId: String, val username: String) : ListenTogetherEvent

    data class SuggestionReceived(
        val suggestionId: String,
        val fromUserId: String,
        val fromUsername: String,
        val track: TrackInfo?
    ) : ListenTogetherEvent

    data class SuggestionApproved(val suggestionId: String, val track: TrackInfo?) : ListenTogetherEvent

    data class SuggestionRejected(val suggestionId: String, val reason: String) : ListenTogetherEvent

    /** Terminal state. */
    data class FatalDisconnected(val reason: String) : ListenTogetherEvent
}

enum class ConnectionState {
    Disconnected,
    Connecting,
    Handshaking,
    Connected,
    Fatal
}
