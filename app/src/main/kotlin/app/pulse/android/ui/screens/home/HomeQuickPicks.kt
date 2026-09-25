package app.pulse.android.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.pulse.android.Database
import app.pulse.android.LocalPlayerAwareWindowInsets
import app.pulse.android.LocalPlayerServiceBinder
import app.pulse.android.R
import app.pulse.core.data.models.Song
import app.pulse.android.preferences.DataPreferences
import app.pulse.android.query
import app.pulse.android.ui.components.LocalMenuState
import app.pulse.android.ui.components.ShimmerHost
import app.pulse.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.pulse.android.ui.components.themed.NonQueuedMediaItemMenu
import app.pulse.android.ui.components.themed.TextPlaceholder
import app.pulse.android.ui.items.AlbumItem
import app.pulse.android.ui.items.AlbumItemPlaceholder
import app.pulse.android.ui.items.ArtistItem
import app.pulse.android.ui.items.ArtistItemPlaceholder
import app.pulse.android.ui.items.PlaylistItem
import app.pulse.android.ui.items.PlaylistItemPlaceholder
import app.pulse.android.ui.items.SongItem
import app.pulse.android.ui.items.SongItemPlaceholder
import app.pulse.android.ui.screens.Route
import app.pulse.android.ui.components.themed.CollapsingHeader
import app.pulse.android.ui.components.themed.CollapsingHeaderContentSpacer
import app.pulse.android.utils.asMediaItem
import androidx.media3.common.MediaItem
import app.pulse.android.utils.center
import app.pulse.android.utils.forcePlay
import app.pulse.android.utils.playingSong
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
import java.io.File
import app.pulse.compose.persist.persist
import app.pulse.core.ui.Dimensions
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.utils.isLandscape
import app.pulse.providers.innertube.Innertube
import app.pulse.providers.innertube.models.NavigationEndpoint
import app.pulse.providers.innertube.models.bodies.NextBody
import app.pulse.providers.innertube.requests.nextPage
import app.pulse.providers.innertube.requests.relatedPage
import app.pulse.providers.innertube.requests.trendingFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.days

// Upper bound for the Quick Picks grid so a large related page never turns
// into hundreds of lazy rows (memory + scroll jank on low-RAM devices).
private const val MAX_QUICK_PICKS_SONGS = 60

// A song that can drive the related feed. Radio/local items have no resolvable
// related endpoint and are skipped as seeds (their events never get a Song row).
private fun String?.isQuickPicksSeed(): Boolean =
    this != null && isNotEmpty() && !startsWith("radio:") && !startsWith("local:")

private data class QuickPicksSeeds(
    val primary: List<Song>,
    val fallback: List<Song>
) {
    val head: Song? get() = primary.firstOrNull() ?: fallback.firstOrNull()
}


