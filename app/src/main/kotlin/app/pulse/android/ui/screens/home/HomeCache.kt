package app.pulse.android.ui.screens.home

import android.content.Context
import app.pulse.android.preferences.DataPreferences
import app.pulse.android.utils.thumbnail
import app.pulse.core.data.models.Song
import app.pulse.core.ui.Dimensions
import coil3.imageLoader
import coil3.request.ImageRequest
import app.pulse.providers.innertube.Innertube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days

// Disk-persisted home feed cache (ported from the desktop HomeScreen): JSON
// files in the app's files dir with a user-configurable TTL
// (DataPreferences.homeFeedCacheDays, 0 = disabled). Restored on cold start so
// Quick Picks and Discover render instantly instead of refetching. All disk IO
// runs on Dispatchers.IO.
private val fCacheJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

object HomeCache {
    @Serializable
    data class DiscoverData(val page: Innertube.DiscoverPage? = null)

    @Serializable
    data class RelatedData(
        val page: Innertube.RelatedPage? = null,
        // Seed (latest listened song) this feed was generated from, so a cold
        // start can tell "instant render from cache" apart from a stale feed.
        val seedId: String? = null
    )

    /** Page restored from disk plus the seed it was generated for. */
    data class RelatedCache(
        val page: Result<Innertube.RelatedPage?>?,
        val seedId: String?
    )

    @Serializable
    data class BecauseSection(
        val songs: List<Innertube.SongItem> = emptyList(),
        val seedTitles: List<String> = emptyList()
    )

    /**
     * Post-listening recommendation sections (MetroList-style): recs with the
     * seeds behind them, resume mix and rediscoveries. Bounded takes
     * everywhere: tiny in RAM (~20 songs), one small JSON on disk.
     */
    @Serializable
    data class HomeSectionsData(
        val seedId: String? = null,
        val because: BecauseSection? = null,
        val keep: List<Song> = emptyList(),
        val forgotten: List<Song> = emptyList()
    )

    private val ttlMs: Long
        get() = DataPreferences.homeFeedCacheDays.days.inWholeMilliseconds

    @Volatile
    var inMemoryDiscover: Innertube.DiscoverPage? = null

    @Volatile
    var inMemoryRelated: Innertube.RelatedPage? = null

    @Volatile
    var inMemoryRelatedSeed: String? = null

    @Volatile
    var inMemorySections: HomeSectionsData? = null

    /** Pre-warms in-memory cache from disk asynchronously during app start. */
    suspend fun initFromDisk(filesDir: File) {
        if (inMemoryDiscover == null) {
            inMemoryDiscover = restore<DiscoverData, Innertube.DiscoverPage>(filesDir, "discover.json") { it.page }?.getOrNull()
        }
        if (inMemoryRelated == null) {
            restoreRelated(filesDir) // also fills inMemoryRelatedSeed
        }
        if (inMemorySections == null) {
            restoreSections(filesDir)
        }
    }

    /** Restores a fresh cached discover page, or null when absent/stale/disabled. */
    suspend fun restoreDiscover(filesDir: File): Result<Innertube.DiscoverPage>? {
        inMemoryDiscover?.let { return Result.success(it) }
        val res = restore<DiscoverData, Innertube.DiscoverPage>(filesDir, "discover.json") { it.page }
        res?.getOrNull()?.let { inMemoryDiscover = it }
        return res
    }

    /** Restores a fresh cached related page (plus its seed), or null when absent/stale/disabled. */
    suspend fun restoreRelated(filesDir: File): RelatedCache? {
        if (inMemoryRelated != null) return RelatedCache(Result.success(inMemoryRelated), inMemoryRelatedSeed)
        if (ttlMs <= 0L) return null
        return withContext(Dispatchers.IO) {
            val file = File(filesDir, "home/related.json")
            try {
                if (file.exists() && System.currentTimeMillis() - file.lastModified() <= ttlMs) {
                    val data = fCacheJson.decodeFromString<RelatedData>(file.readText())
                    val page = data.page
                    if (page != null) {
                        inMemoryRelated = page
                        inMemoryRelatedSeed = data.seedId
                        RelatedCache(Result.success(page), data.seedId)
                    } else {
                        file.delete()
                        null
                    }
                } else {
                    if (file.exists()) file.delete()
                    null
                }
            } catch (e: Exception) {
                file.delete()
                null
            }
        }
    }

