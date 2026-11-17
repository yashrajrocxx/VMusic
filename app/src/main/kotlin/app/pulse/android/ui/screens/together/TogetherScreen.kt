@file:Suppress("TooManyFunctions")

package app.pulse.android.ui.screens.together

import android.content.Intent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import app.pulse.android.utils.toast
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pulse.android.LocalPlayerAwareWindowInsets
import app.pulse.android.LocalPlayerServiceBinder
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import app.pulse.android.R
import app.pulse.android.listentogether.ListenTogetherCoordinator
import app.pulse.android.listentogether.ListenTogetherPreferences
import app.pulse.android.listentogether.TogetherUiState
import app.pulse.android.ui.components.LocalMenuState
import app.pulse.android.ui.components.themed.CircularProgressIndicator
import app.pulse.android.ui.components.themed.Header
import app.pulse.android.ui.components.themed.HeaderIconButton
import app.pulse.android.ui.components.themed.Menu
import app.pulse.android.ui.components.themed.MenuEntry
import app.pulse.android.ui.components.themed.SecondaryTextButton
import app.pulse.android.ui.components.themed.Slider
import app.pulse.android.ui.components.themed.TextFieldDialog
import app.pulse.android.ui.screens.settings.SettingsEntry
import app.pulse.android.ui.screens.settings.SettingsGroup
import app.pulse.android.utils.bold
import app.pulse.android.utils.center
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.dockBorder
import app.pulse.core.ui.dockSelected
import app.pulse.providers.listentogether.ConnectionState
import app.pulse.providers.listentogether.DriftHealth
import app.pulse.providers.listentogether.RoomMember
import app.pulse.providers.listentogether.TrackInfo
import coil3.compose.AsyncImage

/**
 * The "Together" home tab: create/join a synchronized listening room on a
 * metroserver instance and share a single playhead with friends, with live
 * drift-status feedback.
 *
 * Layout deliberately reuses the app's canonical [SettingsGroup]/[SettingsEntry]
 * cards so the screen follows the same theme in light, dark, dynamic and AMOLED
 * modes instead of drawing its own nested cards.
 */
