package app.pulse.android.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

data class ScrollingInfo(
    val isScrollingDown: Boolean = false,
    val isFar: Boolean = false,
    val isReversed: Boolean = false
)

private class ScrollTracker(var index: Int, var offset: Int, var isDown: Boolean = false)

@Composable
fun LazyListState.scrollingInfo(key: Any = Unit): ScrollingInfo {
    val tracker = remember(this) { ScrollTracker(firstVisibleItemIndex, firstVisibleItemScrollOffset) }

    return remember(this, key) {
        derivedStateOf {
            val currIndex = firstVisibleItemIndex
            val currOffset = firstVisibleItemScrollOffset
            val delta = if (tracker.index == currIndex) currOffset - tracker.offset else currIndex - tracker.index

            if (kotlin.math.abs(delta) > 4) {
                tracker.isDown = delta > 0
            }
            tracker.index = currIndex
            tracker.offset = currOffset

            val isFar = currIndex > 3

            ScrollingInfo(
                isScrollingDown = tracker.isDown,
                isFar = isFar,
                isReversed = false
            )
        }
    }.value
}

@Composable
fun LazyGridState.scrollingInfo(key: Any = Unit): ScrollingInfo {
    val tracker = remember(this) { ScrollTracker(firstVisibleItemIndex, firstVisibleItemScrollOffset) }

    return remember(this, key) {
        derivedStateOf {
            val currIndex = firstVisibleItemIndex
            val currOffset = firstVisibleItemScrollOffset
            val delta = if (tracker.index == currIndex) currOffset - tracker.offset else currIndex - tracker.index

            if (kotlin.math.abs(delta) > 4) {
                tracker.isDown = delta > 0
            }
            tracker.index = currIndex
            tracker.offset = currOffset

            val isFar = currIndex > 3

            ScrollingInfo(
                isScrollingDown = tracker.isDown,
                isFar = isFar,
                isReversed = false
            )
        }
    }.value
}

@Composable
fun ScrollState.scrollingInfo(key: Any = Unit): ScrollingInfo {
    val tracker = remember(this) { ScrollTracker(0, value) }

    return remember(this, key) {
        derivedStateOf {
            val currValue = value
            val delta = currValue - tracker.offset
            if (kotlin.math.abs(delta) > 4) {
                tracker.isDown = delta > 0
            }
            tracker.offset = currValue

            ScrollingInfo(
                isScrollingDown = tracker.isDown,
                isFar = false,
                isReversed = false
            )
        }
    }.value
}
