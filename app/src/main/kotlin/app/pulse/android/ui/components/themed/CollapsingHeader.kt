package app.pulse.android.ui.components.themed

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import app.pulse.android.utils.medium
import app.pulse.core.ui.LocalAppearance


import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer

val CollapsingHeaderContentSpacer: Dp = 96.dp

@Composable
fun CollapsingHeader(
    title: String,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
    expandedFontSize: TextUnit = 36.sp,
    collapsedFontSize: TextUnit = 26.sp,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()

        HeaderOverlay(
            title = title,
            scrollOffsetProvider = { scrollState.value.toFloat() },
            headerActions = headerActions,
            expandedFontSize = expandedFontSize,
            collapsedFontSize = collapsedFontSize
        )
    }
}

@Composable
fun CollapsingHeader(
    title: String,
    lazyListState: LazyListState,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
    expandedFontSize: TextUnit = 38.sp,
    collapsedFontSize: TextUnit = 28.sp,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()

        HeaderOverlay(
            title = title,
            scrollOffsetProvider = {
                if (lazyListState.firstVisibleItemIndex > 0) Float.MAX_VALUE
                else lazyListState.firstVisibleItemScrollOffset.toFloat()
            },
            headerActions = headerActions,
            expandedFontSize = expandedFontSize,
            collapsedFontSize = collapsedFontSize
        )
    }
}

@Composable
fun CollapsingHeader(
    title: String,
    lazyGridState: LazyGridState,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
    expandedFontSize: TextUnit = 38.sp,
    collapsedFontSize: TextUnit = 28.sp,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()

        HeaderOverlay(
            title = title,
            scrollOffsetProvider = {
                if (lazyGridState.firstVisibleItemIndex > 0) Float.MAX_VALUE
                else lazyGridState.firstVisibleItemScrollOffset.toFloat()
            },
            headerActions = headerActions,
            expandedFontSize = expandedFontSize,
            collapsedFontSize = collapsedFontSize
        )
    }
}

@Composable
private fun HeaderOverlay(
    title: String,
    scrollOffsetProvider: () -> Float,
    headerActions: @Composable RowScope.() -> Unit = {},
    expandedFontSize: TextUnit = 38.sp,
    collapsedFontSize: TextUnit = 28.sp,
) {
    val (colorPalette, typography) = LocalAppearance.current
    val density = LocalDensity.current

    val expandedHeight = 120.dp
    val collapsedHeight = 72.dp
    val collapseThresholdPx = remember(density) { with(density) { (expandedHeight - collapsedHeight).toPx().coerceAtLeast(1f) } }
    val travelPx = remember(density) { with(density) { (expandedHeight - collapsedHeight).toPx() } }

    val gradientBrush = remember(colorPalette.background0) {
        Brush.verticalGradient(
            colors = listOf(
                colorPalette.background0,
                colorPalette.background0.copy(alpha = 0.95f),
                colorPalette.background0.copy(alpha = 0.6f),
                Color.Transparent
            )
        )
    }

    // Header Background Gradient - fixed height layout, GPU-composited alpha fade
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(expandedHeight + 48.dp)
            .graphicsLayer {
                alpha = (scrollOffsetProvider() / collapseThresholdPx).coerceIn(0f, 1f)
            }
            .background(gradientBrush)
    )

    // Header Content - fixed height layout, GPU-composited text scaling & translation
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(expandedHeight)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        ) {
            val scaleFactor = collapsedFontSize.value / expandedFontSize.value
            BasicText(
                text = title,
                style = typography.xxl.medium.copy(fontSize = expandedFontSize),
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        val progress = (scrollOffsetProvider() / collapseThresholdPx).coerceIn(0f, 1f)
                        val scale = 1f - (1f - scaleFactor) * progress
                        scaleX = scale
                        scaleY = scale
                        translationY = -travelPx * progress
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.graphicsLayer {
                    val progress = (scrollOffsetProvider() / collapseThresholdPx).coerceIn(0f, 1f)
                    translationY = -travelPx * progress
                },
                content = headerActions
            )
        }
    }
}
