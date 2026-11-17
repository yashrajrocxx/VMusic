package app.pulse.providers.listentogether

/**
 * Abstract player wrapper — the sync engine knows nothing about ExoPlayer,
 * Media3, or any concrete framework. Every command required by the protocol
 * is expressed here and wired to the host app's player elsewhere.
 *
 * Positions are milliseconds; the app layer converts to/from any units.
 */
interface IMetrolistSyncPlayer {

    fun play()

    fun pause()

    /** Absolute jump to [positionMs] within the current track. */
    fun seekTo(positionMs: Long)

    /**
     * Tempo warp used by the micro-adjust engine. `1.03f` while lagging the
     * host, `0.97f` while leading; implementations should apply it without
     * rebuffering and must preserve pitch where the platform allows.
     */
    fun setSpeed(rate: Float)

    /** Current playhead position in milliseconds. */
    fun positionMs(): Long

    /** True while audio is actively playing. */
    fun isPlaying(): Boolean

    /** Media id of the loaded track, or null when idle. */
    fun currentTrackId(): String?

    /**
     * Ask the player to load and start preparing [track] (id, title, artist,
     * thumbnail, duration). Implementations may resolve streams eagerly — the
     * sync flow will `buffer_ready` or report failure via [isReady].
     *
     * Returns false when the player cannot host this track (empty catalog etc).
     */
    fun loadTrack(track: TrackInfo): Boolean = false

    /** Whether the last [loadTrack] target has primed its audio buffer. */
    fun isReady(): Boolean = true
}

data class TrackInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    /** Track duration in milliseconds. */
    val duration: Long = 0L,
    val thumbnail: String = "",
    val suggestedBy: String = ""
)

data class RoomMember(
    val userId: String,
    val username: String,
    val isHost: Boolean = false,
    val isConnected: Boolean = true
)

data class RoomState(
    val roomCode: String,
    val hostId: String? = null,
    val members: List<RoomMember> = emptyList(),
    val track: TrackInfo? = null,
    val isPlaying: Boolean = false,
    /** Position at [lastUpdateMs]. */
    val positionMs: Long = 0L,
    /** Server clock (ms) the snapshot was taken at. */
    val lastUpdateMs: Long = 0L,
    val volume: Float = 1f,
    val queue: List<TrackInfo> = emptyList(),
    val revision: Long = 0L
)
