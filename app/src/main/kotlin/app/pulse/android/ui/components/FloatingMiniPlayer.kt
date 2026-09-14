package app.pulse.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import app.pulse.android.utils.formatAsDuration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.pulse.android.Database
import app.pulse.android.LocalPlayerServiceBinder
import app.pulse.android.R
import app.pulse.android.preferences.AppearancePreferences
import app.pulse.android.models.ui.toUiMedia
import app.pulse.android.ui.components.themed.CircularProgressIndicator
import app.pulse.core.ui.utils.px
import app.pulse.android.utils.asMediaItem
import app.pulse.android.utils.DisposableListener
import app.pulse.android.utils.forceSeekToNext
import app.pulse.android.utils.forceSeekToPrevious
import app.pulse.android.utils.positionAndDurationState
import app.pulse.android.utils.rememberIsBuffering
import app.pulse.android.utils.seamlessPlay
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
import app.pulse.android.utils.shouldBePlaying
import app.pulse.android.utils.thumbnail
import app.pulse.core.ui.Dimensions
import app.pulse.core.ui.LocalAppearance
import app.pulse.android.models.ui.UiMedia
import coil3.compose.AsyncImage

@Composable
fun rememberMiniPlayerState(): MiniPlayerState {
    val binder = LocalPlayerServiceBinder.current

    var mediaItem by remember(binder) {
        mutableStateOf(
            value = binder?.player?.currentMediaItem,
            policy = neverEqualPolicy()
        )
    }
    // Follow the service's song flow so the dock flips to the incoming song
    // at crossfade start, not only at the boundary transition.
    LaunchedEffect(binder) {
        binder?.mediaItemState?.collect { mediaItem = it }
    }
    var shouldBePlaying by remember(binder) { mutableStateOf(binder?.player?.shouldBePlaying == true) }
    val isBuffering = binder?.player?.rememberIsBuffering() ?: false

    // Track whether the user explicitly swiped down to clear the queue.
    // When true, suppress the historyMediaItem fallback so the dock shows
    // the clean "Play something…" empty state instead of the last song.
    var userCleared by remember { mutableStateOf(false) }

    binder?.player?.DisposableListener {
        object : Player.Listener {
            override fun onMediaItemTransition(newMediaItem: MediaItem?, reason: Int) {
                mediaItem = newMediaItem
                // A new song started — user is no longer in a cleared state.
                if (newMediaItem != null) userCleared = false
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                shouldBePlaying = player.shouldBePlaying
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                shouldBePlaying = player.shouldBePlaying
            }
        }
    }

    var duration by remember(mediaItem) {
        mutableLongStateOf(binder?.player?.duration?.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L)
    }

    val activeMediaItem = if (userCleared) null else mediaItem
    val metadata = activeMediaItem?.toUiMedia(duration)

    val onClearAll: () -> Unit = {
        userCleared = true
        binder?.stopRadio()
        binder?.player?.clearMediaItems()
    }

    return remember(activeMediaItem, metadata, shouldBePlaying, isBuffering, binder, mediaItem, userCleared) {
        MiniPlayerState(activeMediaItem, metadata, shouldBePlaying, isBuffering, binder, mediaItem, null, onClearAll)
    }
}

data class MiniPlayerState(
    val activeMediaItem: MediaItem?,
    val metadata: UiMedia?,
    val shouldBePlaying: Boolean,
    val isBuffering: Boolean,
    val binder: app.pulse.android.service.PlayerService.Binder?,
    val mediaItem: MediaItem?,
    val historyMediaItem: MediaItem?,
    val onClearAll: () -> Unit = {}
)