@Composable
fun TogetherScreen(modifier: Modifier = Modifier) {
    val binder = LocalPlayerServiceBinder.current
    val state by ListenTogetherCoordinator.state.collectAsState()

    LaunchedEffect(binder) {
        binder?.let(ListenTogetherCoordinator::bind)
    }

    // Lifecycle gate for the coordinator's heavy loops (ticker, watchdog).
    DisposableEffect(Unit) {
        ListenTogetherCoordinator.setUiVisible(true)
        onDispose { ListenTogetherCoordinator.setUiVisible(false) }
    }

    val windowInsets = LocalPlayerAwareWindowInsets.current
    val topInset = windowInsets
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val bottomInset = windowInsets
        .asPaddingValues()
        .calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(topInset)
    ) {
        Header(
            title = stringResource(R.string.together),
            headerActions = {
                if (state.inRoom) HeaderIconButton(
                    onClick = { ListenTogetherCoordinator.requestSyncNow() },
                    icon = R.drawable.sync
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomInset + 16.dp)
        ) {
            state.error?.let { error ->
                Spacer(Modifier.height(4.dp))
                TogetherAlert(
                    text = error,
                    onClick = { ListenTogetherCoordinator.clearError() }
                )
                Spacer(Modifier.height(4.dp))
            }

            when {
                state.kickedReason != null -> KickedContent(state.kickedReason)
                state.inRoom -> RoomContent(state)
                else -> LobbyContent(state)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TogetherAlert(text: String, onClick: (() -> Unit)? = null) {
    val (colorPalette, typography) = LocalAppearance.current
    val shape = AlertShape

    BasicText(
        text = text,
        style = typography.xxs.semiBold.copy(color = colorPalette.red),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colorPalette.red.copy(alpha = 0.08f))
            .border(1.dp, colorPalette.red.copy(alpha = 0.35f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

// ---------------------------------------------------------------------------
// Lobby (not in a room)
// ---------------------------------------------------------------------------

@Composable
private fun LobbyContent(state: TogetherUiState) {
    val (colorPalette, typography) = LocalAppearance.current
    val context = LocalContext.current
    var isEditingUsername by remember { mutableStateOf(false) }
    var isJoining by remember { mutableStateOf(false) }
    val username = ListenTogetherCoordinator.userName()
    val isBusy = state.isCreatingRoom || state.isJoiningRoom
    val initialCode = remember(isJoining) {
        if (isJoining) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val candidate = ListenTogetherCoordinator.extractRoomCode(clipText)
            if (candidate.length in 6..10) candidate else ""
        } else ""
    }

    BasicText(
        text = stringResource(R.string.together_description),
        style = typography.xs.secondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )

    if (state.connectionState == ConnectionState.Connecting ||
        state.connectionState == ConnectionState.Handshaking
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp))
            BasicText(
                text = stringResource(R.string.together_connecting),
                style = typography.xxs.semiBold.copy(color = colorPalette.textSecondary)
            )
        }
    }

    SettingsGroup(title = stringResource(R.string.together_profile)) {
        SettingsEntry(
            title = stringResource(R.string.together_username),
            text = username.ifBlank { stringResource(R.string.together_username) },
            icon = R.drawable.person,
            onClick = { isEditingUsername = true }
        )
    }

    SettingsGroup(title = stringResource(R.string.together_room)) {
        SettingsEntry(
            title = stringResource(R.string.together_create_room),
            text = if (state.isCreatingRoom) {
                stringResource(R.string.together_creating)
            } else {
                stringResource(R.string.together_create_room_description)
            },
            icon = R.drawable.add,
            isEnabled = !isBusy,
            trailingContent = if (state.isCreatingRoom) {
                { CircularProgressIndicator(modifier = Modifier.size(18.dp)) }
            } else null,
            onClick = { ListenTogetherCoordinator.createRoom() }
        )

        SettingsEntry(
            title = stringResource(R.string.together_join_room),
            text = if (state.isJoiningRoom) {
                if (state.isAwaitingJoinApproval) {
                    stringResource(R.string.together_waiting_approval)
                } else {
                    stringResource(R.string.together_joining)
                }
            } else {
                stringResource(R.string.together_join_room_description)
            },
            icon = R.drawable.users,
            isEnabled = !isBusy,
            trailingContent = if (state.isJoiningRoom) {
                { CircularProgressIndicator(modifier = Modifier.size(18.dp)) }
            } else null,
            onClick = { isJoining = true }
        )
    }

    if (state.connectionState == ConnectionState.Disconnected &&
        ListenTogetherPreferences.sessionToken.isNotBlank()
    ) {
        Spacer(Modifier.height(8.dp))
        SecondaryTextButton(
            text = stringResource(R.string.together_reconnect),
            onClick = { ListenTogetherCoordinator.restoreSession() },
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (isEditingUsername) TextFieldDialog(
        hintText = stringResource(R.string.together_username),
        initialTextInput = username,
        onDismiss = { isEditingUsername = false },
        onAccept = {
            ListenTogetherCoordinator.setUserName(it.trim())
            isEditingUsername = false
        },
        isTextInputValid = { it.isNotBlank() }
    )

    if (isJoining) TextFieldDialog(
        hintText = stringResource(R.string.together_room_code),
        initialTextInput = initialCode,
        onDismiss = { isJoining = false },
        onAccept = {
            isJoining = false
            ListenTogetherCoordinator.joinRoom(it)
        },
        isTextInputValid = { it.isNotBlank() }
    )
}

// ---------------------------------------------------------------------------
// Room (in a room)
// ---------------------------------------------------------------------------

@Composable
private fun RoomContent(state: TogetherUiState) {
    val track = state.track
    // Remembered so unrelated state changes (suggestions, members, drift)
    // do not rebuild the queue on every recomposition.
    val queue = remember(state.queue, track?.id) {
        state.queue.filter { it.id != track?.id }
    }
    val visibleQueue = remember(queue) { queue.take(MAX_QUEUE_ROWS) }

    TogetherRoomHeaderCard(state = state)

    if (state.isUnsupportedLocalPlayback && track == null) {
        Spacer(Modifier.height(4.dp))
        TogetherAlert(
            text = stringResource(R.string.together_unsupported_playback_warning)
        )
        Spacer(Modifier.height(4.dp))
    }

    // Requests and suggestions stay at the top — the queue can be extremely
    // long, and burying an incoming join request below it would hide it.
    if (state.isHost && state.pendingJoinRequests.isNotEmpty()) {
        SettingsGroup(title = stringResource(R.string.together_join_requests)) {
            state.pendingJoinRequests.forEach { request ->
                JoinRequestRow(
                    username = request.username,
                    onApprove = { ListenTogetherCoordinator.approveJoin(request.userId) },
                    onReject = { ListenTogetherCoordinator.rejectJoin(request.userId) }
                )
            }
        }
    }

    if (state.isHost && state.suggestions.isNotEmpty()) {
        SettingsGroup(title = stringResource(R.string.together_suggestions)) {
            state.suggestions.forEach { suggestion ->
                SuggestionRow(
                    suggestion = suggestion,
                    onApprove = { ListenTogetherCoordinator.approveSuggestion(suggestion) },
                    onReject = { ListenTogetherCoordinator.rejectSuggestion(suggestion) },
                    onPlayNow = { ListenTogetherCoordinator.playSuggestionNow(suggestion) }
                )
            }
        }
    }

    if (track != null) {
        SettingsGroup(title = stringResource(R.string.together_now_playing)) {
            TogetherTrackCard(
                track = track,
                isBuffering = state.isBuffering,
                isHost = state.isHost,
                isPlaying = state.isPlaying
            )
        }
    } else {
        SettingsGroup(title = stringResource(R.string.together_now_playing)) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
            ) {
                BasicText(
                    text = stringResource(R.string.together_nothing_playing),
                    style = LocalAppearance.current.typography.xs.semiBold.copy(
                        color = LocalAppearance.current.colorPalette.text
                    )
                )
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = stringResource(R.string.together_nothing_playing_hint),
                    style = LocalAppearance.current.typography.xxs.secondary
                )
            }
        }
    }

    if (state.members.isNotEmpty()) {
        SettingsGroup(
            title = "${stringResource(R.string.together_members)} (${state.members.size})"
        ) {
            state.members.forEach { member ->
                TogetherMemberRow(
                    member = member,
                    selfUserId = state.userId,
                    viewerIsHost = state.isHost
                )
            }
        }
    }

    if (visibleQueue.isNotEmpty()) {
        SettingsGroup(
            title = "${stringResource(R.string.together_up_next)} (${queue.size})"
        ) {
            visibleQueue.forEach { queued ->
                QueueTrackItem(
                    track = queued,
                    isHost = state.isHost,
                    isWaiting = state.pendingPlayRequest == queued.id
                )
            }

            if (queue.size > visibleQueue.size) {
                BasicText(
                    text = stringResource(
                        R.string.together_more_tracks,
                        queue.size - visibleQueue.size
                    ),
                    style = LocalAppearance.current.typography.xxs.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** Keep the composed queue bounded even if the host mirrors a huge playlist. */
private const val MAX_QUEUE_ROWS = 50

private val AlertShape = RoundedCornerShape(16.dp)
private val RoomCodeShape = RoundedCornerShape(8.dp)
private val BadgeShape = RoundedCornerShape(50)
private val SeekbarShape = RoundedCornerShape(3.dp)

@Composable
private fun TogetherRoomHeaderCard(
    state: TogetherUiState
) {
    val (colorPalette, typography) = LocalAppearance.current
    val context = LocalContext.current
    // Join once per membership change instead of on every recomposition.
    val bufferingUsersText = remember(state.bufferingUsers) {
        state.bufferingUsers.joinToString(", ")
    }

    SettingsGroup(title = stringResource(R.string.together_room)) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoomCodeShape)
                        .clickable {
                            if (state.roomCode.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Room code", state.roomCode))
                                context.toast(context.getString(R.string.together_room_code_copied))
                            }
                        }
                ) {
                    BasicText(
                        text = stringResource(R.string.together_room_code),
                        style = typography.xxs.semiBold.secondary
                    )
                    Spacer(Modifier.height(2.dp))
                    BasicText(
                        text = state.roomCode,
                        style = typography.l.bold.copy(color = colorPalette.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colorPalette.dockSelected)
                        .border(1.dp, colorPalette.dockBorder, CircleShape)
                        .clickable {
                            if (state.roomCode.isNotBlank()) {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        context.getString(
                                            R.string.together_share_message,
                                            state.roomCode
                                        )
                                    )
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        send,
                                        context.getString(R.string.together_share_room)
                                    )
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.share_social),
                        contentDescription = stringResource(R.string.together_share_room),
                        colorFilter = ColorFilter.tint(colorPalette.text),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isHost) {
                    TogetherStatusBadge(
                        text = stringResource(R.string.together_host),
                        color = colorPalette.accent
                    )
                }

                if (state.track != null) {
                    if (!state.isPlaying) {
                        TogetherStatusBadge(
                            text = stringResource(R.string.together_paused),
                            color = colorPalette.textSecondary
                        )
                    } else {
                        val driftLabel = when (state.drift.health) {
                            DriftHealth.INSYNC -> stringResource(R.string.together_in_sync)
                            DriftHealth.MICRO_ADJUST -> stringResource(R.string.together_adjusting)
                            DriftHealth.HARD_SNAP -> stringResource(R.string.together_recovering)
                        }
                        val driftColor = when (state.drift.health) {
                            DriftHealth.INSYNC -> colorPalette.accent
                            // Yellow is too pale on the light palette; blue stays
                            // readable in every theme mode.
                            DriftHealth.MICRO_ADJUST -> colorPalette.blue
                            DriftHealth.HARD_SNAP -> colorPalette.red
                        }
                        TogetherStatusBadge(text = driftLabel, color = driftColor)
                    }
                }

                if (state.isBuffering) {
                    TogetherStatusBadge(
                        text = stringResource(R.string.together_buffering_self),
                        color = colorPalette.textSecondary
                    )
                }
            }

            if (state.bufferingUsers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = stringResource(
                        R.string.together_buffering,
                        bufferingUsersText
                    ),
                    style = typography.xxs.semiBold.copy(color = colorPalette.textSecondary)
                )
            }
        }

        // Long-press any song anywhere in the app to propose it for the
        // room (Play now / Play next in room) — no link pasting needed.
        SettingsEntry(
            title = stringResource(R.string.together_leave_room),
            icon = R.drawable.close,
            showDivider = true,
            onClick = { ListenTogetherCoordinator.leave() }
        )
    }
}

@Composable
private fun TogetherStatusBadge(text: String, color: Color) {
    val typography = LocalAppearance.current.typography
    val shape = BadgeShape

    BasicText(
        text = text,
        style = typography.xxs.semiBold.copy(color = color),
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.22f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun TogetherTrackCard(
    track: TrackInfo,
    isBuffering: Boolean,
    isHost: Boolean,
    isPlaying: Boolean
) {
    val (colorPalette, typography, _, thumbnailShape) = LocalAppearance.current
    // Dedicated hot playhead: only THIS card recomposes as the room
    // advances — the ticker never touches the shared room state.
    val playheadMs by ListenTogetherCoordinator.playhead.collectAsState()
    val durationMs = track.duration.coerceAtLeast(0L)

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = track.thumbnail.takeIf { it.isNotBlank() },
                error = painterResource(R.drawable.musical_notes),
                fallback = painterResource(R.drawable.musical_notes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(thumbnailShape)
                    .background(colorPalette.dockSelected)
            )

            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = track.title.ifBlank { track.id },
                    style = typography.s.semiBold.copy(color = colorPalette.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.artist.isNotBlank()) BasicText(
                    text = track.artist,
                    style = typography.xs.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.suggestedBy.isNotBlank()) BasicText(
                    text = stringResource(R.string.together_added_by, track.suggestedBy),
                    style = typography.xxs.semiBold.copy(color = colorPalette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isBuffering) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else if (isHost) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colorPalette.dockSelected)
                        .border(1.dp, colorPalette.dockBorder, CircleShape)
                        .clickable {
                            ListenTogetherCoordinator.setRoomPlaying(!isPlaying)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(
                            if (isPlaying) R.drawable.pause else R.drawable.play
                        ),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorPalette.text),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        if (durationMs > 0L) {
            Spacer(Modifier.height(12.dp))
            TogetherSeekbar(playheadMs, durationMs, isHost)

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(
                    text = formatPlaybackTime(playheadMs),
                    style = typography.xxs.copy(color = colorPalette.textSecondary)
                )
                BasicText(
                    text = "/ ${formatPlaybackTime(durationMs)}",
                    style = typography.xxs.copy(color = colorPalette.textSecondary)
                )
            }
        }
    }
}

/** Live room playhead: drag from the host, read-only for members. */
@Composable
private fun TogetherSeekbar(positionMs: Long, duration: Long, isHost: Boolean) {
    val colorPalette = LocalAppearance.current.colorPalette
    if (duration <= 0L) return

    // While the host drags, show the dragged position instead of the ticker.
    var draggingValue by remember { mutableStateOf<Float?>(null) }
    val shownPosition = (draggingValue?.toLong() ?: positionMs).coerceIn(0L, duration)

    if (isHost) {
        Slider(
            state = shownPosition.toFloat(),
            setState = { draggingValue = it },
            onSlideComplete = {
                draggingValue?.let { ListenTogetherCoordinator.seekRoom(it.toLong()) }
                draggingValue = null
            },
            range = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        // Read-only progress bar for members (the server rejects guest
        // playback actions; only the host drives the shared playhead).
        val fraction = (shownPosition.toFloat() / duration).coerceIn(0f, 1f)
        val animatedFraction by animateFloatAsState(
            targetValue = fraction,
            animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
            label = "togetherProgress"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(SeekbarShape)
                .background(colorPalette.dockSelected)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(6.dp)
                    .background(colorPalette.accent)
            )
        }
    }
}

private fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
private fun QueueTrackItem(track: TrackInfo, isHost: Boolean, isWaiting: Boolean) {
    val (colorPalette, typography, _, thumbnailShape) = LocalAppearance.current
    val subtitle = buildString {
        append(track.artist)
        if (track.duration > 0L) {
            if (isNotEmpty()) append(" · ")
            append(formatPlaybackTime(track.duration))
        }
    }

    val canTapRow = isHost || !isWaiting
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canTapRow) {
                if (isHost) {
                    ListenTogetherCoordinator.playQueueItem(track)
                } else if (!isWaiting) {
                    ListenTogetherCoordinator.requestPlay(track)
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        AsyncImage(
            model = track.thumbnail.takeIf { it.isNotBlank() },
            error = painterResource(R.drawable.musical_notes),
            fallback = painterResource(R.drawable.musical_notes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(thumbnailShape)
                .background(colorPalette.dockSelected)
        )

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = track.title.ifBlank { track.id },
                style = typography.xs.semiBold.copy(color = colorPalette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) BasicText(
                text = subtitle,
                style = typography.xxs.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (track.suggestedBy.isNotBlank()) BasicText(
                text = stringResource(R.string.together_added_by, track.suggestedBy),
                style = typography.xxs.semiBold.copy(color = colorPalette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isHost) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colorPalette.dockSelected)
                    .border(1.dp, colorPalette.dockBorder, CircleShape)
                    .clickable { ListenTogetherCoordinator.playQueueItem(track) },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.play),
                    contentDescription = stringResource(R.string.together_play_now),
                    colorFilter = ColorFilter.tint(colorPalette.text),
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            SecondaryTextButton(
                text = when {
                    isWaiting -> stringResource(R.string.together_request_pending)
                    else -> stringResource(R.string.together_request_play)
                },
                onClick = { ListenTogetherCoordinator.requestPlay(track) },
                enabled = !isWaiting,
                alternative = isWaiting
            )
        }
    }
}

@Composable
private fun TogetherMemberRow(member: RoomMember, selfUserId: String, viewerIsHost: Boolean) {
    val (colorPalette, typography) = LocalAppearance.current
    val menuState = LocalMenuState.current
    val isSelf = member.userId == selfUserId
    val canAct = (viewerIsHost && !isSelf) || isSelf

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colorPalette.accent.copy(alpha = 0.14f))
                .border(1.dp, colorPalette.accent.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = member.username.take(1).uppercase(),
                style = typography.m.semiBold.center.copy(color = colorPalette.accent)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BasicText(
                    text = member.username,
                    style = typography.xs.semiBold.copy(color = colorPalette.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(weight = 1f, fill = false)
                )
                if (isSelf) TogetherStatusBadge(
                    text = stringResource(R.string.together_you),
                    color = colorPalette.textSecondary
                )
            }
            if (!member.isConnected) BasicText(
                text = stringResource(R.string.together_offline),
                style = typography.xxs.semiBold.copy(color = colorPalette.textSecondary)
            )
        }

        if (member.isHost) {
            TogetherStatusBadge(
                text = stringResource(R.string.together_host),
                color = colorPalette.accent
            )
        }

        if (canAct) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = {
                            menuState.display {
                                Menu {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(colorPalette.accent.copy(alpha = 0.14f))
                                                .border(1.dp, colorPalette.accent.copy(alpha = 0.22f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            BasicText(
                                                text = member.username.take(1).uppercase(),
                                                style = typography.m.semiBold.center.copy(color = colorPalette.accent)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            BasicText(
                                                text = member.username,
                                                style = typography.s.bold.copy(color = colorPalette.text),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            BasicText(
                                                text = when {
                                                    member.isHost -> stringResource(R.string.together_host)
                                                    isSelf -> stringResource(R.string.together_you)
                                                    !member.isConnected -> stringResource(R.string.together_offline)
                                                    else -> stringResource(R.string.together_members)
                                                },
                                                style = typography.xxs.secondary
                                            )
                                        }
                                    }

                                    if (viewerIsHost && !isSelf) {
                                        MenuEntry(
                                            icon = R.drawable.person,
                                            text = stringResource(R.string.together_transfer_host),
                                            onClick = {
                                                menuState.hide()
                                                ListenTogetherCoordinator.transferHost(member.userId)
                                            }
                                        )
                                        MenuEntry(
                                            icon = R.drawable.close,
                                            text = stringResource(R.string.together_kick),
                                            onClick = {
                                                menuState.hide()
                                                ListenTogetherCoordinator.kickUser(member.userId)
                                            }
                                        )
                                    } else if (isSelf) {
                                        MenuEntry(
                                            icon = R.drawable.close,
                                            text = stringResource(R.string.together_leave_room),
                                            onClick = {
                                                menuState.hide()
                                                ListenTogetherCoordinator.leave()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ellipsis_horizontal),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun JoinRequestRow(
    username: String,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        BasicText(
            text = username,
            style = typography.xs.semiBold.copy(color = colorPalette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        SecondaryTextButton(
            text = stringResource(R.string.together_approve),
            onClick = onApprove
        )
        SecondaryTextButton(
            text = stringResource(R.string.together_reject),
            onClick = onReject,
            alternative = true
        )
    }
}

@Composable
private fun SuggestionRow(
    suggestion: app.pulse.android.listentogether.TogetherSuggestion,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onPlayNow: (() -> Unit)? = null
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = suggestion.track?.title?.takeIf { it.isNotBlank() }
                    ?: suggestion.track?.id.orEmpty(),
                style = typography.xs.semiBold.copy(color = colorPalette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = if (suggestion.isPlayRequest) {
                    stringResource(R.string.together_wants_to_play, suggestion.fromUsername)
                } else {
                    suggestion.fromUsername
                },
                style = typography.xxs.secondary,
                maxLines = 1
            )
        }
        if (onPlayNow != null) SecondaryTextButton(
            text = stringResource(R.string.together_play_now),
            onClick = onPlayNow
        )
        SecondaryTextButton(
            text = stringResource(R.string.together_approve),
            onClick = onApprove
        )
        SecondaryTextButton(
            text = stringResource(R.string.together_reject),
            onClick = onReject,
            alternative = true
        )
    }
}

@Composable
private fun KickedContent(reason: String?) {
    val (colorPalette, typography) = LocalAppearance.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 48.dp)
    ) {
        BasicText(
            text = stringResource(R.string.together_kicked),
            style = typography.m.bold.center.copy(color = colorPalette.text)
        )
        if (!reason.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = reason,
                style = typography.xs.secondary.center
            )
        }
        Spacer(Modifier.height(20.dp))
        SecondaryTextButton(
            text = stringResource(R.string.done),
            onClick = { ListenTogetherCoordinator.clearError() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
