package app.pulse.android.ui.screens.player

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import app.pulse.android.Database
import app.pulse.android.LocalPlayerServiceBinder
import app.pulse.android.R
import app.pulse.android.models.Lyrics
import app.pulse.android.preferences.PlayerPreferences
import app.pulse.android.query
import app.pulse.android.service.LOCAL_KEY_PREFIX
import app.pulse.android.transaction
import app.pulse.android.ui.components.LocalMenuState
import app.pulse.android.ui.components.themed.CircularProgressIndicator
import app.pulse.android.ui.components.themed.DefaultDialog
import app.pulse.android.ui.components.themed.Menu
import app.pulse.android.ui.components.themed.MenuEntry
import app.pulse.android.ui.components.themed.TextField
import app.pulse.android.ui.components.themed.TextFieldDialog
import app.pulse.android.ui.components.themed.TextPlaceholder
import app.pulse.android.ui.components.themed.ValueSelectorDialogBody
import app.pulse.android.ui.modifiers.verticalFadingEdge
import app.pulse.android.utils.SynchronizedLyrics
import app.pulse.android.utils.SynchronizedLyricsState
import app.pulse.android.utils.bold
import app.pulse.android.utils.center
import app.pulse.android.utils.color
import app.pulse.android.utils.medium
import app.pulse.android.utils.semiBold
import app.pulse.android.utils.toast
import app.pulse.android.utils.LyricsRomanizer
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.onOverlay
import app.pulse.core.ui.onOverlayShimmer
import app.pulse.core.ui.overlay
import app.pulse.core.ui.utils.dp
import app.pulse.android.betterlyrics.BetterLyrics
import app.pulse.providers.innertube.Innertube
import app.pulse.providers.innertube.models.bodies.NextBody
import app.pulse.providers.innertube.requests.lyrics
import app.pulse.providers.kugou.KuGou
import app.pulse.providers.lrclib.LrcLib
import app.pulse.providers.lrclib.LrcParser
import app.pulse.providers.lrclib.models.Track
import app.pulse.providers.lrclib.toLrcFile
import com.valentinilk.shimmer.shimmer
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val UPDATE_DELAY = 100L
private const val LYRICS_OFFSET_MS = 1000L

private const val ACTIVE_LINE_SCALE = 1.05f

/**
 * One synced-lyric row. Inputs are tiered (active / adjacent / near / far)
 * instead of exact distance so rows keep equal inputs across ticks and
 * Compose skips them: only rows crossing a tier boundary recompose at 10Hz.
 * Distant rows render statically with no layer transform; only the active
 * line and its neighbors run springs.
 */
@Composable
private fun LyricLine(
    sentence: String,
    active: Boolean,
    tier: Int,
    seekPositionMs: Long,
    lyricsFontSize: TextUnit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    val targetAlpha = when (tier) {
        0 -> 1f
        1 -> 0.52f
        2 -> 0.24f
        else -> 0.10f
    }
    val colorState by animateColorAsState(
        targetValue = if (active) Color.White else colorPalette.text,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "lyricLineColor"
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "lyricLineAlpha"
    )
    val color = colorState.copy(alpha = alpha)
    val scale by animateFloatAsState(
        targetValue = if (active) ACTIVE_LINE_SCALE else 0.98f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "lyricLineScale"
    )

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .then(
                if (tier <= 1) Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                } else Modifier
            )
            .padding(vertical = 12.dp, horizontal = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onSeek(seekPositionMs) }
            )
    ) {
        if (sentence.isBlank()) Image(
            painter = painterResource(R.drawable.musical_notes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier
                .padding(vertical = 2.dp)
                .size(lyricsFontSize.value.dp)
        ) else BasicText(
            text = sentence,
            style = if (active) typography.m.bold.copy(fontSize = lyricsFontSize).color(color)
            else typography.m.semiBold.copy(fontSize = lyricsFontSize).color(color)
        )
    }
}

