package app.pulse.android.listentogether

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.pulse.providers.listentogether.IMetrolistSyncPlayer
import app.pulse.providers.listentogether.TrackInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One ticker read of the audible player: a single main-thread round-trip for
 * everything the one-second UI tick needs.
 */
data class PlaybackSnapshot(
    val trackId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val unsupported: Boolean = false
)

/**
 * Android binding of [IMetrolistSyncPlayer] over the app's Media3 [Player]
 * (via PlayerService's binder).
 *
 * ExoPlayer demands single-thread (application thread) access. The sync
 * engine, the coordinator and socket coroutines all run off-main, so every
 * command is posted to the main looper and every read round-trips through it
 * with a bounded handoff. Host callers on the main thread take the fast path.
 *
 * Tracks are loaded by mediaId only — PlayerService resolves stream URLs for
 * that id through InnerTubeXPlayer when the media source prepares, so a guest
 * never needs (and never receives) a stream URI from the host.
 */
class ExoPlayerSyncPlayer(private val playerProvider: () -> Player) : IMetrolistSyncPlayer {

    /**
     * RESOLVED PER CALL: PlayerService alternates between two physical
     * players at each crossfade boundary (`audible`), so a captured instance
     * would target a paused, silent decoder forever. Reads through the
     * provider on the main thread (the same thread the service swaps it on).
     */
    private val player: Player get() = playerProvider()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The physical instance our listener is currently attached to. */
    private var attachedPlayer: Player? = null

    /**
     * Registers [listener] on the CURRENT audible player. The service swaps
     * the audible instance at each crossfade; call again after a swap.
     * Returns true when the listener was (re)attached to a new instance.
     */
    fun attachPlayerListener(listener: Player.Listener): Boolean = query(false) {
        val target = player
        if (target === attachedPlayer) {
            false
        } else {
            attachedPlayer?.removeListener(listener)
            target.addListener(listener)
            attachedPlayer = target
            true
        }
    }

