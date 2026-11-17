package app.pulse.android.ui.items

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import app.pulse.android.R
import app.pulse.core.data.models.Song
import app.pulse.android.preferences.AppearancePreferences
import app.pulse.android.ui.components.themed.TextPlaceholder
import app.pulse.android.ui.modifiers.rememberSwipeState
import app.pulse.android.ui.modifiers.swipeToPlayNext
import app.pulse.android.utils.medium
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
import app.pulse.android.utils.thumbnail
import app.pulse.android.utils.toast
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.shimmer
import app.pulse.core.ui.utils.px
import app.pulse.core.data.utils.songBundle
import app.pulse.providers.innertube.Innertube
import coil3.compose.AsyncImage

@Composable
fun SongItem(
    song: Innertube.SongItem,
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    showDuration: Boolean = true,
    clip: Boolean = true,
    isPlaying: Boolean = false,
    hideExplicit: Boolean = AppearancePreferences.hideExplicit,
    onPlayNext: (() -> Unit)? = null
) = SongItem(
    modifier = modifier,
    thumbnailUrl = song.thumbnail?.size(thumbnailSize.px * 2),
    title = song.info?.name,
    authors = song.authors?.joinToString("") { it.name.orEmpty() },
    duration = song.durationText,
    explicit = song.explicit,
    thumbnailSize = thumbnailSize,
    showDuration = showDuration,
    clip = clip,
    isPlaying = isPlaying,
    hideExplicit = hideExplicit,
    onPlayNext = onPlayNext,
    swipeKey = song.key
)

