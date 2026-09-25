package app.pulse.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Left
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Right
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import app.pulse.android.Database
import app.pulse.android.LocalPlayerServiceBinder
import app.pulse.android.R
import app.pulse.android.preferences.AccountPreferences
import app.pulse.android.preferences.PlayerPreferences
import app.pulse.android.service.LoginRequiredException
import app.pulse.android.service.PlayableFormatNotFoundException
import app.pulse.android.service.RestrictedVideoException
import app.pulse.android.service.UnplayableException
import app.pulse.android.service.VideoIdMismatchException
import app.pulse.android.service.isLocal
import app.pulse.android.ui.modifiers.onSwipe
import app.pulse.android.ui.screens.loginRoute
import app.pulse.android.utils.forceSeekToNext
import app.pulse.android.utils.forceSeekToPrevious
import app.pulse.android.utils.isInPip
import app.pulse.android.utils.thumbnail
import app.pulse.android.utils.videoThumbnailHd
import app.pulse.android.utils.windowState
import app.pulse.core.ui.Dimensions
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.utils.px
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumbnail(
    isShowingLyrics: Boolean,
    onShowLyrics: (Boolean) -> Unit,
    isShowingStatsForNerds: Boolean,
    onShowStatsForNerds: (Boolean) -> Unit,
    onOpenDialog: () -> Unit,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    shouldShowSynchronizedLyrics: Boolean = PlayerPreferences.isShowingSynchronizedLyrics,
    setShouldShowSynchronizedLyrics: (Boolean) -> Unit = {
        PlayerPreferences.isShowingSynchronizedLyrics = it
    },
    showLyricsControls: Boolean = true
) {
    val binder = LocalPlayerServiceBinder.current
    val (colorPalette, _, _, thumbnailShape) = LocalAppearance.current

    val (window, error) = windowState()

    val coroutineScope = rememberCoroutineScope()
    val transitionState = remember { SeekableTransitionState(false) }
    val transition = rememberTransition(transitionState)
    val opacity by transition.animateFloat(label = "") { if (it) 1f else 0f }
    val scale by transition.animateFloat(
        label = "",
        transitionSpec = {
            spring(dampingRatio = Spring.DampingRatioLowBouncy)
        }
    ) { if (it) 1f else 0f }
    val isInPip = isInPip()

    AnimatedContent(
        targetState = window,
        transitionSpec = {
            val slide = spring<IntOffset>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            val fade = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            val sizeSpec = spring<IntSize>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            val initial = initialState
            val target = targetState

            if (initial == null || target == null) return@AnimatedContent ContentTransform(
                targetContentEnter = fadeIn(fade),
                initialContentExit = fadeOut(fade),
                sizeTransform = null
            )

            val sizeTransform = SizeTransform(clip = false) { _, _ -> sizeSpec }

            val direction = if (target.firstPeriodIndex < initial.firstPeriodIndex) Right else Left

            ContentTransform(
                targetContentEnter = slideIntoContainer(direction, slide) +
                    fadeIn(fade) +
                    scaleIn(spring<Float>(dampingRatio = 0.85f), 0.85f),
                initialContentExit = slideOutOfContainer(direction, slide) +
                    fadeOut(fade) +
                    scaleOut(spring<Float>(dampingRatio = 0.9f), 0.85f),
                sizeTransform = sizeTransform
            )
        },
        modifier = modifier.onSwipe(
            onSwipeLeft = {
                binder?.player?.forceSeekToNext()
            },
            onSwipeRight = {
                binder?.player?.forceSeekToPrevious(seekToStart = false)
            }
        ),
        contentAlignment = Alignment.Center,
        label = ""
    ) { currentWindow ->
        val shadowElevation by animateDpAsState(
            targetValue = if (window == currentWindow) 8.dp else 0.dp,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
            label = ""
        )
        // Static blur cut instead of an animated radius: animating it re-renders
        // a full-image blur every frame of the spring (heavy on low-end GPUs),
        // while the overlay opening covers the transition anyway.
        val blurRadius =
            if (isShowingLyrics || error != null || isShowingStatsForNerds) 8.dp else 0.dp

        if (currentWindow != null) Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(if (isInPip) RectangleShape else thumbnailShape)
                .shadow(
                    elevation = shadowElevation,
                    shape = thumbnailShape,
                    clip = false
                )
        ) {
            var height by remember { mutableIntStateOf(0) }
            // Request the full display width (1:1, capped by maxThumbnailSize):
            // asking for less upscales on display and looks soft fullscreen.
            val artwork = currentWindow.mediaItem.mediaMetadata.artworkUri
                ?.toString()?.videoThumbnailHd()
                ?.thumbnail(Dimensions.thumbnails.player.song.px)

            if (artwork != null) {
                // Current callbacks: this gesture node never restarts, so it
                // must read through holders (track changes would like the
                // wrong song after a skip).
                val currentLikedAt by rememberUpdatedState(likedAt)
                val currentSetLikedAt by rememberUpdatedState(setLikedAt)
                val currentOnShowLyrics by rememberUpdatedState(onShowLyrics)
                val currentOnShowStats by rememberUpdatedState(onShowStatsForNerds)
                AsyncImage(
                    model = artwork,
                    placeholder = painterResource(id = R.mipmap.ic_launcher_foreground),
                    error = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { currentOnShowLyrics(true) },
                            onLongClick = { currentOnShowStats(true) },
                            onDoubleClick = {
                                // Like-only (never unlikes: an already-liked
                                // song just replays the burst below).
                                if (currentLikedAt == null) {
                                    currentSetLikedAt(System.currentTimeMillis())
                                }

                                coroutineScope.launch {
                                    transitionState.animateTo(
                                        true,
                                        spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                    kotlinx.coroutines.delay(350)
                                    transitionState.animateTo(
                                        false,
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        )
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .background(colorPalette.background0)
                        .let {
                            if (blurRadius == 0.dp) it else it.blur(radius = blurRadius)
                        }
                        .animateContentSize()
                        .onGloballyPositioned { coords ->
                            coords.size.height.let { if (it > 0) height = it }
                        }
                )
            }

            Lyrics(
                mediaId = currentWindow.mediaItem.mediaId,
                isDisplayed = isShowingLyrics && error == null,
                onDismiss = { onShowLyrics(false) },
                ensureSongInserted = { Database.insert(currentWindow.mediaItem) },
                mediaMetadataProvider = currentWindow.mediaItem::mediaMetadata,
                durationProvider = { binder?.player?.duration ?: C.TIME_UNSET },
                onOpenDialog = onOpenDialog,
                modifier = Modifier.height(height.px.dp),
                shouldShowSynchronizedLyrics = shouldShowSynchronizedLyrics,
                setShouldShowSynchronizedLyrics = setShouldShowSynchronizedLyrics,
                showControls = showLyricsControls
            )

            StatsForNerds(
                mediaId = currentWindow.mediaItem.mediaId,
                isDisplayed = isShowingStatsForNerds && error == null,
                onDismiss = { onShowStatsForNerds(false) },
                modifier = Modifier.height(height.px.dp)
            )

            Image(
                painter = painterResource(R.drawable.heart),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.accent),
                modifier = Modifier
                    .fillMaxSize(0.62f)
                    .aspectRatio(1f)
                    .align(Alignment.Center)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        alpha = opacity,
                        shadowElevation = 8.dp.px.toFloat()
                    )
            )

            PlaybackError(
                isDisplayed = error != null,
                messageProvider = {
                    if (currentWindow.mediaItem.isLocal) stringResource(R.string.error_local_music_deleted)
                    else when (error?.cause?.cause) {
                        is UnresolvedAddressException, is UnknownHostException ->
                            stringResource(R.string.error_network)

                        is PlayableFormatNotFoundException -> stringResource(R.string.error_unplayable)
                        is UnplayableException -> stringResource(R.string.error_source_deleted)
                        is LoginRequiredException ->
                            if (AccountPreferences.isLoggedIn) stringResource(R.string.error_login_expired)
                            else stringResource(R.string.error_login_required)
                        is RestrictedVideoException ->
                            stringResource(R.string.error_server_restrictions)

                        is VideoIdMismatchException -> stringResource(R.string.error_id_mismatch)
                        else -> stringResource(R.string.error_unknown_playback)
                    }
                },
                onDismiss = { binder?.player?.prepare() },
                actionLabel = if (error?.cause?.cause is LoginRequiredException) stringResource(R.string.login_with_google) else null,
                onAction = if (error?.cause?.cause is LoginRequiredException) ({ loginRoute.global() }) else null,
                modifier = Modifier.height(height.px.dp)
            )
        }
    }
}
