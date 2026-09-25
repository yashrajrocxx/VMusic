package app.pulse.android.ui.modifiers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

private fun Modifier.fadingEdge(
    start: Boolean,
    end: Boolean,
    startMiddle: Int,
    endMiddle: Int,
    alpha: Float,
    isHorizontal: Boolean
) = this
    .graphicsLayer(alpha = 0.99f)
    .composed {
        // Brush depends only on params (fractional stops, no px), so cache it:
        // otherwise every scrolled frame rebuilds the gradient shader.
        val brush = remember(start, end, startMiddle, endMiddle, alpha, isHorizontal) {
            val transparentColor = Color(red = 0f, green = 0f, blue = 0f, alpha = 1f - alpha)
            if (isHorizontal) {
                Brush.horizontalGradient(
                    0f to (if (start) transparentColor else Color.Black),
                    (1f / (startMiddle + 2)) to Color.Black,
                    (1f - 1f / (endMiddle + 2)) to Color.Black,
                    1f to (if (end) transparentColor else Color.Black)
                )
            } else {
                Brush.verticalGradient(
                    0f to (if (start) transparentColor else Color.Black),
                    (1f / (startMiddle + 2)) to Color.Black,
                    (1f - 1f / (endMiddle + 2)) to Color.Black,
                    1f to (if (end) transparentColor else Color.Black)
                )
            }
        }
        drawWithContent {
            drawContent()
            drawRect(
                brush = brush,
                blendMode = BlendMode.DstIn
            )
        }
    }

fun Modifier.verticalFadingEdge(
    top: Boolean = true,
    bottom: Boolean = true,
    topSize: Int = 3,
    bottomSize: Int = 3,
    alpha: Float = 1f
) = fadingEdge(
    start = top,
    end = bottom,
    startMiddle = topSize,
    endMiddle = bottomSize,
    alpha = alpha,
    isHorizontal = false
)

fun Modifier.horizontalFadingEdge(
    left: Boolean = true,
    right: Boolean = true,
    leftSize: Int = 3,
    rightSize: Int = 3,
    alpha: Float = 1f
) = fadingEdge(
    start = left,
    end = right,
    startMiddle = leftSize,
    endMiddle = rightSize,
    alpha = alpha,
    isHorizontal = true
)