@Composable
fun SongItem(
    song: MediaItem,
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    onThumbnailContent: (@Composable BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    showDuration: Boolean = true,
    clip: Boolean = true,
    isPlaying: Boolean = false,
    hideExplicit: Boolean = AppearancePreferences.hideExplicit,
    onPlayNext: (() -> Unit)? = null
) {
    val extras = remember(song) { song.mediaMetadata.extras?.songBundle }

    SongItem(
        modifier = modifier,
        thumbnailUrl = song.mediaMetadata.artworkUri.thumbnail(thumbnailSize.px * 2)?.toString(),
        title = song.mediaMetadata.title?.toString(),
        authors = song.mediaMetadata.artist?.toString(),
        duration = extras?.durationText,
        explicit = extras?.explicit == true,
        thumbnailSize = thumbnailSize,
        onThumbnailContent = onThumbnailContent,
        trailingContent = trailingContent,
        showDuration = showDuration,
        clip = clip,
        isPlaying = isPlaying,
        hideExplicit = hideExplicit,
        onPlayNext = onPlayNext,
        swipeKey = song.mediaId
    )
}

@Composable
fun SongItem(
    song: Song,
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    index: Int? = null,
    onThumbnailContent: @Composable (BoxScope.() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    showDuration: Boolean = true,
    clip: Boolean = true,
    isPlaying: Boolean = false,
    hideExplicit: Boolean = AppearancePreferences.hideExplicit,
    onPlayNext: (() -> Unit)? = null
) = SongItem(
    modifier = modifier,
    index = index,
    thumbnailUrl = song.thumbnailUrl?.thumbnail(thumbnailSize.px * 2),
    title = song.title,
    authors = song.artistsText,
    duration = song.durationText,
    explicit = song.explicit,
    thumbnailSize = thumbnailSize,
    onThumbnailContent = onThumbnailContent,
    trailingContent = trailingContent,
    showDuration = showDuration,
    clip = clip,
    isPlaying = isPlaying,
    hideExplicit = hideExplicit,
    onPlayNext = onPlayNext,
    swipeKey = song.id
)

@Composable
private fun SongItem(
    thumbnailUrl: String?,
    title: String?,
    authors: String?,
    duration: String?,
    explicit: Boolean,
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    index: Int? = null,
    onThumbnailContent: @Composable (BoxScope.() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    showDuration: Boolean = true,
    clip: Boolean = true,
    isPlaying: Boolean = false,
    hideExplicit: Boolean = AppearancePreferences.hideExplicit,
    onPlayNext: (() -> Unit)? = null,
    swipeKey: Any? = null
) {
    val (colorPalette, typography, _, thumbnailShape) = LocalAppearance.current

    SongItem(
        title = title,
        authors = authors,
        duration = duration,
        explicit = explicit,
        thumbnailSize = thumbnailSize,
        thumbnailContent = {
            Box(
                modifier = Modifier
                    .clip(thumbnailShape)
                    .background(colorPalette.background1)
                    .fillMaxSize()
            ) {
                if (thumbnailUrl != null) {
                    AsyncImage(
                        model = thumbnailUrl,
                        // Local files without embedded art (and any dead link)
                        // fall back to a music icon instead of a blank box,
                        // like the radio placeholder does.
                        error = painterResource(R.drawable.musical_notes),
                        fallback = painterResource(R.drawable.musical_notes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.musical_notes),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                        modifier = Modifier
                            .fillMaxSize(0.5f)
                            .align(Alignment.Center)
                    )
                }

                if (index != null) {
                    Box(
                        modifier = Modifier
                            .background(color = Color.Black.copy(alpha = 0.75f))
                            .fillMaxSize()
                    )
                    BasicText(
                        text = "${index + 1}",
                        style = typography.xs.semiBold.copy(color = Color.White),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            onThumbnailContent?.invoke(this)
        },
        modifier = modifier,
        trailingContent = trailingContent,
        showDuration = showDuration,
        clip = clip,
        isPlaying = isPlaying,
        hideExplicit = hideExplicit,
        onPlayNext = onPlayNext,
        swipeKey = swipeKey
    )
}

@Composable
private fun SongItem(
    title: String?,
    authors: String?,
    duration: String?,
    explicit: Boolean,
    thumbnailSize: Dp,
    thumbnailContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null,
    showDuration: Boolean = true,
    clip: Boolean = true,
    isPlaying: Boolean = false,
    hideExplicit: Boolean = AppearancePreferences.hideExplicit,
    onPlayNext: (() -> Unit)? = null,
    swipeKey: Any? = null
) {
    val (colorPalette, typography) = LocalAppearance.current

    // Plain background, not animated: only one row is ever "playing", and an
    // Animatable per row burns a frame clock on every list for zero benefit.
    val backgroundColor = if (isPlaying) colorPalette.background2 else Color.Transparent

    if (!(hideExplicit && explicit)) {
        val containerModifier = Modifier
            .background(backgroundColor)
            .let {
                if (clip) Modifier.clip(LocalAppearance.current.thumbnailShape) then it
                else it
            }

        val content: @Composable () -> Unit = {
            Box(
                modifier = Modifier.size(thumbnailSize),
                content = thumbnailContent
            )

            ItemInfoContainer {
                trailingContent?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BasicText(
                            text = title.orEmpty(),
                            style = typography.xs.semiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        it()
                    }
                } ?: BasicText(
                    text = title.orEmpty(),
                    style = typography.xs.semiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        authors?.let {
                            BasicText(
                                text = authors,
                                style = typography.xs.semiBold.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(
                                    weight = 1f,
                                    fill = false
                                )
                            )
                        }

                        if (explicit) Image(
                            painter = painterResource(R.drawable.explicit),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colorPalette.text),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    if (showDuration) duration?.let {
                        BasicText(
                            text = duration,
                            style = typography.xxs.secondary.medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        if (onPlayNext == null) {
            ItemContainer(
                alternative = false,
                thumbnailSize = thumbnailSize,
                modifier = modifier.then(containerModifier),
                content = { content() }
            )
        } else {
            PlayNextSwipeRow(
                key = swipeKey ?: title.orEmpty(),
                title = title.orEmpty(),
                onPlayNext = onPlayNext,
                modifier = modifier,
                content = {
                    ItemContainer(
                        alternative = false,
                        thumbnailSize = thumbnailSize,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(containerModifier),
                        content = { content() }
                    )
                }
            )
        }
    }
}

/**
 * Wraps a song row in the right-swipe-to-play-next gesture. The row slides
 * right to uncover the skip-forward glyph; a left swipe is left unconsumed so
 * [app.pulse.android.ui.modifiers.swipeToClose] on the caller's modifier keeps
 * working.
 */
@Composable
private fun PlayNextSwipeRow(
    key: Any,
    title: String,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val swipeState = rememberSwipeState(key)
    val density = LocalDensity.current

    var width by remember { mutableIntStateOf(0) }
    val widthDp by remember { derivedStateOf { width.px.dp(density) } }
    val offset = swipeState.calculateOffset(bounds = 0.dp..widthDp)

    Box(
        modifier = modifier
            .onSizeChanged { width = it.width }
            .swipeToPlayNext(
                state = swipeState,
                enabled = AppearancePreferences.swipeToPlayNext,
                onPlayNext = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    context.toast(
                        if (title.isBlank()) context.getString(R.string.play_next)
                        else context.getString(R.string.up_next, title)
                    )
                    onPlayNext()
                }
            )
    ) {
        Image(
            painter = painterResource(R.drawable.play_skip_forward),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorPalette.textSecondary),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    alpha = (swipeState.offset.value / 160f).coerceIn(0f, 1f)
                }
                .padding(start = 24.dp)
                .size(28.dp)
        )

        Box(
            modifier = Modifier.offset {
                IntOffset(x = offset.roundToPx(), y = 0)
            }
        ) {
            content()
        }
    }
}

@Composable
fun SongItemPlaceholder(
    thumbnailSize: Dp,
    modifier: Modifier = Modifier
) = ItemContainer(
    alternative = false,
    thumbnailSize = thumbnailSize,
    modifier = modifier
) {
    val (colorPalette, _, _, thumbnailShape) = LocalAppearance.current

    Spacer(
        modifier = Modifier
            .background(color = colorPalette.shimmer, shape = thumbnailShape)
            .size(thumbnailSize)
    )

    ItemInfoContainer {
        TextPlaceholder()
        TextPlaceholder()
    }
}
