package app.pulse.android.ui.components.themed

import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import app.pulse.android.R
import app.pulse.android.preferences.UIStatePreferences
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.utils.isLandscape
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

data class NavigationState(
    val tabs: ImmutableList<Tab>,
    val tabIndex: Int,
    val onTabChange: (Int) -> Unit,
    val hiddenTabs: ImmutableList<String>
)

val LocalNavigationState = staticCompositionLocalOf<MutableState<NavigationState?>> {
    mutableStateOf(null)
}

val LocalDockHiddenCount = staticCompositionLocalOf<MutableState<Int>> {
    mutableStateOf(0)
}

val LocalDockScrolled = staticCompositionLocalOf<MutableState<Boolean>> {
    mutableStateOf(false)
}

val LocalRadioAction = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalRadioVisible = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

@Composable
fun Scaffold(
    key: String,
    topIconButtonId: Int? = null,
    onTopIconButtonClick: () -> Unit = {},
    tabIndex: Int,
    onTabChange: (Int) -> Unit,
    tabColumnContent: TabsBuilder.() -> Unit,
    modifier: Modifier = Modifier,
    tabsEditingTitle: String = stringResource(R.string.tabs),
    isGlobalNav: Boolean = false,
    content: @Composable (Int) -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    var hiddenTabs by UIStatePreferences.mutableTabStateOf(key)
    val tabs = TabsBuilder.rememberTabs(tabColumnContent)
    val isLandscape = isLandscape
    val globalNavigationState = LocalNavigationState.current

    if (isGlobalNav) {
        DisposableEffect(tabs, tabIndex, onTabChange, hiddenTabs) {
            globalNavigationState.value = NavigationState(
                tabs = tabs,
                tabIndex = tabIndex,
                onTabChange = onTabChange,
                hiddenTabs = hiddenTabs
            )
            onDispose { }
        }
    }

    val dockScrolled = LocalDockScrolled.current
    LaunchedEffect(tabIndex) {
        if (isGlobalNav) {
            dockScrolled.value = false
        }
    }

    Row(
        modifier = modifier
            .background(colorPalette.background0)
            .fillMaxSize()
    ) {
            if (isLandscape) {
                NavigationRail(
                    topIconButtonId = topIconButtonId,
                    onTopIconButtonClick = onTopIconButtonClick,
                    tabIndex = tabIndex,
                    onTabIndexChange = onTabChange,
                    hiddenTabs = hiddenTabs,
                    setHiddenTabs = { hiddenTabs = it.toImmutableList() },
                    tabsEditingTitle = tabsEditingTitle,
                    content = tabColumnContent
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                if (isGlobalNav) {
                    val visitedTabs = rememberSaveable(
                        saver = listSaver(
                            save = { it.toList() },
                            restore = { it.toMutableStateList() }
                        )
                    ) {
                        mutableStateListOf(tabIndex)
                    }

                    if (!visitedTabs.contains(tabIndex)) {
                        visitedTabs.add(tabIndex)
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        visitedTabs.forEach { currentTabKey ->
                            val isCurrent = currentTabKey == tabIndex
                            val alpha by animateFloatAsState(
                                targetValue = if (isCurrent) 1f else 0f,
                                animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
                                label = "globalTabAlpha_$currentTabKey"
                            )
                            val scale by animateFloatAsState(
                                targetValue = if (isCurrent) 1f else 0.985f,
                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                                label = "globalTabScale_$currentTabKey"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (isCurrent) 1f else 0f)
                                    .graphicsLayer {
                                        this.alpha = alpha
                                        this.scaleX = scale
                                        this.scaleY = scale
                                    }
                                    .then(
                                        if (!isCurrent) {
                                            Modifier.pointerInput(Unit) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        awaitPointerEvent()
                                                    }
                                                }
                                            }
                                        } else Modifier
                                    )
                            ) {
                                content(currentTabKey)
                            }
                        }
                    }
                } else {
                    AnimatedContent(
                        targetState = tabIndex,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            ContentTransform(
                                targetContentEnter = fadeIn(spring(dampingRatio = 0.9f, stiffness = 600f)) +
                                    scaleIn(
                                        initialScale = 0.985f,
                                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f)
                                    ),
                                initialContentExit = fadeOut(spring(dampingRatio = 0.95f, stiffness = 700f)),
                                sizeTransform = null
                            )
                        },
                        label = "tabContent"
                    ) { targetIndex ->
                        content(targetIndex)
                    }
                }
            }
        }
}
