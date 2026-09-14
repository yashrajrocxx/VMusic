package app.pulse.android.ui.components.themed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue

/**
 * A [NestedScrollConnection] that toggles the dock's scroll state based on scroll direction.
 * Downward scroll collapses the dock, upward scroll expands it.
 */
@Composable
fun rememberDockScrollConnection(
    isScrolled: MutableState<Boolean>,
    threshold: Float = 12f
): NestedScrollConnection {
    return remember(isScrolled) {
        object : NestedScrollConnection {
            private var accumulatedDelta = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta > 0f && isScrolled.value) {
                    accumulatedDelta += delta
                    if (accumulatedDelta > threshold) {
                        isScrolled.value = false
                        accumulatedDelta = 0f
                    }
                } else if (delta < 0f && !isScrolled.value) {
                    accumulatedDelta += delta
                    if (accumulatedDelta < -threshold) {
                        isScrolled.value = true
                        accumulatedDelta = 0f
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = if (consumed.y != 0f) consumed.y else available.y
                if (delta < -threshold) {
                    isScrolled.value = true
                    accumulatedDelta = 0f
                } else if (delta > threshold) {
                    isScrolled.value = false
                    accumulatedDelta = 0f
                }
                return Offset.Zero
            }
        }
    }
}

/**
 * Provides an animated progress value (0f to 1f) representing the dock's collapse state.
 */
@Composable
fun rememberDockMorphProgress(): Float {
    val isScrolled by LocalDockScrolled.current
    return animateFloatAsState(
        targetValue = if (isScrolled) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = 450f
        ),
        label = "dockMorphProgress"
    ).value
}