// Center a lyric line using measured layout metrics (ported from ArchiveTune):
// read the item's real offset and size from layoutInfo and scroll BY the delta
// to the viewport center, so centering is exact at every font size. When the
// item is not composed yet (seek/initial), jump to its vicinity first; the next
// tick's measured branch then centers it precisely. `animated` glides a line
// change to center; drift corrections stay instant so the beat is never lost.
private suspend fun LazyListState.centerActiveItem(
    targetIndex: Int,
    animated: Boolean = false,
    seek: Boolean = false
) {
    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (itemInfo != null) {
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val center = layoutInfo.viewportStartOffset + (viewportHeight * 0.35f).toInt()
        val delta = itemInfo.offset + itemInfo.size / 2 - center
        if (abs(delta) > 5) {

            if (animated) animateScrollBy(delta.toFloat(), if (seek) spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            ) else spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            ))
            else scrollBy(delta.toFloat())
        }
    } else {
        val distance = abs(targetIndex - firstVisibleItemIndex)
        if (distance > 15) scrollToItem(targetIndex)
        else animateScrollToItem(targetIndex, 0)
    }
}

internal data class FetchedLyrics(
    val fixed: String?,
    val synced: String?,
    val fixedRomanized: String?,
    val syncedRomanized: String?
) {
    val hasContent: Boolean
        get() = !fixed.isNullOrBlank() || !synced.isNullOrBlank()
}

private data class SyncedResult(
    val synced: String,
    val romanizedSynced: String?,
    val plain: String? = null,
    val romanizedPlain: String? = null
)

private fun Lyrics?.isNullOrNoContent(): Boolean =
    this == null || (fixed.isNullOrBlank() && synced.isNullOrBlank())

/**
 * Backfills romanized variants for lyrics cached before romanization existed
 * (and for provider payloads that shipped only the original script). Local
 * transliteration keeps timestamps because [LyricsRomanizer.romanizeLrc]
 * preserves them.
 */
internal fun Lyrics.withRomanizedVariants(): Lyrics {
    val newFixed = fixedRomanized ?: fixed?.let(LyricsRomanizer::romanize)
    val newSynced = syncedRomanized ?: synced?.let(LyricsRomanizer::romanizeLrc)
    return if (newFixed == fixedRomanized && newSynced == syncedRomanized) this
    else copy(fixedRomanized = newFixed, syncedRomanized = newSynced)
}

private fun String?.plainOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

private fun String?.syncedOrNull(): String? = this?.trim()
    ?.takeIf { it.isNotBlank() && LyricsRomanizer.isSynced(it) }

private suspend fun fetchBetter(
    title: String,
    artist: String,
    durationSec: Int?,
    album: String?
): BetterLyrics.ParsedLyrics? = BetterLyrics.getParsedLyrics(
    title = title,
    artist = artist,
    duration = durationSec ?: -1,
    album = album
)

/**
 * Synchronized lyrics preference chain: Better Lyrics (word-level TTML and
 * provider romanization) -> LRCLIB (large free line-synced database) -> KuGou
 * (broad Asian catalogue). Plain lyrics keep the current YouTube/Innertube
 * source, then LRCLIB, and are derived from synced lyrics when a synchronized
 * provider is the only one that has the song.
 *
 * Every step is bounded by the provider HTTP timeouts, so a dead provider
 * cannot hold the lyrics screen hostage.
 */
private suspend fun fetchSynced(
    artist: String,
    title: String,
    altTitle: String,
    album: String?,
    durationSec: Int?,
    duration: Long
): SyncedResult? {
    // Nothing to query; better to fall through to an empty state than to
    // spend 8s per provider on empty parameters.
    if (title.isBlank() && artist.isBlank()) return null

    // 1. Better Lyrics — the preferred synchronized provider.
    val better = fetchBetter(title, artist, durationSec, album)
        ?: if (altTitle != title) fetchBetter(altTitle, artist, durationSec, album) else null
    if (better != null) return SyncedResult(
        synced = better.synced,
        romanizedSynced = better.romanizedSynced,
        plain = better.plain,
        romanizedPlain = better.romanizedPlain
    )

    // 2. LRCLIB — reliable free fallback; retry with the shortened title.
    val lrcLib = LrcLib.bestLyrics(
        artist = artist,
        title = title,
        duration = duration.milliseconds,
        album = album
    )?.getOrNull()?.text.syncedOrNull()
        ?: if (altTitle != title) LrcLib.bestLyrics(
            artist = artist,
            title = altTitle,
            duration = duration.milliseconds,
            album = album
        )?.getOrNull()?.text.syncedOrNull() else null
    if (lrcLib != null) return SyncedResult(
        synced = lrcLib,
        romanizedSynced = LyricsRomanizer.romanizeLrc(lrcLib)
    )

    // 3. KuGou — broad Asian catalogue fallback.
    val kugou = KuGou.lyrics(
        artist = artist,
        title = title,
        duration = duration / 1000
    )?.getOrNull()?.value.syncedOrNull()
    if (kugou != null) return SyncedResult(
        synced = kugou,
        romanizedSynced = LyricsRomanizer.romanizeLrc(kugou)
    )

    return null
}

