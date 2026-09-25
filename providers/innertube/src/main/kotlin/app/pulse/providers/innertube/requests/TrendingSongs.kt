package app.pulse.providers.innertube.requests

import app.pulse.providers.innertube.Innertube
import app.pulse.providers.innertube.models.BrowseResponse
import app.pulse.providers.innertube.models.Context
import app.pulse.providers.innertube.models.bodies.BrowseBody
import app.pulse.providers.innertube.utils.from
import app.pulse.providers.innertube.utils.fromMoodPlaylist
import app.pulse.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.async

/**
 * The fresh-install feed: Hindi songs by default (from the Hindi mood page:
 * real audio tracks, not videos), worldwide English hits as fallback, plus
 * Hindi featured playlists. Deliberately Hindi-only: the Explore
 * trending/videos/albums shelves mix every Indian region with no per-item
 * language signal, so they are excluded rather than guessed at. Mood
 * category tiles themselves live on the Discover tab.
 *
 * Two waves: page fetches (explore for the mood tile + charts for the global
 * playlist), then the mood page + global playlist concurrently. A third wave
 * (video-chart fallback) runs only if the mood page yields nothing.
 * On failure an empty page is returned and callers show an explicit empty
 * state instead of an indefinite shimmer.
 */
suspend fun Innertube.trendingFeed(): Innertube.RelatedPage = runCatchingCancellable {
    kotlinx.coroutines.coroutineScope {
        val moodTile = async { exploreHindiMoodTile() }
        val chartTiles = async { chartPlaylistTiles() }
        val tile = moodTile.await()
        val tiles = chartTiles.await()
        val moodFeed = async { browseHindiMood(tile) }
        val globalSongs = async { chartSongs(tiles.findChart("international") ?: FALLBACK_GLOBAL_CHART_PLAYLIST) }
        val mood = moodFeed.await()
        val global = globalSongs.await()
        val hindi = mood.songs.takeIf { it.isNotEmpty() }
            ?: chartSongs(tiles.findChart("hindi") ?: FALLBACK_HINDI_CHART_PLAYLIST).take(35)
        Innertube.RelatedPage(
            songs = (hindi.take(35) + global.take(25))
                .distinctBy { it.key }
                .takeIf(List<Innertube.SongItem>::isNotEmpty),
            playlists = mood.playlists.takeIf(List<Innertube.PlaylistItem>::isNotEmpty)
        )
    }
}?.getOrNull() ?: Innertube.RelatedPage()

private suspend fun Innertube.chartSongs(playlistId: String): List<Innertube.SongItem> =
    runCatchingCancellable {
        playlistPage(
            BrowseBody(browseId = playlistId, context = GlobalExploreContext)
        )?.getOrNull()?.songsPage?.items.orEmpty()
            .map { song ->
                song.copy(
                    authors = song.authors?.firstOrNull()?.let { listOf(it) } ?: emptyList()
                )
            }
            .distinctBy { it.key }
    }?.getOrNull().orEmpty()

// Last-resort pinned IDs (weekly-rotating: the live lookups above are
// authoritative; these only matter if the charts/explore shapes change).
private const val FALLBACK_GLOBAL_CHART_PLAYLIST = "VLPL4fGSI1pDJn49TUu37nJoN2QTeYuRwmNv"
private const val FALLBACK_HINDI_CHART_PLAYLIST = "VLPL4fGSI1pDJn5RgLW0Sb_zECecWdH_4zOX"
private val FALLBACK_HINDI_MOOD = MoodTarget(
    browseId = "FEmusic_moods_and_genres_category",
    params = "ggMPOg1uX2ZvbzNJMzJwRkFT"
)

private fun Map<String, String>.findChart(keyword: String): String? =
    entries.firstOrNull { it.key.contains(keyword) }?.value

private suspend fun Innertube.chartPlaylistTiles(): Map<String, String> = runCatchingCancellable {
    client.post(BROWSE) {
        setBody(BrowseBody(browseId = "FEmusic_charts", context = GlobalExploreContext))
        mask("contents")
    }.body<BrowseResponse>()
        ?.contents
        ?.singleColumnBrowseResultsRenderer
        ?.tabs
        ?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer
        ?.contents
        .orEmpty()
        .asSequence()
        .mapNotNull { it.musicCarouselShelfRenderer }
        .flatMap { it.contents.orEmpty() }
        .mapNotNull { it.musicTwoRowItemRenderer }
        .mapNotNull { item ->
            val title = item.title?.runs?.firstOrNull()?.text?.lowercase() ?: return@mapNotNull null
            val id = item.navigationEndpoint?.browseEndpoint?.browseId
                ?.takeIf { it.startsWith("VLPL") } ?: return@mapNotNull null
            title to id
        }
        .toMap()
}?.getOrNull().orEmpty()