@OptIn(ExperimentalFoundationApi::class)
@Route
@Composable
fun QuickPicks(
    onAlbumClick: (Innertube.AlbumItem) -> Unit,
    onArtistClick: (Innertube.ArtistItem) -> Unit,
    onPlaylistClick: (Innertube.PlaylistItem) -> Unit,
) {
    val (colorPalette, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val windowInsets = LocalPlayerAwareWindowInsets.current

    // Starred seed: the song the current feed was generated from, shown with a
    // star in the grid. Distinct from the trending fallback: on a fresh install
    // (or whenever no related feed exists yet) the grid shows trending songs
    // with no star until the first real seed arrives.
    var starredSeed by persist<Song?>("home/trending")

    var relatedPageResult by persist<Result<Innertube.RelatedPage?>?>(tag = "home/relatedPageResult")

    // Seed (latest listened song) the current feed is based on. Comparing the
    // current seed against it tells us whether a refetch is needed or the feed
    // is already up to date – this is what lets the grid update the moment the
    // user listens to something new, without hammering the network on repeat.
    var lastSeedId by persist<String?>("home/quickPicksSeedId")

    // Synchronously use in-memory cache if not already set, eliminating placeholder flash
    if (relatedPageResult == null && HomeCache.inMemoryRelated != null) {
        relatedPageResult = Result.success(HomeCache.inMemoryRelated)
        if (lastSeedId == null) lastSeedId = HomeCache.inMemoryRelatedSeed
    }

    // Restore the disk cache first so a cold open renders instantly and
    // skips the network while the cache is fresh (TTL-configurable).
    val context = LocalContext.current.applicationContext
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Single-flight fetch token: launching a new fetch (a newer seed or a manual
    // refresh) cancels the previous request so the feed always ends up matching
    // the latest thing the user listened to.
    var fetchJob by remember { mutableStateOf<Job?>(null) }

    fun launchFetch(block: suspend () -> Unit) {
        fetchJob?.cancel()
        fetchJob = scope.launch { block() }
    }

    // Post-listening recommendation sections (rendered below the grid).
    var sections by persist<HomeCache.HomeSectionsData?>("home/sections")
    var sectionsBuiltFor by remember { mutableStateOf<String?>(null) }
    var sectionsBuiltAt by remember { mutableLongStateOf(0L) }
    if (sections == null && HomeCache.inMemorySections != null) {
        sections = HomeCache.inMemorySections
    }

    // ponytail: shared click handler extracted to avoid duplication
    fun playSong(mediaItem: MediaItem) {
        binder?.stopRadio()
        binder?.player?.forcePlay(mediaItem)
        binder?.setupRadio(NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId))
    }

    // Related feed for a seed, MetroList-style: the songs grid comes from the
    // Up-next queue (the same request that fills the player queue on every
    // tap, so it works wherever playback works), while albums/artists/
    // playlists sections come from the browse-related page best-effort. The
    // grid never waits on — and never fails because of — the sections fetch,
    // and there is no multi-candidate request storm.
    suspend fun fetchFeedFor(seedId: String): Innertube.RelatedPage? = withContext(Dispatchers.IO) {
        val songsDeferred = async {
            Innertube.nextPage(NextBody(videoId = seedId))
                ?.getOrNull()?.itemsPage?.items.orEmpty()
                .filter { it.key != seedId }
        }
        val sectionsDeferred = async {
            Innertube.relatedPage(body = NextBody(videoId = seedId))?.getOrNull()
        }
        val songs = songsDeferred.await()
        val sections = sectionsDeferred.await()
        val mergedSongs = (songs + sections?.songs.orEmpty()).distinctBy { it.key }
        if (mergedSongs.isEmpty()) return@withContext null
        Innertube.RelatedPage(
            songs = mergedSongs,
            albums = sections?.albums,
            artists = sections?.artists,
            playlists = sections?.playlists
        )
    }

    // No listening history yet: render the Hindi trending feed (global
    // English fallback included). Phase 1 shows songs instantly; phase 2
    // merges albums/artists/playlists rooted in the first Hindi song so every
    // section matches the songs. The moment the user plays something the
    // source flow re-emits, the seed becomes non-null and the feed switches
    // to their listening – MetroList-style. Also used by pull-to-refresh to
    // retry, so a temporary failure leaves an empty state instead of a shimmer
    // and is recoverable without restarting the app.
    suspend fun loadTrendingFeed() {
        val feed = withContext(Dispatchers.IO) {
            Innertube.trendingFeed()
        }
        val songs = feed.songs.orEmpty()
        if (songs.isEmpty()) {
            // Explicit empty state instead of an indefinite shimmer; pull-to-
            // refresh re-runs this and can recover from a network blip.
            relatedPageResult = Result.success(Innertube.RelatedPage())
            return
        }

        var page = Innertube.RelatedPage(songs = songs, playlists = feed.playlists)
        relatedPageResult = Result.success(page)
        // No listening seed: the first play re-seeds the feed.
        lastSeedId = null
        HomeCache.saveRelated(context.filesDir, page, null)
        HomeCache.prefetchThumbs(context, null, page)

        // Phase 2 runs inside the same fetch (cancellable, joined by
        // pull-to-refresh): sections rooted in the top Hindi seeds merge in
        // when ready, so the fresh feed is full (albums/artists/playlists)
        // instead of a single sparse row. Three seeds are fanned out
        // concurrently and merged distinct; MPTR rarely yields playlists, so
        // the Hindi featured ones above are preserved, never overwritten.
        val seeds = songs.take(3).map { it.key }.distinct()
        val sectionPages = withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                seeds.map { seedId ->
                    async {
                        Innertube.relatedPage(body = NextBody(videoId = seedId))?.getOrNull()
                    }
                }.mapNotNull { it.await() }
            }
        }
        if (sectionPages.isEmpty()) return
        val albums = sectionPages.flatMap { it.albums.orEmpty() }.distinctBy { it.key }.take(20)
        val artists = sectionPages.flatMap { it.artists.orEmpty() }.distinctBy { it.key }.take(20)
        val mptrPlaylists = sectionPages.flatMap { it.playlists.orEmpty() }.distinctBy { it.key }.take(20)
        if (albums.isEmpty() && artists.isEmpty() && mptrPlaylists.isEmpty()) return
        page = page.copy(
            albums = albums.takeIf(List<Innertube.AlbumItem>::isNotEmpty),
            artists = artists.takeIf(List<Innertube.ArtistItem>::isNotEmpty),
            playlists = mptrPlaylists.takeIf(List<Innertube.PlaylistItem>::isNotEmpty) ?: page.playlists
        )
        relatedPageResult = Result.success(page)
        HomeCache.saveRelated(context.filesDir, page, null)
        HomeCache.prefetchThumbs(context, null, page)
    }

    /**
     * Post-listening recommendation sections, MetroList-style but bounded for
     * RAM/network/storage: recs-with-seeds from recent + liked Up-next mixes
     * (the same proven request as the grid), a resume mix from 30-day play
     * counts and rediscoveries from all-time play counts absent recent
     * history — all DB reads except the recs. Single-flight with the grid
     * (same fetch job), disk-cached for instant cold open, skipped when the
     * same seed was built recently unless forced.
     */
    suspend fun buildSections(seedId: String, gridIds: Set<String>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && seedId == sectionsBuiltFor && now - sectionsBuiltAt < 30 * 60_000L) return
        val data = withContext(Dispatchers.IO) {
            val liked = Database.favoritesByLikedAtDesc().first()
            val recent = Database.latestEvents(limit = 6).first()
            val likeSeed = liked.firstOrNull { it.id != seedId && recent.none { r -> r.id == it.id } }
            val becauseSeeds = (recent.filter { it.id != seedId }.take(2) + listOfNotNull(likeSeed))
                .distinctBy { it.id }
                .take(3)
            val seen = (gridIds + seedId).toMutableSet()
            val recSongs = mutableListOf<Innertube.SongItem>()
            val seedTitles = mutableListOf<String>()
            for (s in becauseSeeds) {
                val items = Innertube.nextPage(NextBody(videoId = s.id))
                    ?.getOrNull()?.itemsPage?.items.orEmpty()
                var added = false
                for (item in items) {
                    if (seen.add(item.key)) {
                        recSongs.add(item)
                        added = true
                        if (recSongs.size >= 9) break
                    }
                }
                if (added) s.title.takeIf { it.isNotBlank() }?.let(seedTitles::add)
                if (recSongs.size >= 9) break
            }
            seen += recSongs.map { it.key }
            val keep = Database.trending(limit = 50, period = 30.days.inWholeMilliseconds).first()
                .filter { seen.add(it.id) }
                .take(6)
            val recentIds = Database.latestEvents(limit = 50).first().map { it.id }.toSet()
            val forgotten = Database.songsByPlayTimeDesc(200).first()
                .filter { it.id !in recentIds && seen.add(it.id) }
                .take(6)
            HomeCache.HomeSectionsData(
                seedId = seedId,
                because = HomeCache.BecauseSection(recSongs, seedTitles).takeIf { recSongs.isNotEmpty() },
                keep = keep,
                forgotten = forgotten
            )
        }
        sections = data
        sectionsBuiltFor = seedId
        sectionsBuiltAt = now
        HomeCache.saveSections(context.filesDir, data)
        HomeCache.prefetchThumbs(context, null, Innertube.RelatedPage(songs = data.because?.songs))
    }

    // React to the current seed: refresh the feed whenever the user's listening
    // changes, and skip the network when the feed is already up to date.
    suspend fun loadRelated(seeds: QuickPicksSeeds) {
        val seed = seeds.head

        // Brand-new install with no listening history: show the Hindi/global
        // trending feed (songs + moods instantly, matching sections stream
        // in) instead of a shimmer. Once the user plays something the flows
        // re-emit and the feed follows their listening, MetroList-style.
        if (seed == null) {
            if (relatedPageResult == null && starredSeed == null) {
                launchFetch { loadTrendingFeed() }
            }
            return
        }

        // Cache hit: the feed we're already showing was generated from this
        // exact seed – render instantly, no refetch. The star is left alone:
        // the feed may have been generated from a fallback candidate, and
        // forcing it to the head here would mislabel the grid.
        if (seed.id.isQuickPicksSeed() && seed.id == lastSeedId &&
            relatedPageResult?.getOrNull()?.songs?.isNotEmpty() == true
        ) {
            if (starredSeed == null) starredSeed = seed
            return
        }

        if (seed.id.isQuickPicksSeed()) starredSeed = seed

        launchFetch {
            val page = fetchFeedFor(seed.id)
            if (page != null) {
                relatedPageResult = Result.success(page)
                lastSeedId = seed.id
                HomeCache.saveRelated(context.filesDir, page, lastSeedId)
                HomeCache.prefetchThumbs(context, null, page)
                buildSections(seed.id, page.songs.orEmpty().map { it.key }.toSet(), force = false)
            } else if (relatedPageResult == null) {
                // Explicit empty state instead of an indefinite shimmer.
                relatedPageResult = Result.success(Innertube.RelatedPage())
            }
        }
    }

    // Force a fresh feed for the current seed, bypassing the cache TTL.
    // Awaits the kicked-off fetch so callers (pull-to-refresh, tap-to-retry)
    // reflect the real load instead of flashing while it still runs.
    suspend fun refreshRelated(forceSections: Boolean = false) {
        val seed = starredSeed
        if (seed?.id?.isQuickPicksSeed() == true) {
            launchFetch {
                val fresh = fetchFeedFor(seed.id)
                if (fresh != null) {
                    relatedPageResult = Result.success(fresh)
                    HomeCache.saveRelated(context.filesDir, fresh, seed.id)
                    HomeCache.prefetchThumbs(context, null, fresh)
                    buildSections(seed.id, fresh.songs.orEmpty().map { it.key }.toSet(), force = forceSections)
                }
            }
        } else {
            // Nothing played yet (or the empty state from a previous failed
            // load): (re)load the trending feed so pull-to-refresh can recover
            // without restarting the app.
            launchFetch { loadTrendingFeed() }
        }
        fetchJob?.join()
    }

    // Shared pull-to-refresh / tap-to-retry entry point. The finally keeps the
    // indicator honest even when a superseding fetch cancels this one.
    suspend fun retryRefresh(forceSections: Boolean = false) {
        isRefreshing = true
        try {
            refreshRelated(forceSections = forceSections)
        } finally {
            isRefreshing = false
        }
    }

    LaunchedEffect(DataPreferences.quickPicksSource) {
        // Cancel any fetch left over from a previous cycle.
        fetchJob?.cancel()

        // Restore the disk cache (with the seed it was generated from) so a cold
        // open renders instantly while the cache is fresh (TTL-configurable).
        if (relatedPageResult == null) {
            val cached = HomeCache.restoreRelated(context.filesDir)
            if (cached?.page?.getOrNull()?.songs?.isNotEmpty() == true) {
                relatedPageResult = cached.page
                // Remember the seed this feed belongs to, so the first emission
                // skips the network instead of refetching the same feed.
                if (lastSeedId == null) lastSeedId = cached.seedId
            } else if (cached != null) {
                // stale/empty cache: delete so the next restart fetches fresh.
                File(context.filesDir, "home/related.json").delete()
            }
        }
        if (sections == null) {
            sections = HomeCache.restoreSections(context.filesDir)
        }

        // Both flows read the Event table, so either one re-emits the moment
        // something is played – Quick Picks always catches up with what the
        // user is listening to, in both the default and the fallback source.
        // "Trending" seeds from a rolling 7-day window so fresh listens reorder
        // the feed quickly (all-time play counts barely move on new songs).
        val useTrendingSource = DataPreferences.quickPicksSource ==
            DataPreferences.QuickPicksSource.Trending
        val trendingFlow = if (useTrendingSource) {
            Database.trending(limit = 3, period = 7.days.inWholeMilliseconds)
        } else {
            Database.trending(limit = 3)
        }

        val sourceFlow = combine(
            Database.latestEvents(limit = 4),
            trendingFlow
        ) { recent, trend ->
            if (useTrendingSource) QuickPicksSeeds(primary = trend, fallback = recent)
            else QuickPicksSeeds(primary = recent, fallback = trend)
        }.distinctUntilChanged { old, new -> old.head?.id == new.head?.id }

        sourceFlow.collect {
            runCatching { loadRelated(it) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
        }
    }

    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val quickPicksLazyGridState = rememberLazyGridState()
    val albumsRowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val artistsRowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val playlistsRowState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Ponytail: grid transform runs once per feed/star change, not on every
    // recomposition (play/pause toggles recompose this screen).
    val gridSongs = remember(relatedPageResult, starredSeed) {
        relatedPageResult?.getOrNull()?.songs
            ?.distinctBy { it.key }
            ?.filter { it.key != starredSeed?.id }
            ?.take(MAX_QUICK_PICKS_SONGS)
            ?: emptyList()
    }

    val endPaddingValues = windowInsets.only(WindowInsetsSides.End).asPaddingValues()

    val sectionTextModifier = Modifier
        .padding(horizontal = 16.dp)
        .padding(top = 24.dp, bottom = 8.dp)
        .padding(endPaddingValues)

    val (currentMediaId, playing) = playingSong(binder)


    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch { retryRefresh(forceSections = true) }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val quickPicksLazyGridItemWidthFactor =
            if (isLandscape && screenWidth * 0.475f >= 320.dp) 0.475f else 0.75f

        val itemInHorizontalGridWidth = screenWidth * quickPicksLazyGridItemWidthFactor

        CollapsingHeader(
            title = stringResource(R.string.vmusic),
            lazyListState = lazyListState
        ) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .background(colorPalette.background0)
                    .fillMaxSize(),
                contentPadding = windowInsets
                    .only(WindowInsetsSides.Vertical)
                    .asPaddingValues()
            ) {
                item(key = "spacer") {
                    Spacer(modifier = Modifier.height(CollapsingHeaderContentSpacer))
                }

                relatedPageResult?.getOrNull()?.let { related ->

                    item(key = "quick_picks_title") {
                        BasicText(
                            text = if (lastSeedId == null) stringResource(R.string.trending_songs)
                            else stringResource(R.string.quick_picks),
                            style = typography.m.semiBold,
                            modifier = sectionTextModifier
                        )
                    }

                    item(key = "quick_picks_grid") {
                        LazyHorizontalGrid(
                            state = quickPicksLazyGridState,
                            rows = GridCells.Fixed(4),
                            contentPadding = endPaddingValues,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((Dimensions.thumbnails.song + Dimensions.items.verticalPadding * 2) * 4)
                        ) {
                            starredSeed?.let { song ->
                                item {
                                    SongItem(
                                        modifier = Modifier
                                            .combinedClickable(
                                                onLongClick = {
                                                    menuState.display {
                                                        NonQueuedMediaItemMenu(
                                                            onDismiss = menuState::hide,
                                                            mediaItem = song.asMediaItem,
                                                            onRemoveFromQuickPicks = {
                                                                query { Database.clearEventsFor(song.id) }
                                                            }
                                                        )
                                                    }
                                                },
                                                onClick = { playSong(song.asMediaItem) }
                                            )
                                            .width(itemInHorizontalGridWidth),
                                        song = song,
                                        thumbnailSize = Dimensions.thumbnails.song,
                                        trailingContent = {
                                            Image(
                                                painter = painterResource(R.drawable.star),
                                                contentDescription = null,
                                                colorFilter = ColorFilter.tint(colorPalette.accent),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        showDuration = false,
                                        isPlaying = playing && currentMediaId == song.id
                                    )
                                }
                            }

                            items(
                                items = gridSongs,
                                key = Innertube.SongItem::key,
                                contentType = { "song" }
                            ) { song ->
                                SongItem(
                                    song = song,
                                    thumbnailSize = Dimensions.thumbnails.song,
                                    modifier = Modifier
                                        .combinedClickable(
                                            onLongClick = {
                                                menuState.display {
                                                    NonQueuedMediaItemMenu(
                                                        onDismiss = menuState::hide,
                                                        mediaItem = song.asMediaItem
                                                    )
                                                }
                                            },
                                            onClick = { playSong(song.asMediaItem) }
                                        )
                                        .width(itemInHorizontalGridWidth),
                                    showDuration = false,
                                    isPlaying = playing && currentMediaId == song.key
                                )
                            }
                        }
                    }

                    // Fresh-install categories live on the Discover tab, not here.
                    related.albums?.takeIf(List<Innertube.AlbumItem>::isNotEmpty)?.let { albums ->
                        item(key = "albums_section") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                BasicText(
                                    text = stringResource(R.string.related_albums),
                                    style = typography.m.semiBold,
                                    modifier = sectionTextModifier
                                )

                                LazyRow(
                                    state = albumsRowState,
                                    contentPadding = endPaddingValues
                                ) {
                                    items(
                                        items = albums,
                                        key = Innertube.AlbumItem::key,
                                        contentType = { "album" }
                                    ) { album ->
                                        AlbumItem(
                                            album = album,
                                            thumbnailSize = Dimensions.thumbnails.album,
                                            alternative = true,
                                            modifier = Modifier.clickable { onAlbumClick(album) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    related.artists?.takeIf(List<Innertube.ArtistItem>::isNotEmpty)?.let { artists ->
                        item(key = "artists_section") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                BasicText(
                                    text = stringResource(R.string.similar_artists),
                                    style = typography.m.semiBold,
                                    modifier = sectionTextModifier
                                )

                                LazyRow(
                                    state = artistsRowState,
                                    contentPadding = endPaddingValues
                                ) {
                                    items(
                                        items = artists,
                                        key = Innertube.ArtistItem::key,
                                        contentType = { "artist" }
                                    ) { artist ->
                                        ArtistItem(
                                            artist = artist,
                                            thumbnailSize = Dimensions.thumbnails.artist,
                                            alternative = true,
                                            modifier = Modifier.clickable { onArtistClick(artist) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    related.playlists?.takeIf(List<Innertube.PlaylistItem>::isNotEmpty)?.let { playlists ->
                        item(key = "playlists_section") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                BasicText(
                                    text = stringResource(R.string.recommended_playlists),
                                    style = typography.m.semiBold,
                                    modifier = sectionTextModifier
                                )

                                LazyRow(
                                    state = playlistsRowState,
                                    contentPadding = endPaddingValues
                                ) {
                                    items(
                                        items = playlists,
                                        key = Innertube.PlaylistItem::key,
                                        contentType = { "playlist" }
                                    ) { playlist ->
                                        PlaylistItem(
                                            playlist = playlist,
                                            thumbnailSize = Dimensions.thumbnails.playlist,
                                            alternative = true,
                                            modifier = Modifier.clickable { onPlaylistClick(playlist) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Post-listening recommendations, shown only for the seed the
                    // current feed was built from so sections never disagree
                    // with the grid above.
                    sections?.takeIf { it.seedId == lastSeedId && lastSeedId != null }?.let { shown ->
                        shown.because?.takeIf { it.songs.isNotEmpty() }?.let { because ->
                            item(key = "because_title") {
                                BasicText(
                                    text = because.seedTitles.firstOrNull()?.let {
                                        stringResource(R.string.because_you_listened_to, it)
                                    } ?: stringResource(R.string.because_you_listened),
                                    style = typography.m.semiBold,
                                    modifier = sectionTextModifier
                                )
                            }

                            items(
                                items = because.songs,
                                key = Innertube.SongItem::key,
                                contentType = { "song" }
                            ) { song ->
                                SongItem(
                                    song = song,
                                    thumbnailSize = Dimensions.thumbnails.song,
                                    modifier = Modifier.combinedClickable(
                                        onLongClick = {
                                            menuState.display {
                                                NonQueuedMediaItemMenu(
                                                    onDismiss = menuState::hide,
                                                    mediaItem = song.asMediaItem
                                                )
                                            }
                                        },
                                        onClick = { playSong(song.asMediaItem) }
                                    ),
                                    showDuration = false,
                                    isPlaying = playing && currentMediaId == song.key
                                )
                            }
                        }

                        if (shown.keep.isNotEmpty()) {
                            item(key = "keep_title") {
                                BasicText(
                                    text = stringResource(R.string.keep_listening),
                                    style = typography.m.semiBold,
                                    modifier = sectionTextModifier
                                )
                            }

                            items(
                                items = shown.keep,
                                key = { it.id },
                                contentType = { "song" }
                            ) { song ->
                                SongItem(
                                    song = song,
                                    thumbnailSize = Dimensions.thumbnails.song,
                                    modifier = Modifier.combinedClickable(
                                        onLongClick = {
                                            menuState.display {
                                                NonQueuedMediaItemMenu(
                                                    onDismiss = menuState::hide,
                                                    mediaItem = song.asMediaItem
                                                )
                                            }
                                        },
                                        onClick = { playSong(song.asMediaItem) }
                                    ),
                                    showDuration = false,
                                    isPlaying = playing && currentMediaId == song.id
                                )
                            }
                        }

                        if (shown.forgotten.isNotEmpty()) {
                            item(key = "forgotten_title") {
                                BasicText(
                                    text = stringResource(R.string.forgotten_favorites),
                                    style = typography.m.semiBold,
                                    modifier = sectionTextModifier
                                )
                            }

                            items(
                                items = shown.forgotten,
                                key = { it.id },
                                contentType = { "song" }
                            ) { song ->
                                SongItem(
                                    song = song,
                                    thumbnailSize = Dimensions.thumbnails.song,
                                    modifier = Modifier.combinedClickable(
                                        onLongClick = {
                                            menuState.display {
                                                NonQueuedMediaItemMenu(
                                                    onDismiss = menuState::hide,
                                                    mediaItem = song.asMediaItem
                                                )
                                            }
                                        },
                                        onClick = { playSong(song.asMediaItem) }
                                    ),
                                    showDuration = false,
                                    isPlaying = playing && currentMediaId == song.id
                                )
                            }
                        }
                    }

                    // Loaded but every section came back empty (failed fetch that
                    // resolved to the explicit empty page): say so and offer a
                    // retry instead of a blank screen.
                    if (related.songs.isNullOrEmpty() && related.albums.isNullOrEmpty() &&
                        related.artists.isNullOrEmpty() && related.playlists.isNullOrEmpty()
                    ) {
                        item(key = "empty") {
                            BasicText(
                                text = stringResource(R.string.no_items_found),
                                style = typography.s.secondary.center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { scope.launch { retryRefresh() } }
                                    .padding(all = 24.dp)
                            )
                        }
                    }

                } ?: relatedPageResult?.exceptionOrNull()?.let {
                    item(key = "error") {
                        BasicText(
                            text = stringResource(R.string.error_message),
                            style = typography.s.secondary.center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { scope.launch { retryRefresh() } }
                                .padding(all = 16.dp)
                        )
                    }
                } ?: item(key = "shimmer") {
                    ShimmerHost {
                        repeat(4) {
                            SongItemPlaceholder(thumbnailSize = Dimensions.thumbnails.song)
                        }

                        TextPlaceholder(modifier = sectionTextModifier)

                        Row {
                            repeat(2) {
                                AlbumItemPlaceholder(
                                    thumbnailSize = Dimensions.thumbnails.album,
                                    alternative = true
                                )
                            }
                        }

                        TextPlaceholder(modifier = sectionTextModifier)

                        Row {
                            repeat(2) {
                                ArtistItemPlaceholder(
                                    thumbnailSize = Dimensions.thumbnails.album,
                                    alternative = true
                                )
                            }
                        }

                        TextPlaceholder(modifier = sectionTextModifier)

                        Row {
                            repeat(2) {
                                PlaylistItemPlaceholder(
                                    thumbnailSize = Dimensions.thumbnails.album,
                                    alternative = true
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionsContainerWithScrollToTop(
            lazyListState = lazyListState,
            icon = null
        )
    }
}
