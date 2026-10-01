package app.pulse.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.times
import app.pulse.android.LocalPlayerAwareWindowInsets
import app.pulse.android.ui.modifiers.pressable

val LocalMenuState = staticCompositionLocalOf { MenuState() }

@Stable
class MenuState {
    var isDisplayed by mutableStateOf(false)
        private set

    /**
     * Bumped on every [display] call so openers can distinguish a fresh tap
     * from a stale close: without this, tapping while the close animation is
     * still running is swallowed (open flag flips true→true, no effect
     * restart) and the delayed hide wins, eating the tap.
     */
    var generation by mutableStateOf(0)
        private set

    var content by mutableStateOf<@Composable () -> Unit>({})
        private set

    /**
     * Wall-clock of the last [display] call. Dim taps inside the grace
     * window are ignored (see BottomSheetMenu): without this, an impatient
     * second tap — or a finger bounce — lands on the rising dim and
     * instantly re-closes the just-opened menu, which reads as a dead
     * 3-dot button.
     */
    var lastDisplayAt: Long = 0L
        private set

    fun display(content: @Composable () -> Unit) {
        this.content = content
        generation++
        lastDisplayAt = System.currentTimeMillis()
        isDisplayed = true
    }

    fun hide() {
        isDisplayed = false
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState = LocalMenuState.current
) = BoxWithConstraints(modifier = modifier) {
    val windowInsets = LocalPlayerAwareWindowInsets.current

    val height = 0.8f * maxHeight

    val bottomSheetState = rememberBottomSheetState(
        dismissedBound = -windowInsets
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding(),
        expandedBound = height
    )

    LaunchedEffect(state.isDisplayed, state.generation) {
        if (state.isDisplayed) bottomSheetState.expandFast()
        else bottomSheetState.dismissFast()
    }

    // Self-heal: if the flag says open but the sheet sits settled down
    // (missed effect, killed animation, lost tap handshake), reopen. The
    // delay lets legitimate closes land hide() first, so normal dismissals
    // never flicker back open.
    LaunchedEffect(state.isDisplayed, bottomSheetState.collapsed, bottomSheetState.dismissed) {
        if (!state.isDisplayed) return@LaunchedEffect
        if (!bottomSheetState.collapsed && !bottomSheetState.dismissed) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        if (state.isDisplayed && (bottomSheetState.collapsed || bottomSheetState.dismissed)) {
            bottomSheetState.expandSoft()
        }
    }

    LaunchedEffect(bottomSheetState.collapsed) {
        if (bottomSheetState.collapsed) state.hide()
    }

    AnimatedVisibility(
        visible = state.isDisplayed,
        enter = fadeIn(),
        // Near-instant exit: a lingering fading dim eats the next tap (it
        // consumes presses while visible with no visual feedback), which
        // reads as a dead menu button. The sheet itself clears the button
        // zone within ~50ms on its fast dismiss.
        exit = fadeOut(animationSpec = tween(80))
    ) {
        Spacer(
            modifier = Modifier
                // Hit-testable only while actually open: the moment hide()
                // flips the flag, the fading-out dim must let taps through
                // to the button beneath instead of swallowing the reopen.
                .then(
                    if (state.isDisplayed) Modifier.pressable(onRelease = {
                        // Open-priority: a tap landing on the rising dim
                        // right after display() is a bounce/re-tap, not a
                        // close intent. Real outside-taps (after the grace
                        // window), slide-down and back still dismiss
                        // instantly.
                        if (System.currentTimeMillis() - state.lastDisplayAt > 400) state.hide()
                    })
                    else Modifier
                )
                .alpha(bottomSheetState.progress * 0.5f)
                .background(Color.Black)
                .fillMaxSize()
        )
    }

    // System back dismisses the menu directly while it is open. The sheet's
    // own predictive-back scrub is disabled above (it left a dead tap window);
    // dismissFast + the hide effect below settle it in ~200ms.
    androidx.activity.compose.BackHandler(enabled = state.isDisplayed) {
        state.hide()
    }

    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        if (!bottomSheetState.dismissed) BottomSheet( // This way the back gesture gets handled correctly
            state = bottomSheetState,
            collapsedContent = { },
            onDismiss = { state.hide() },
            // Menus dismiss instantly on back (standard menu behavior) instead
            // of scrubbing through the sheet's own slow collapse, which leaves
            // a dead tap window behind. The fast dismiss below keeps it snappy.
            backHandlerEnabled = false,
            indication = null,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .sizeIn(maxHeight = height)
                    .nestedScroll(bottomSheetState.preUpPostDownNestedScrollConnection)
            ) {
                state.content()
            }
        }
    }
}