    /** Fire-and-forget command execution on the player thread. */
    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { runCatching(block) }
        }
    }

    /** Bounded synchronous read from the player thread. */
    @Volatile
    var lastQueryTimedOut = false
        private set

    private fun <T> query(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lastQueryTimedOut = false
            return runCatching(block).getOrDefault(fallback)
        }
        val latch = CountDownLatch(1)
        var result: T? = null
        mainHandler.post {
            result = try {
                block()
            } catch (_: Throwable) {
                null
            }
            latch.countDown()
        }
        // A busy main thread must never masquerade as an idle player (the
        // host would pause the whole room over local jank): callers check
        // [lastQueryTimedOut] before declaring anything idle.
        lastQueryTimedOut = !latch.await(2, TimeUnit.SECONDS)
        return result ?: fallback
    }

    override fun play() = post { player.playWhenReady = true }

    override fun pause() = post { player.playWhenReady = false }

    override fun seekTo(positionMs: Long) = post { player.seekTo(positionMs) }

    override fun setSpeed(rate: Float) = post {
        player.playbackParameters = player.playbackParameters.withSpeed(rate)
    }

    override fun positionMs(): Long = query(0L) {
        player.currentPosition
    }

    override fun isPlaying(): Boolean = query(false) { player.isPlaying }

    override fun isReady(): Boolean = query(false) { player.playbackState == Player.STATE_READY }

    /** Batched read used by the one-second UI ticker. */
    fun playbackSnapshot(): PlaybackSnapshot = query(PlaybackSnapshot()) {
        val mediaId = player.currentMediaItem?.mediaId
        PlaybackSnapshot(
            trackId = trackIdOf(mediaId),
            positionMs = player.currentPosition,
            durationMs = if (player.duration == androidx.media3.common.C.TIME_UNSET) 0L else player.duration,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            unsupported = isUnsupportedMediaId(mediaId)
        )
    }

    fun setPauseAtEndOfMediaItems(pause: Boolean) = post {
        (player as? androidx.media3.exoplayer.ExoPlayer)?.pauseAtEndOfMediaItems = pause
    }

    /** True if the local player is currently loaded with an unsharable item (local audio or radio). */
    fun currentMediaItemIsUnsupported(): Boolean = query(false) {
        isUnsupportedMediaId(player.currentMediaItem?.mediaId)
    }

    private fun isUnsupportedMediaId(mediaId: String?): Boolean =
        mediaId != null && (mediaId.startsWith("radio:") || mediaId.startsWith("local:"))

    override fun currentTrackId(): String? = query<String?>(null) {
        // Media ids carry the canonical video id; sync messages match directly.
        trackIdOf(player.currentMediaItem?.mediaId)
    }

    /**
     * Ask the player to resolve and prepare [track]. When [autoPlay] is false
     * the item is primed *paused* — the room's buffer protocol decides when
     * audio actually starts.
     */
    fun loadTrack(track: TrackInfo, autoPlay: Boolean): Boolean = run {
        val cleanId = trackIdOf(track.id) ?: track.id
        val item = mediaItemFor(track.copy(id = cleanId))

        post {
            if (trackIdOf(player.currentMediaItem?.mediaId) == cleanId) {
                player.seekTo(0)
                player.playWhenReady = autoPlay
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
            } else {
                player.setMediaItem(item, /* resetPosition = */ true)
                player.prepare()
                player.playWhenReady = autoPlay
            }
        }
        true
    }

    override fun loadTrack(track: TrackInfo): Boolean = loadTrack(track, autoPlay = true)

    /**
     * Load [track] together with the room's remaining [upcoming] queue so the
     * local Media3 playlist mirrors the room (needed for "play now" jumps and
     * guest queue views without losing the rest of the queue).
     */
    fun loadTrackWithQueue(track: TrackInfo, upcoming: List<TrackInfo>, autoPlay: Boolean) {
        val items = canonicalQueue(track, upcoming).map(::mediaItemFor)
        post {
            runCatching {
                player.setMediaItems(items, 0, 0L)
                player.prepare()
                player.playWhenReady = autoPlay
            }
        }
    }

    /**
     * Rebuild the local upcoming region from a canonical room snapshot while
     * keeping the currently-playing window intact (Metrolist
     * applyCanonicalUpcomingQueue analog). Guest-only.
     */
    fun replaceUpcoming(tracks: List<TrackInfo>) {
        post {
            runCatching {
                val index = player.currentMediaItemIndex
                if (player.mediaItemCount > index + 1) {
                    player.removeMediaItems(index + 1, player.mediaItemCount)
                }
                if (currentTrackId() == null) {
                    // Empty player: seed the first item directly.
                    tracks.firstOrNull()?.let { head ->
                        player.setMediaItem(mediaItemFor(head))
                        player.prepare()
                    }
                    if (tracks.size > 1) {
                        player.addMediaItems(1, tracks.drop(1).map(::mediaItemFor))
                    }
                    return@runCatching
                }
                val upcoming = tracks.filter {
                    it.id.isNotBlank() && it.id != currentTrackId()
                }
                if (upcoming.isNotEmpty()) {
                    player.addMediaItems(index + 1, upcoming.map(::mediaItemFor))
                }
            }
        }
    }

    private fun mediaItemFor(track: TrackInfo): MediaItem {
        val cleanId = trackIdOf(track.id) ?: track.id
        val watchUri = "https://youtube.com/watch?v=$cleanId"
        val extras = android.os.Bundle().apply {
            if (track.duration > 0L) {
                putLong("durationMs", track.duration)
                val totalSecs = track.duration / 1000
                val secs = totalSecs % 60
                val mins = totalSecs / 60
                putString("durationText", "$mins:${if (secs < 10) "0$secs" else "$secs"}")
            }
        }
        return MediaItem.Builder()
            .setMediaId(cleanId)
            .setUri(watchUri)
            .setCustomCacheKey(cleanId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    // Never leak the internal play-request tag into player
                    // metadata (notification lockscreen etc.).
                    .setAlbumTitle(track.album.takeUnless {
                        it.startsWith(ListenTogetherCoordinator.PLAY_REQUEST_MARKER)
                    }.orEmpty())
                    .setArtworkUri(track.thumbnail.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /**
     * Insert [track] right after the current window without disturbing
     * playback. Used for host "play next in room".
     */
    fun insertNext(track: TrackInfo) {
        val cleanId = trackIdOf(track.id) ?: track.id
        if (cleanId.isBlank()) return
        post {
            val current = player.currentMediaItemIndex
            if (current < 0) return@post
            // Drop any existing occurrence so the queue never shows duplicates.
            for (i in player.mediaItemCount - 1 downTo 0) {
                if (i != current && trackIdOf(player.getMediaItemAt(i).mediaId) == cleanId) {
                    player.removeMediaItem(i)
                }
            }
            val at = player.currentMediaItemIndex
            if (at >= 0) player.addMediaItem(at + 1, mediaItemFor(track.copy(id = cleanId)))
        }
    }

    /**
     * Upcoming queue items (after the current window index), used for host
     * queue broadcasting.
     */
    fun upcomingQueue(): List<TrackInfo> = query(emptyList()) {
        val index = player.currentMediaItemIndex
        (index + 1 until player.mediaItemCount)
            .map { windowIndex -> player.getMediaItemAt(windowIndex) }
            .mapNotNull { item ->
                val id = trackIdOf(item.mediaId) ?: return@mapNotNull null
                val metadata = item.mediaMetadata
                val durText = metadata.extras?.getString("durationText")
                val parsed = parseDurationText(durText)
                val durationMs = if (parsed > 0L) parsed else metadata.extras?.getLong("durationMs", 0L) ?: 0L
                TrackInfo(
                    id = id,
                    title = metadata.title?.toString().orEmpty().ifBlank { id },
                    artist = metadata.artist?.toString().orEmpty(),
                    album = metadata.albumTitle?.toString().orEmpty(),
                    duration = durationMs,
                    thumbnail = metadata.artworkUri?.toString().orEmpty()
                )
            }
    }

    /** Snapshot of the currently-loaded track for host broadcasts. */
    fun currentTrackInfo(suggestedBy: String = ""): TrackInfo? = query<TrackInfo?>(null) {
        val item = player.currentMediaItem ?: return@query null
        val id = trackIdOf(item.mediaId) ?: return@query null
        val metadata = item.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: id
        val playerDuration = if (player.duration == androidx.media3.common.C.TIME_UNSET) 0L else player.duration
        val duration = if (playerDuration > 0L) {
            playerDuration
        } else {
            val durText = metadata.extras?.getString("durationText")
            val parsed = parseDurationText(durText)
            if (parsed > 0L) parsed else metadata.extras?.getLong("durationMs", 0L) ?: 0L
        }
        TrackInfo(
            id = id,
            title = title,
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            duration = duration,
            thumbnail = metadata.artworkUri?.toString().orEmpty(),
            suggestedBy = suggestedBy
        )
    }

    companion object {
        /** Parse duration string ("3:45", "1:15:30") to milliseconds. */
        fun parseDurationText(durationText: String?): Long {
            if (durationText.isNullOrBlank()) return 0L
            return try {
                val parts = durationText.trim().split(":").map { it.toLong() }
                val seconds = when (parts.size) {
                    1 -> parts[0]
                    2 -> parts[0] * 60 + parts[1]
                    3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                    else -> 0L
                }
                seconds * 1000L
            } catch (_: Exception) {
                0L
            }
        }

        /**
         * Normalises a player mediaId to a sync-track video id. Items are
         * loaded with plain videoIds; legacy watch-URL items still resolve.
         */
        fun trackIdOf(mediaId: String?): String? {
            if (mediaId.isNullOrBlank()) return null
            if (mediaId.startsWith("radio:") || mediaId.startsWith("local:")) return null
            val normalized = ListenTogetherCoordinator.normalizeVideoId(mediaId)
            if (normalized.isNotBlank()) return normalized
            val stripped = mediaId
                .removePrefix("https://music.youtube.com/watch?v=")
                .removePrefix("https://youtube.com/watch?v=")
                .removePrefix("http://music.youtube.com/watch?v=")
                .removePrefix("http://youtube.com/watch?v=")
                .substringBefore('&')
                .substringBefore('?')
            return stripped.takeIf { it.isNotBlank() && !it.contains(":") && !it.contains("/") }
        }

        /**
         * [TrackInfo] of the given [current] track plus [upcoming], deduped —
         * every canonical queue write in the room relies on this invariant
         * (current always first, index 0).
         */
        fun canonicalQueue(current: TrackInfo, upcoming: List<TrackInfo>): List<TrackInfo> =
            listOf(current) + upcoming.filter { it.id != current.id }.distinctBy { it.id }
    }
}
