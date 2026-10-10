package app.pulse.android.ui.components.themed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.pulse.android.preferences.AppearancePreferences
import app.pulse.android.ui.components.MorphingMiniPlayer
import app.pulse.core.ui.Dimensions
import app.pulse.core.ui.LocalAppearance

@Composable
fun MorphingDock(
    progress: Float,
    navigationState: NavigationState?,
    onPlayerClick: () -> Unit,
    onPlayerQueueClick: (() -> Unit)? = null,
    onSearchClick: () -> Unit,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val compact = AppearancePreferences.compactDock
    val dockPad = if (compact) 16.dp else 24.dp
    val dockSpacing = if (compact) 6.dp else 8.dp
    val topPad = 4.dp

    val density = LocalDensity.current
    val (colorPalette) = LocalAppearance.current

    // Keep the last non-null navigation state so the dock is persistent across all screens
    var lastNavState by remember { mutableStateOf(navigationState) }
    if (navigationState != null && navigationState != lastNavState) {
        lastNavState = navigationState
    }
    val currentNavState = navigationState ?: lastNavState

    // Progress timeline: 0.0 (fully expanded navbar) to 1.0 (minimized dock: [Tab] [Miniplayer] [Search])
    val p = progress
    val clampedP = p.coerceIn(0f, 1f)

    val navMorphProgress = (clampedP / 0.90f).coerceIn(0f, 1f)
    val playerMorphProgress = ((clampedP - 0.2f) / 0.7f).coerceIn(0f, 1f)
    val playerSlideProgress = ((clampedP - 0.5f) / 0.4f).coerceIn(0f, 1f)

    val dockOverscrollDip = if (compact) 12.dp else 16.dp

    // Hoisted so the container hugs its content: without an active song there is
    // only one pill row, so a fixed two-row height would leave a tall empty fade.
    val miniPlayerState = app.pulse.android.ui.components.rememberMiniPlayerState()
    val hasActiveSong = miniPlayerState.activeMediaItem != null
    val baseSize = if (compact) Dimensions.items.collapsedPlayerHeight else 64.dp

    // Fade runway matched to the pills: taller when the stacked mini-player is
    // present (two rows need a longer dissolve), shorter for the navbar alone.
    // Tracks the scroll-collapse live: as the dock folds to a single row the
    // fade breathes down with it instead of jumping between two heights.
    // Purpose-built as a legibility scrim (list text must not clash with tab
    // text), not decoration — so it stays short and settles solid right
    // behind the pills.
    val collapse = if (hasActiveSong) playerSlideProgress else 0f
    val rows = (if (hasActiveSong) 2f else 1f) - collapse
    val contentHeight = baseSize * rows + dockSpacing * (rows - 1f).coerceAtLeast(0f)
    val scrimHeight = ((if (hasActiveSong) 88f else 64f) - 24f * collapse).dp

    // One continuous scrim with no solid block: fully transparent at the top,
    // reaching solid only at the very bottom edge of the screen. The pills
    // float over translucency, so there is never a hard solid rectangle
    // behind them. Driven by background0, so it matches light/dark/AMOLED.
    val backdropBrush = remember(colorPalette.background0) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to colorPalette.background0.copy(alpha = 0.45f),
                0.8f to colorPalette.background0.copy(alpha = 0.85f),
                1f to colorPalette.background0
            )
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .background(backdropBrush)
            .safeDrawingPadding()
            .padding(horizontal = dockPad)
            .padding(top = topPad, bottom = dockPad)
            .fillMaxWidth()
            .height(scrimHeight + contentHeight)
    ) {
        val fullWidth = maxWidth

        // Structural morph, shrink circle to 80% of baseSize at p=1.0
        val morphFactor = clampedP
        val targetSize = baseSize * (1f - 0.2f * morphFactor)
        val overscroll = (p - 1f).coerceAtLeast(0f)
        val currentCircleSize = (targetSize - (baseSize * 0.4f * overscroll)).coerceAtLeast(baseSize * 0.6f)
        val commonDip = with(density) { dockOverscrollDip.toPx() * overscroll }

        // 1. Search button (Right circle)
        Box(
            modifier = Modifier
                .size(currentCircleSize)
                .align(Alignment.BottomEnd)
                .graphicsLayer {
                    translationY = commonDip
                }
        ) {
            FloatingSearchButton(onClick = onSearchClick, modifier = Modifier.fillMaxSize())
        }

        // 2. Navigation bar (Left circle / expanded pill)
        if (!isLandscape && currentNavState != null) {
            val expandedNavWidth = fullWidth - baseSize - dockSpacing
            val currentNavWidth = (expandedNavWidth + (currentCircleSize - expandedNavWidth) * navMorphProgress)
                .coerceAtLeast(currentCircleSize)

            Box(
                modifier = Modifier
                    .height(currentCircleSize)
                    .width(currentNavWidth)
                    .align(Alignment.BottomStart)
                    .graphicsLayer {
                        translationY = commonDip
                    }
            ) {
                MorphingNavigationBar(
                    progress = navMorphProgress,
                    tabs = currentNavState.tabs,
                    tabIndex = currentNavState.tabIndex,
                    onTabChange = currentNavState.onTabChange,
                    hiddenTabs = currentNavState.hiddenTabs,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 3. Mini player (Center pill) - only rendered when music is actively loaded/playing
        if (hasActiveSong) {
            val playerLandingWidth = fullWidth - (currentCircleSize * 2) - (dockSpacing * 2)
            val expandedPlayerWidth = fullWidth

            val currentPlayerWidth = expandedPlayerWidth + (playerLandingWidth - expandedPlayerWidth) * playerMorphProgress

            Box(
                modifier = Modifier
                    .height(currentCircleSize)
                    .width(currentPlayerWidth)
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        val travelDistance = (baseSize + dockSpacing).toPx()
                        translationY = -travelDistance * (1f - playerSlideProgress) + commonDip
                    }
            ) {
                MorphingMiniPlayer(
                    progress = playerMorphProgress,
                    onClick = onPlayerClick,
                    onSwipeUp = onPlayerQueueClick,
                    modifier = Modifier.fillMaxSize(),
                    contentWidth = fullWidth
                )
            }
        }
    }
}