@Composable
fun MorphingMiniPlayer(
    progress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onSwipeUp: (() -> Unit)? = null,
    contentWidth: Dp
) {
    val state = rememberMiniPlayerState()
    val (activeMediaItem, metadata, shouldBePlaying, isBuffering, binder) = state
    if (activeMediaItem == null) return

    val (colorPalette, typography) = LocalAppearance.current

    val isRadio = activeMediaItem.mediaId.startsWith("radio:")

    var sleepTimerMillisLeft by remember { mutableLongStateOf(0L) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    LaunchedEffect(binder, binder?.sleepTimerMillisLeft) {
        binder?.sleepTimerMillisLeft?.collectLatest {
            sleepTimerMillisLeft = it ?: 0L
        } ?: run { sleepTimerMillisLeft = 0L }
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(CircleShape)
            .background(colorPalette.background1)
            .pointerInput(activeMediaItem, isRadio) {
                val slop = viewConfiguration.touchSlop
                val threshold = (slop * 1.5f).coerceAtLeast(36f)
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                coroutineScope {
                    while (isActive) {
                        awaitPointerEventScope {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalX = 0f
                            var totalY = 0f
                            var isDrag = false
                            val pointerId = down.id
                            var isLongPress = false

                            val longPressJob = launch {
                                delay(longPressTimeout)
                                if (!isDrag && isRadio) {
                                    isLongPress = true
                                    showSleepTimerDialog = true
                                }
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) {
                                    longPressJob.cancel()
                                    if (!isLongPress) {
                                        if (isDrag) {
                                            if (activeMediaItem != null) {
                                                if (!isRadio && kotlin.math.abs(totalX) > kotlin.math.abs(totalY)) {
                                                    if (totalX > threshold) {
                                                        binder?.player?.forceSeekToPrevious()
                                                    } else if (totalX < -threshold) {
                                                        binder?.player?.forceSeekToNext()
                                                    }
                                                } else {
                                                    if (!isRadio && totalY < -threshold) {
                                                        onSwipeUp?.invoke()
                                                    } else if (totalY > threshold) {
                                                        state.onClearAll()
                                                    }
                                                }
                                            }
                                        } else if (!change.isConsumed) {
                                            if (isRadio) {
                                                if (shouldBePlaying) binder?.player?.pause() else binder?.player?.play()
                                            } else {
                                                onClick()
                                            }
                                        }
                                    }
                                    break
                                }

                                val dragAmount = change.positionChange()
                                totalX += dragAmount.x
                                totalY += dragAmount.y
                                if (kotlin.math.abs(totalX) > threshold || kotlin.math.abs(totalY) > threshold) {
                                    isDrag = true
                                    longPressJob.cancel()
                                    change.consume()
                                }
                            }
                        }
                    }
                }
            },
        // the pill masks from the right only, so the artwork thumb
        // stays visible as the pill narrows.
        contentAlignment = Alignment.CenterStart
    ) {
        val pillHeight = maxHeight
        val thumbSize = (pillHeight - 16.dp).coerceAtLeast(28.dp)

        Row(
            // the pill's clip masks the content as it narrows
            // instead of re-laying the text out (the source of the bounce).
            modifier = Modifier
                .width(contentWidth)
                .padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 0 at both rest states, 1 mid-morph: tuck/twist peaks in the middle.
            val pulse = 4f * progress.coerceIn(0f, 1f) * (1f - progress.coerceIn(0f, 1f))
            val textScale = 1f - 0.08f * pulse

            val art = activeMediaItem.mediaMetadata.artworkUri?.thumbnail(Dimensions.thumbnails.song.px)
            Box(
                modifier = Modifier.size(thumbSize),
                contentAlignment = Alignment.Center
            ) {
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(colorPalette.background0)
                            .graphicsLayer {
                                rotationZ = -8f * pulse
                            }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(colorPalette.background0)
                            .graphicsLayer {
                                rotationZ = -8f * pulse
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(if (isRadio) R.drawable.radio else R.drawable.musical_notes),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colorPalette.accent),
                            modifier = Modifier.size(thumbSize * 0.55f)
                        )
                    }
                }
                if (isRadio && sleepTimerMillisLeft > 0L) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(colorPalette.accent)
                    )
                }
            }

                Spacer(modifier = Modifier.width(12.dp))

                val density = LocalDensity.current
                val artistLineHeight = with(density) { (typography.xs.fontSize * 1.4f).toDp() }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    BasicText(
                        text = metadata?.title ?: "",
                        style = typography.xs.semiBold.copy(
                            color = colorPalette.text
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            scaleX = textScale
                            scaleY = textScale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                    )

                    val artistFade = (1f - (progress / 0.8f)).coerceIn(0f, 1f)
                    BasicText(
                        text = metadata?.artist ?: "-",
                        style = typography.xs.secondary.copy(
                            color = colorPalette.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .height(artistLineHeight * artistFade)
                            .graphicsLayer {
                                alpha = artistFade
                                scaleX = textScale
                                scaleY = textScale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                    )
                }

                Row(
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = (1f - (progress / 0.8f)).coerceIn(0f, 1f)
                            rotationZ = -15f * (progress / 0.8f).coerceIn(0f, 1f)
                        }
                        .then(if (progress > 0.5f) Modifier.pointerInput(Unit) {} else Modifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = shouldBePlaying to isBuffering,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = ""
                    ) { (isPlaying, buffering) ->
                        val pressSource = remember { MutableInteractionSource() }
                        val isPressed by pressSource.collectIsPressedAsState()
                        val btnScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.82f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "miniPlayScale"
                        )
                        Box(
                            modifier = Modifier
                                .padding(all = 8.dp)
                                .size(24.dp)
                                .graphicsLayer {
                                    scaleX = btnScale
                                    scaleY = btnScale
                                }
                                .clickable(
                                    interactionSource = pressSource,
                                    indication = null,
                                    onClick = {
                                        if (shouldBePlaying) binder?.player?.pause()
                                        else binder?.player?.play()
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (buffering && isPlaying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colorPalette.accent),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSleepTimerDialog && isRadio) {
            RadioSleepTimerDialog(
                sleepTimerMillisLeft = sleepTimerMillisLeft,
                onDismiss = { showSleepTimerDialog = false },
                onSetTimer = { millis -> binder?.startSleepTimer(millis) },
                onCancelTimer = { binder?.cancelSleepTimer() }
            )
        }
    }

@Composable
fun RadioSleepTimerDialog(
    sleepTimerMillisLeft: Long,
    onDismiss: () -> Unit,
    onSetTimer: (Long) -> Unit,
    onCancelTimer: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    var minutes by remember { mutableIntStateOf(30) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(all = 24.dp)
                .background(
                    color = colorPalette.background1,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.alarm),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.accent),
                    modifier = Modifier.size(20.dp)
                )
                BasicText(
                    text = "Radio Sleep Timer",
                    style = typography.s.semiBold.copy(color = colorPalette.text)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (sleepTimerMillisLeft > 0L) {
                BasicText(
                    text = "Timer active",
                    style = typography.xs.secondary.copy(color = colorPalette.textSecondary)
                )
                Spacer(modifier = Modifier.height(6.dp))
                BasicText(
                    text = formatAsDuration(sleepTimerMillisLeft),
                    style = typography.xxl.semiBold.copy(color = colorPalette.accent)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorPalette.background2)
                            .clickable {
                                onCancelTimer()
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = "Stop Timer",
                            style = typography.xs.semiBold.copy(color = colorPalette.text)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorPalette.accent)
                            .clickable {
                                onSetTimer(sleepTimerMillisLeft + 15 * 60 * 1000L)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = "+15 min",
                            style = typography.xs.semiBold.copy(color = colorPalette.onAccent)
                        )
                    }
                }
            } else {
                val presets = listOf(15, 30, 45, 60, 90)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { presetMin ->
                        val isSelected = minutes == presetMin
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) colorPalette.accent else colorPalette.background2)
                                .clickable { minutes = presetMin }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                text = "${presetMin}m",
                                style = typography.xs.semiBold.copy(
                                    color = if (isSelected) colorPalette.onAccent else colorPalette.text
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colorPalette.background2)
                            .clickable(enabled = minutes > 5) { minutes = (minutes - 5).coerceAtLeast(5) }
                    ) {
                        BasicText("-", style = typography.s.semiBold.copy(color = colorPalette.text))
                    }

                    BasicText(
                        text = "$minutes minutes",
                        style = typography.s.semiBold.copy(color = colorPalette.text)
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colorPalette.background2)
                            .clickable(enabled = minutes < 240) { minutes = (minutes + 5).coerceAtMost(240) }
                    ) {
                        BasicText("+", style = typography.s.semiBold.copy(color = colorPalette.text))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorPalette.background2)
                            .clickable(onClick = onDismiss)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = "Cancel",
                            style = typography.xs.semiBold.copy(color = colorPalette.textSecondary)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorPalette.accent)
                            .clickable {
                                onSetTimer(minutes * 60 * 1000L)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = "Start Timer",
                            style = typography.xs.semiBold.copy(color = colorPalette.onAccent)
                        )
                    }
                }
            }
        }
    }
}
