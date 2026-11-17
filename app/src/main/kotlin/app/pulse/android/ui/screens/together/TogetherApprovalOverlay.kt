package app.pulse.android.ui.screens.together

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pulse.android.R
import app.pulse.android.listentogether.ListenTogetherCoordinator
import app.pulse.android.ui.components.themed.DefaultDialog
import app.pulse.android.ui.components.themed.DialogTextButton
import app.pulse.android.utils.center
import app.pulse.android.utils.medium
import app.pulse.android.utils.semiBold
import app.pulse.core.ui.LocalAppearance

/**
 * Host approval prompt that floats above every tab and the player sheet —
 * the Together tab cards alone are too easy to miss. Fires for suggestions
 * (Ask-to-play first) and join requests, with the same actions as the cards
 * and the heads-up notification. "Later" snoozes the popup; the notification
 * and the tab cards keep the request actionable.
 */
@Composable
fun TogetherApprovalOverlay(modifier: Modifier = Modifier) {
    val state by ListenTogetherCoordinator.state.collectAsState()
    if (!state.isHost || !state.inRoom) return

    var snoozed by remember { mutableStateOf(setOf<String>()) }
    val liveIds = remember(state.suggestions, state.pendingJoinRequests) {
        state.suggestions.map { it.suggestionId } +
            state.pendingJoinRequests.map { "join:${it.userId}" }
    }
    LaunchedEffect(liveIds) {
        snoozed = snoozed.intersect(liveIds.toSet())
    }

    val suggestion = remember(state.suggestions, snoozed) {
        state.suggestions
            .sortedByDescending { it.isPlayRequest }
            .firstOrNull { it.suggestionId !in snoozed }
    }
    val joinRequest = remember(state.pendingJoinRequests, snoozed, suggestion) {
        if (suggestion != null) null
        else state.pendingJoinRequests.firstOrNull { "join:${it.userId}" !in snoozed }
    }

    val snoozeSuggestion = { id: String -> snoozed = snoozed + id }
    val snoozeJoin = { userId: String -> snoozed = snoozed + "join:$userId" }

    when {
        suggestion != null -> {
            val title = suggestion.track?.title?.takeIf { it.isNotBlank() }
                ?: suggestion.track?.id.orEmpty()
            val subtitle = if (suggestion.isPlayRequest) {
                stringResource(R.string.together_wants_to_play, suggestion.fromUsername)
            } else {
                stringResource(R.string.together_suggested_song, suggestion.fromUsername)
            }

            ApprovalDialog(
                title = title,
                subtitle = subtitle,
                onLater = { snoozeSuggestion(suggestion.suggestionId) },
                modifier = modifier,
                actions = {
                    DialogTextButton(
                        text = stringResource(R.string.together_play_now),
                        primary = true,
                        onClick = { ListenTogetherCoordinator.playSuggestionNow(suggestion) }
                    )
                    DialogTextButton(
                        text = stringResource(R.string.together_approve),
                        primary = true,
                        onClick = { ListenTogetherCoordinator.approveSuggestion(suggestion) }
                    )
                    DialogTextButton(
                        text = stringResource(R.string.together_reject),
                        onClick = { ListenTogetherCoordinator.rejectSuggestion(suggestion) }
                    )
                }
            )
        }

        joinRequest != null -> {
            ApprovalDialog(
                title = stringResource(R.string.together_join_requests),
                subtitle = stringResource(
                    R.string.together_wants_to_join,
                    joinRequest.username
                ),
                onLater = { snoozeJoin(joinRequest.userId) },
                modifier = modifier,
                actions = {
                    DialogTextButton(
                        text = stringResource(R.string.together_approve),
                        primary = true,
                        onClick = { ListenTogetherCoordinator.approveJoin(joinRequest.userId) }
                    )
                    DialogTextButton(
                        text = stringResource(R.string.together_reject),
                        onClick = { ListenTogetherCoordinator.rejectJoin(joinRequest.userId) }
                    )
                }
            )
        }
    }
}

@Composable
private fun ApprovalDialog(
    title: String,
    subtitle: String,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit
) {
    val (_, typography) = LocalAppearance.current

    DefaultDialog(
        onDismiss = onLater,
        modifier = modifier
    ) {
        BasicText(
            text = title,
            style = typography.s.semiBold.center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        BasicText(
            text = subtitle,
            style = typography.xs.medium.center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            actions()
            DialogTextButton(
                text = stringResource(R.string.together_later),
                onClick = onLater
            )
        }
    }
}
