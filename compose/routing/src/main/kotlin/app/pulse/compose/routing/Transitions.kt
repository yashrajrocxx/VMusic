package app.pulse.compose.routing

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

val defaultStacking = ContentTransform(
    initialContentExit = fadeOut(animationSpec = spring(dampingRatio = 0.9f)) +
        scaleOut(targetScale = 0.985f, animationSpec = spring(dampingRatio = 0.9f)),
    targetContentEnter = fadeIn(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)) +
        scaleIn(initialScale = 0.985f, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)),
    targetContentZIndex = 1f
)

val defaultUnstacking = ContentTransform(
    initialContentExit = fadeOut(animationSpec = spring(dampingRatio = 0.9f)) +
        scaleOut(targetScale = 0.985f, animationSpec = spring(dampingRatio = 0.9f)),
    targetContentEnter = fadeIn(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)) +
        scaleIn(initialScale = 0.985f, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)),
    targetContentZIndex = 0f
)

val defaultStill = ContentTransform(
    initialContentExit = fadeOut(animationSpec = spring(dampingRatio = 0.9f)) +
        scaleOut(targetScale = 0.985f, animationSpec = spring(dampingRatio = 0.9f)),
    targetContentEnter = fadeIn(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)) +
        scaleIn(initialScale = 0.985f, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)),
    targetContentZIndex = 1f
)

val TransitionScope<*>.isStacking: Boolean
    get() = initialState == null && targetState != null

val TransitionScope<*>.isUnstacking: Boolean
    get() = initialState != null && targetState == null

val TransitionScope<*>.isStill: Boolean
    get() = initialState == null && targetState == null