    suspend fun saveDiscover(filesDir: File, page: Innertube.DiscoverPage) {
        inMemoryDiscover = page
        save(filesDir, "discover.json", DiscoverData(page))
    }

    suspend fun saveRelated(filesDir: File, page: Innertube.RelatedPage, seedId: String? = null) {
        inMemoryRelated = page
        inMemoryRelatedSeed = seedId
        save(filesDir, "related.json", RelatedData(page, seedId))
    }

    suspend fun restoreSections(filesDir: File): HomeSectionsData? {
        inMemorySections?.let { return it }
        val res = restore<HomeSectionsData, HomeSectionsData>(filesDir, "sections.json") { it }
        res?.getOrNull()?.let { inMemorySections = it }
        return res?.getOrNull()
    }

    suspend fun saveSections(filesDir: File, data: HomeSectionsData) {
        inMemorySections = data
        save(filesDir, "sections.json", data)
    }

    /**
     * Warms Coil's caches with the thumbnails the home feed is about to show.
     * The requested URLs use the exact same size math as the item composables
     * (SongItem 2x, albums/artists/playlists 1x): a prefetch for any other URL
     * would warm cache entries the UI never reads, leaving every thumbnail a
     * full network fetch on first display. Idempotent: already-cached URLs
     * resolve from disk, zero network.
     */
    fun prefetchThumbs(
        context: Context,
        discover: Innertube.DiscoverPage?,
        related: Innertube.RelatedPage?
    ) {
        val density = context.resources.displayMetrics.density
        val songPx = (Dimensions.thumbnails.song.value * density).roundToInt() * 2
        val albumPx = (Dimensions.thumbnails.album.value * density).roundToInt()
        val artistPx = (Dimensions.thumbnails.artist.value * density).roundToInt()
        val urls = listOfNotNull(
            discover?.newReleaseAlbums?.mapNotNull { it.thumbnail?.url?.thumbnail(albumPx) },
            discover?.trending?.songs?.mapNotNull { it.thumbnail?.size(songPx) },
            related?.songs?.mapNotNull { it.thumbnail?.size(songPx) },
            related?.albums?.mapNotNull { it.thumbnail?.url?.thumbnail(albumPx) },
            related?.artists?.mapNotNull { it.thumbnail?.url?.thumbnail(artistPx) },
            related?.playlists?.mapNotNull { it.thumbnail?.url?.thumbnail(albumPx) }
        ).flatten().distinct().take(16)

        urls.forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey(url)
                    .build()
            )
        }
    }

    // Stale or corrupt cache is deleted so the next open fetches fresh data.
    private suspend inline fun <reified T, reified R> restore(
        filesDir: File,
        name: String,
        crossinline extract: (T) -> R?
    ): Result<R>? = withContext(Dispatchers.IO) {
        if (ttlMs <= 0L) return@withContext null
        val file = File(filesDir, "home/$name")
        try {
            if (file.exists() && System.currentTimeMillis() - file.lastModified() <= ttlMs) {
                val value = extract(fCacheJson.decodeFromString<T>(file.readText()))
                if (value != null) Result.success(value) else {
                    file.delete()
                    null
                }
            } else {
                if (file.exists()) file.delete()
                null
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private suspend inline fun <reified T> save(
        filesDir: File,
        name: String,
        data: T
    ) {
        if (ttlMs <= 0L) return
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(filesDir, "home/$name")
                file.parentFile?.mkdirs()
                file.writeText(fCacheJson.encodeToString(data))
            }
        }
    }
}