private suspend fun fetchPlain(
    mediaId: String,
    artist: String,
    title: String,
    altTitle: String,
    album: String?,
    duration: Long
): String? = Innertube.lyrics(NextBody(videoId = mediaId))
    ?.getOrNull()
    .plainOrNull()
    ?: LrcLib.bestLyrics(
        artist = artist,
        title = title,
        duration = duration.milliseconds,
        album = album,
        synced = false
    )?.getOrNull()?.text.plainOrNull()
    ?: if (altTitle != title) LrcLib.bestLyrics(
        artist = artist,
        title = altTitle,
        duration = duration.milliseconds,
        album = album,
        synced = false
    )?.getOrNull()?.text.plainOrNull() else null

internal suspend fun fetchLyricsParallel(
    mediaId: String,
    artist: String,
    title: String,
    album: String?,
    duration: Long,
    currentFixed: String?,
    currentSynced: String?
): FetchedLyrics = coroutineScope {
    val durationSec = (duration / 1000).toInt().takeIf { it > 0 }
    val altTitle = title.substringBefore('(').trim()

    val existingFixed = currentFixed.plainOrNull()
    val existingSynced = currentSynced.syncedOrNull()

    // Plain lyrics are independent of the synced chain, so fetch them in
    // parallel. When a cached synced copy exists they can be derived locally.
    val plainDef = async {
        existingFixed ?: if (existingSynced != null) null else fetchPlain(
            mediaId = mediaId,
            artist = artist,
            title = title,
            altTitle = altTitle,
            album = album,
            duration = duration
        )
    }
    val syncedDef = async {
        if (existingSynced != null) null else fetchSynced(
            artist = artist,
            title = title,
            altTitle = altTitle,
            album = album,
            durationSec = durationSec,
            duration = duration
        )
    }

    val syncedResult = syncedDef.await()
    val synced = existingSynced ?: syncedResult?.synced
    val providerPlain = existingFixed ?: syncedResult?.plain?.plainOrNull()
    // Do not leave the Innertube plain request running (and delaying the
    // result) when the synchronized provider already supplied plain lyrics.
    if (providerPlain != null) plainDef.cancel()
    // A failed plain fetch must never sink an already-resolved synced side:
    // CancellationException (our own cancel above) always propagates.
    val plainResult = try {
        plainDef.await()?.plainOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    val fixed = providerPlain
        ?: plainResult
        ?: synced?.let(LyricsRomanizer::lrcToPlainText)

    FetchedLyrics(
        fixed = fixed,
        synced = synced,
        fixedRomanized = when {
            fixed == null -> null
            fixed == syncedResult?.plain?.plainOrNull() -> syncedResult.romanizedPlain
            else -> LyricsRomanizer.romanize(fixed)
        },
        syncedRomanized = when {
            existingSynced != null -> LyricsRomanizer.romanizeLrc(existingSynced)
            syncedResult?.romanizedSynced != null -> syncedResult.romanizedSynced
            synced != null -> LyricsRomanizer.romanizeLrc(synced)
            else -> null
        }
    )
}

@Composable
fun Lyrics(
    mediaId: String,
    isDisplayed: Boolean,
    onDismiss: () -> Unit,
    mediaMetadataProvider: () -> MediaMetadata,
    durationProvider: () -> Long,
    ensureSongInserted: () -> Unit,
    modifier: Modifier = Modifier,
    onMenuLaunch: () -> Unit = { },
    onOpenDialog: (() -> Unit)? = null,
    shouldShowSynchronizedLyrics: Boolean = PlayerPreferences.isShowingSynchronizedLyrics,
    setShouldShowSynchronizedLyrics: (Boolean) -> Unit = {
        PlayerPreferences.isShowingSynchronizedLyrics = it
    },
    shouldKeepScreenAwake: Boolean = PlayerPreferences.lyricsKeepScreenAwake,
    shouldUpdateLyrics: Boolean = true,
    showControls: Boolean = true,
    lazyListState: LazyListState = rememberLazyListState()
) {
    val currentEnsureSongInserted by rememberUpdatedState(ensureSongInserted)
    val currentMediaMetadataProvider by rememberUpdatedState(mediaMetadataProvider)
    val currentDurationProvider by rememberUpdatedState(durationProvider)

    val (colorPalette, typography) = LocalAppearance.current
    val lyricsFontSize = PlayerPreferences.lyricsFontSize.sp
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val binder = LocalPlayerServiceBinder.current
    val view = LocalView.current

    var lyrics by remember(mediaId) { mutableStateOf<Lyrics?>(LyricsCache[mediaId]) }
    var isLoading by remember(mediaId) { mutableStateOf(LyricsCache[mediaId] == null) }

    val romanize = PlayerPreferences.romanizeLyrics
    val syncedSource = remember(lyrics, romanize) {
        lyrics?.synced?.takeIf { it.isNotBlank() }?.let { original ->
            if (romanize) lyrics?.syncedRomanized?.takeIf { it.isNotBlank() } ?: original
            else original
        }
    }
    val plainSource = remember(lyrics, romanize) {
        lyrics?.fixed?.takeIf { it.isNotBlank() }?.let { original ->
            if (romanize) lyrics?.fixedRomanized?.takeIf { it.isNotBlank() } ?: original
            else original
        }
    }
    val syncedFile = remember(syncedSource) {
        syncedSource?.let { source ->
            runCatching { LrcParser.parse(source)?.toLrcFile() }
                .getOrNull()
                ?.takeIf { file -> file.lines.values.any { it.isNotBlank() } }
        }
    }
    val hasSynced = syncedFile != null
    val hasPlain = !plainSource.isNullOrBlank()
    // Never show an empty surface: when the user prefers plain lyrics but the
    // provider only had synchronized ones, synchronized lyrics are the better
    // fallback (and are never allowed to dismiss the screen).
    val showSynchronizedLyrics = hasSynced && (shouldShowSynchronizedLyrics || !hasPlain)

    val nothingAvailable = !isLoading && !hasPlain && !hasSynced

    // No lyrics at all: briefly show the empty message, then return to the
    // player surface instead of leaving the user on a blank lyrics screen.
    LaunchedEffect(nothingAvailable) {
        if (nothingAvailable) {
            delay(2000)
            onDismiss()
        }
    }
    val canRomanize = !lyrics?.syncedRomanized.isNullOrBlank() ||
        !lyrics?.fixedRomanized.isNullOrBlank()

    var editing by remember(mediaId) { mutableStateOf(false) }
    var picking by remember(mediaId) { mutableStateOf(false) }

    DisposableEffect(shouldKeepScreenAwake) {
        view.keepScreenOn = shouldKeepScreenAwake

        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(mediaId) {
        LyricsCache[mediaId]?.let { cached ->
            if (cached.fixed != null || cached.synced != null) {
                // ICU transliteration init is expensive: off the main thread.
                val enriched = withContext(Dispatchers.IO) { cached.withRomanizedVariants() }
                lyrics = enriched
                if (enriched != cached) LyricsCache[mediaId] = enriched
                isLoading = false
            }
        }

        runCatching {
            withContext(Dispatchers.IO) {
                Database
                    .lyrics(mediaId)
                    .distinctUntilChanged()
                    .cancellable()
                    .collect { currentLyrics ->
                        // All-blank rows (legacy failed attempts persisted as
                        // "") count as NOT attempted so a transient failure
                        // never permanently blocks retries.
                        val alreadyAttempted =
                            currentLyrics?.let { !(it.fixed.isNullOrBlank() && it.synced.isNullOrBlank()) } == true

                        if (!shouldUpdateLyrics || alreadyAttempted) {
                            val enriched = currentLyrics?.withRomanizedVariants()
                            lyrics = enriched
                            if (currentLyrics != null && enriched != null && enriched != currentLyrics) {
                                LyricsCache[mediaId] = enriched
                                transaction { runCatching { Database.upsert(enriched) } }
                            }
                            isLoading = false
                            return@collect
                        }

                        if (currentLyrics.isNullOrNoContent()) isLoading = true

                        val mediaMetadata = currentMediaMetadataProvider()
                        var duration =
                            withContext(Dispatchers.Main) { currentDurationProvider() }
                        var waited = 0L

                        // A missing duration (live/local edge cases) must never
                        // block the fetch forever; providers simply get no hint.
                        while (duration == C.TIME_UNSET && waited < 3_000L) {
                            delay(100)
                            waited += 100
                            duration =
                                withContext(Dispatchers.Main) { currentDurationProvider() }
                        }

                        val album = mediaMetadata.albumTitle?.toString()
                        val artist = mediaMetadata.artist?.toString().orEmpty()
                        val title = mediaMetadata.title?.toString().orEmpty().let {
                            if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it
                                .substringBeforeLast('.')
                                .trim()
                            else it
                        }

                        val existing = currentLyrics
                        val fetched = fetchLyricsParallel(
                            mediaId = mediaId,
                            artist = artist,
                            title = title,
                            album = album,
                            duration = duration,
                            currentFixed = existing?.fixed?.takeIf { it.isNotBlank() },
                            currentSynced = existing?.synced?.takeIf { it.isNotBlank() }
                        )

                        ensureActive()

                        if (fetched.hasContent) {
                            val updated = Lyrics(
                                songId = mediaId,
                                fixed = fetched.fixed.orEmpty(),
                                synced = fetched.synced.orEmpty(),
                                startTime = currentLyrics?.startTime,
                                fixedRomanized = fetched.fixedRomanized,
                                syncedRomanized = fetched.syncedRomanized
                            )

                            LyricsCache[mediaId] = updated
                            lyrics = updated
                            isLoading = false

                            transaction {
                                runCatching {
                                    currentEnsureSongInserted()
                                    Database.upsert(updated)
                                }
                            }
                        } else {
                            // A fetch that found nothing must not erase lyrics
                            // the user already had — nor be persisted as an
                            // empty row, which would block every future retry.
                            lyrics = currentLyrics
                            isLoading = false
                        }
                    }
            }
        }.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            isLoading = false
            it.printStackTrace()
        }
    }

    AnimatedVisibility(
        visible = isDisplayed,
        enter = fadeIn(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
    ) {
        if (editing) TextFieldDialog(
        hintText = stringResource(R.string.enter_lyrics),
        initialTextInput = (if (showSynchronizedLyrics) syncedSource else plainSource).orEmpty(),
        singleLine = false,
        maxLines = 10,
        isTextInputValid = { true },
        onDismiss = { editing = false },
        onAccept = {
            transaction {
                runCatching {
                    currentEnsureSongInserted()

                    Database.upsert(
                        if (showSynchronizedLyrics) Lyrics(
                            songId = mediaId,
                            fixed = lyrics?.fixed,
                            synced = it,
                            startTime = lyrics?.startTime,
                            fixedRomanized = lyrics?.fixedRomanized,
                            syncedRomanized = null
                        ) else Lyrics(
                            songId = mediaId,
                            fixed = it,
                            synced = lyrics?.synced,
                            startTime = lyrics?.startTime,
                            fixedRomanized = null,
                            syncedRomanized = lyrics?.syncedRomanized
                        )
                    )
                }
            }
        }
    )

    if (picking && shouldShowSynchronizedLyrics) {
        var query by rememberSaveable {
            mutableStateOf(
                currentMediaMetadataProvider().title?.toString().orEmpty().let {
                    if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it
                        .substringBeforeLast('.')
                        .trim()
                    else it
                }
            )
        }

        LrcLibSearchDialog(
            query = query,
            setQuery = { query = it },
            onDismiss = { picking = false },
            onPick = {
                runCatching {
                    transaction {
                        Database.upsert(
                            Lyrics(
                                songId = mediaId,
                                fixed = lyrics?.fixed,
                                synced = it.syncedLyrics,
                                startTime = lyrics?.startTime,
                                fixedRomanized = lyrics?.fixedRomanized,
                                syncedRomanized = LyricsRomanizer.romanizeLrc(
                                    it.syncedLyrics.orEmpty()
                                )
                            )
                        )
                    }
                }
            }
        )
    }

    BoxWithConstraints(
        contentAlignment = Alignment.TopStart,
        modifier = modifier.fillMaxSize()
    ) {

        val lyricsState = rememberSaveable(syncedSource, showSynchronizedLyrics) {
            SynchronizedLyricsState(
                sentences = if (showSynchronizedLyrics) syncedFile.lines else null,
                offset = if (showSynchronizedLyrics) {
                    syncedFile.offset?.inWholeMilliseconds ?: 0L
                } else 0L
            )
        }

        val synchronizedLyrics = remember(lyricsState, mediaId) {
            lyricsState.sentences?.let {
                SynchronizedLyrics(it.toImmutableMap()) {
                    binder?.player?.let { player ->
                        // During crossfade, player.currentMediaItem still points
                        // to old song — return 0 so lyrics stay at top.
                        if (player.currentMediaItem?.mediaId != mediaId) 0L
                        else player.currentPosition + lyricsState.offset -
                            (lyrics?.startTime ?: 0L) + LYRICS_OFFSET_MS
                    } ?: 0L
                }
            }
        }

        AnimatedContent(
            targetState = showSynchronizedLyrics,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentAlignment = Alignment.TopStart,
            label = ""
        ) { synchronized ->
            if (synchronized) {

                var autoFollowPausedUntil by remember { mutableStateOf(0L) }

                // Stable seek callback: recreated only if the binder changes,
                // so lyric rows keep equal inputs and skip recomposition.
                val onSeekLine = remember(binder) {
                    { positionMs: Long ->
                        binder?.player?.seekTo(positionMs)
                        Unit
                    }
                }

                val manualScrollConnection = remember {
                    var lastScrollTime = 0L
                    object : NestedScrollConnection {
                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset {
                            if (source == NestedScrollSource.UserInput) {
                                val now = System.currentTimeMillis()
                                if (now - lastScrollTime > 50) {
                                    autoFollowPausedUntil =
                                        now + 2.seconds.inWholeMilliseconds
                                    lastScrollTime = now
                                }
                            }
                            return super.onPostScroll(consumed, available, source)
                        }

                        override suspend fun onPostFling(
                            consumed: Velocity,
                            available: Velocity
                        ): Velocity {
                            autoFollowPausedUntil =
                                System.currentTimeMillis() + 2.seconds.inWholeMilliseconds
                            return super.onPostFling(consumed, available)
                        }
                    }
                }

                // reset scroll on song change
                LaunchedEffect(mediaId) {
                    lazyListState.scrollToItem(0)
                }

                LaunchedEffect(synchronizedLyrics, lyricsFontSize) {
                    val currentSynchronizedLyrics = synchronizedLyrics ?: return@LaunchedEffect

                    // Wait for the first layout: before items exist the helper's
                    // fallback animates a jump, which the 50ms tick then snaps to
                    // center - the visible open/close bounce. Centering only after
                    // layout lands dead-center without any animation.
                    while (lazyListState.layoutInfo.visibleItemsInfo.isEmpty()) {
                        delay(UPDATE_DELAY)
                    }

                    val initIdx = currentSynchronizedLyrics.index + 1
                    lazyListState.centerActiveItem(initIdx)

                    var lastIndex = initIdx
                    while (true) {
                        delay(UPDATE_DELAY)
                        currentSynchronizedLyrics.update()
                        if (autoFollowPausedUntil > System.currentTimeMillis()) continue
                        if (lazyListState.isScrollInProgress) continue

                        val targetIndex = currentSynchronizedLyrics.index + 1
                        lazyListState.centerActiveItem(
                            targetIndex,
                            animated = targetIndex != lastIndex,
                            seek = abs(targetIndex - lastIndex) > 1
                        )
                        lastIndex = targetIndex
                    }
                }

                // Cache the sentence list outside LazyListScope (remember is @Composable,
                // it cannot be called inside LazyListScope which is not @Composable).
                val sentenceList = remember(synchronizedLyrics) {
                    synchronizedLyrics?.sentences?.values?.toImmutableList()
                }

                if (synchronizedLyrics != null && sentenceList != null) LazyColumn(
                    state = lazyListState,
                    userScrollEnabled = true,
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Top,
                    modifier = Modifier
                        .verticalFadingEdge(topSize = 10, bottomSize = 2)
                        .nestedScroll(manualScrollConnection)
                        .fillMaxWidth()
                ) {
                    item(key = "header", contentType = 0) {
                        Spacer(modifier = Modifier.height(maxHeight / 2))
                    }
                    itemsIndexed(
                        items = sentenceList,
                        key = { index, _ -> index }
                    ) { index, sentence ->
                        // Tier buckets keep far rows' inputs stable across ticks
                        // so Compose skips them: only rows crossing a tier
                        // boundary (or toggling active) recompose at 10Hz.
                        val active = index == synchronizedLyrics.index
                        val tier = when (abs(index - synchronizedLyrics.index)) {
                            0 -> 0
                            1 -> 1
                            2, 3 -> 2
                            else -> 3
                        }
                        val seekPositionMs = synchronizedLyrics.sentences.keys.elementAt(index)
                        LyricLine(
                            sentence = sentence,
                            active = active,
                            tier = tier,
                            seekPositionMs = seekPositionMs,
                            lyricsFontSize = lyricsFontSize,
                            onSeek = onSeekLine
                        )
                    }
                    item(key = "footer", contentType = 0) {
                        Spacer(modifier = Modifier.height(maxHeight / 2))
                    }
                }
            } else BasicText(
                text = plainSource.orEmpty(),
                style = typography.m.semiBold.copy(fontSize = lyricsFontSize).color(colorPalette.text.copy(alpha = 0.8f)),
                modifier = Modifier
                    .verticalFadingEdge(topSize = 4, bottomSize = 0)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .padding(
                        start = 48.dp,
                        top = 32.dp,
                        end = 48.dp,
                        bottom = 32.dp
                    )
            )
        }

        if (isLoading && !hasPlain && !hasSynced) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .shimmer()
        ) {
            repeat(4) {
                TextPlaceholder(
                    color = colorPalette.onOverlayShimmer,
                    modifier = Modifier.alpha(1f - it * 0.2f)
                )
            }
        }

        if (nothingAvailable) LyricsEmptyState(
            modifier = Modifier.align(Alignment.Center)
        )

        if (showControls) {
            if (onOpenDialog != null) Image(
                painter = painterResource(R.drawable.expand),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.onOverlay),
                modifier = Modifier
                    .padding(all = 4.dp)
                    .clickable(
                        indication = ripple(bounded = false),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            onOpenDialog()
                        }
                    )
                    .padding(all = 8.dp)
                    .size(20.dp)
                    .align(Alignment.BottomStart)
            )

            Image(
                painter = painterResource(R.drawable.ellipsis_horizontal),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.onOverlay),
                modifier = Modifier
                    .padding(all = 4.dp)
                    .clickable(
                        indication = ripple(bounded = false),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            onMenuLaunch()
                            menuState.display {
                                Menu {
                                    if (hasSynced && hasPlain) MenuEntry(
                                        icon = R.drawable.time,
                                        text = stringResource(
                                            if (shouldShowSynchronizedLyrics) R.string.show_unsynchronized_lyrics
                                            else R.string.show_synchronized_lyrics
                                        ),
                                        secondaryText = if (shouldShowSynchronizedLyrics) null
                                        else stringResource(R.string.provided_lyrics_by),
                                        onClick = {
                                            menuState.hide()
                                            setShouldShowSynchronizedLyrics(!shouldShowSynchronizedLyrics)
                                        }
                                    )

                                    if (canRomanize) MenuEntry(
                                        icon = R.drawable.translate,
                                        text = stringResource(
                                            if (romanize) R.string.show_original_lyrics
                                            else R.string.romanize_lyrics
                                        ),
                                        secondaryText = if (romanize) null
                                        else stringResource(R.string.romanize_lyrics_description),
                                        onClick = {
                                            menuState.hide()
                                            PlayerPreferences.romanizeLyrics = !romanize
                                        }
                                    )

                                    MenuEntry(
                                        icon = R.drawable.pencil,
                                        text = stringResource(R.string.edit_lyrics),
                                        onClick = {
                                            menuState.hide()
                                            editing = true
                                        }
                                    )

                                    val errorMsg = stringResource(R.string.no_browser_installed)
                                    MenuEntry(
                                        icon = R.drawable.search,
                                        text = stringResource(R.string.search_lyrics_online),
                                        onClick = {
                                            menuState.hide()
                                            val mediaMetadata = currentMediaMetadataProvider()

                                            try {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                                                        putExtra(
                                                            SearchManager.QUERY,
                                                            "${mediaMetadata.title} ${mediaMetadata.artist} lyrics"
                                                        )
                                                    }
                                                )
                                            } catch (_: ActivityNotFoundException) {
                                                context.toast(errorMsg)
                                            }
                                        }
                                    )

                                    MenuEntry(
                                        icon = R.drawable.sync,
                                        text = stringResource(R.string.refetch_lyrics),
                                        enabled = lyrics != null,
                                        onClick = {
                                            menuState.hide()

                                            transaction {
                                                runCatching {
                                                    currentEnsureSongInserted()

                                                    Database.upsert(
                                                        if (shouldShowSynchronizedLyrics) Lyrics(
                                                            songId = mediaId,
                                                            fixed = lyrics?.fixed,
                                                            synced = null,
                                                            startTime = lyrics?.startTime,
                                                            fixedRomanized = lyrics?.fixedRomanized
                                                        ) else Lyrics(
                                                            songId = mediaId,
                                                            fixed = null,
                                                            synced = lyrics?.synced,
                                                            startTime = lyrics?.startTime,
                                                            syncedRomanized = lyrics?.syncedRomanized
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    )

                                    if (shouldShowSynchronizedLyrics) {
                                        MenuEntry(
                                            icon = R.drawable.download,
                                            text = stringResource(R.string.pick_from_lrclib),
                                            onClick = {
                                                menuState.hide()
                                                picking = true
                                            }
                                        )
                                        MenuEntry(
                                            icon = R.drawable.play_skip_forward,
                                            text = stringResource(R.string.set_lyrics_start_offset),
                                            secondaryText = stringResource(
                                                R.string.set_lyrics_start_offset_description
                                            ),
                                            onClick = {
                                                menuState.hide()
                                                lyrics?.let {
                                                    val startTime = binder?.player?.currentPosition
                                                    query {
                                                        Database.upsert(it.copy(startTime = startTime))
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                    .padding(all = 8.dp)
                    .size(20.dp)
                    .align(Alignment.BottomEnd)
            )
        }
    }
    }
}

@Composable
private fun LyricsEmptyState(
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    BasicText(
        text = stringResource(R.string.lyrics_not_available),
        style = typography.m.medium.center.copy(color = colorPalette.textSecondary),
        modifier = modifier.padding(horizontal = 48.dp)
    )
}

@Composable
fun LrcLibSearchDialog(
    query: String,
    setQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onPick: (Track) -> Unit,
    modifier: Modifier = Modifier
) = DefaultDialog(
    onDismiss = onDismiss,
    horizontalPadding = 0.dp,
    modifier = modifier
) {
    val (_, typography) = LocalAppearance.current

    val tracks = remember { mutableStateListOf<Track>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        loading = true
        error = false

        delay(512)

        LrcLib.lyrics(
            query = query,
            synced = true
        )?.onSuccess { newTracks ->
            tracks.clear()
            tracks.addAll(newTracks.filter { !it.syncedLyrics.isNullOrBlank() })
            loading = false
            error = false
        }?.onFailure {
            loading = false
            error = true
            it.printStackTrace()
        } ?: run { loading = false }
    }

    TextField(
        value = query,
        onValueChange = setQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        maxLines = 1,
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))

    when {
        loading -> CircularProgressIndicator(
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        error || tracks.isEmpty() -> BasicText(
            text = stringResource(R.string.no_lyrics_found),
            style = typography.s.semiBold.center,
            modifier = Modifier
                .padding(all = 24.dp)
                .align(Alignment.CenterHorizontally)
        )

        else -> ValueSelectorDialogBody(
            onDismiss = onDismiss,
            title = stringResource(R.string.choose_lyric_track),
            selectedValue = null,
            values = tracks.toImmutableList(),
            onValueSelect = {
                transaction {
                    onPick(it)
                    onDismiss()
                }
            },
            valueText = {
                "${it.artistName} - ${it.trackName} (${
                    it.duration?.seconds?.toComponents { minutes, seconds, _ ->
                        "$minutes:${seconds.toString().padStart(2, '0')}"
                    } ?: "?:??"
                })"
            }
        )
    }
}