private data class MoodTarget(
    val browseId: String,
    val params: String
)

private data class HindiMoodFeed(
    val songs: List<Innertube.SongItem> = emptyList(),
    val playlists: List<Innertube.PlaylistItem> = emptyList()
)

/**
 * The Hindi mood tile from Explore (exact title match). Null when the shelf
 * or tile is missing; callers fall back to the pinned target.
 */
private suspend fun Innertube.exploreHindiMoodTile(): MoodTarget? = runCatchingCancellable {
    client.post(BROWSE) {
        setBody(BrowseBody(browseId = "FEmusic_explore", context = GlobalExploreContext))
        mask("contents")
    }.body<BrowseResponse>()
        ?.contents
        ?.singleColumnBrowseResultsRenderer
        ?.tabs
        ?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer
        ?.contents
        .orEmpty()
        .asSequence()
        .mapNotNull { it.musicCarouselShelfRenderer }
        .filter {
            it.header
                ?.musicCarouselShelfBasicHeaderRenderer
                ?.moreContentButton
                ?.buttonRenderer
                ?.navigationEndpoint
                ?.browseEndpoint
                ?.browseId == "FEmusic_moods_and_genres"
        }
        .flatMap { it.contents.orEmpty() }
        .mapNotNull { it.musicNavigationButtonRenderer }
        .firstOrNull { button ->
            button.buttonText.runs.firstOrNull()?.text.equals("hindi", ignoreCase = true)
        }
        ?.let { button ->
            val endpoint = button.clickCommand.browseEndpoint
            endpoint?.browseId?.let { browseId ->
                MoodTarget(browseId = browseId, params = endpoint.params.orEmpty())
            }
        }
}?.getOrNull()

/**
 * Hindi mood page: the "Songs" shelf (50 audio tracks) and the "Featured
 * playlists" shelf (Hindi playlists). Verified live against the API.
 */
private suspend fun Innertube.browseHindiMood(target: MoodTarget?): HindiMoodFeed =
    runCatchingCancellable {
        val actual = target ?: FALLBACK_HINDI_MOOD
        val sections = client.post(BROWSE) {
            setBody(
                BrowseBody(
                    browseId = actual.browseId,
                    params = actual.params,
                    context = GlobalExploreContext
                )
            )
            mask("contents")
        }.body<BrowseResponse>()
            ?.contents
            ?.singleColumnBrowseResultsRenderer
            ?.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
            ?.contents
            .orEmpty()
            .mapNotNull { it.musicCarouselShelfRenderer }
        val songs = sections
            .firstOrNull {
                it.header
                    ?.musicCarouselShelfBasicHeaderRenderer
                    ?.title
                    ?.runs
                    ?.firstOrNull()
                    ?.text
                    .equals("songs", ignoreCase = true)
            }
            ?.contents
            .orEmpty()
            .mapNotNull { it.musicResponsiveListItemRenderer }
            .mapNotNull(Innertube.SongItem::from)
            .map { song ->
                song.copy(
                    authors = song.authors?.firstOrNull()?.let { listOf(it) } ?: emptyList()
                )
            }
            .distinctBy { it.key }
        val playlists = sections
            .firstOrNull {
                it.header
                    ?.musicCarouselShelfBasicHeaderRenderer
                    ?.title
                    ?.runs
                    ?.firstOrNull()
                    ?.text
                    ?.contains("featured", ignoreCase = true) == true
            }
            ?.contents
            .orEmpty()
            .mapNotNull { item ->
                item.musicTwoRowItemRenderer?.let { renderer ->
                    Innertube.PlaylistItem.fromMoodPlaylist(renderer)
                }
            }
        HindiMoodFeed(songs = songs, playlists = playlists)
    }?.getOrNull() ?: HindiMoodFeed()

// Fresh-install/global context... explicit hl/gl so a future default
// change can never silently re-regionalize it.
private val GlobalExploreContext
    get() = Context.DefaultWebNoLang.copy(
        client = Context.DefaultWebNoLang.client.copy(hl = "en", gl = "US")
    )
