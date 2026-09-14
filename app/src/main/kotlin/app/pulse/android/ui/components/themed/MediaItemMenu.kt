package app.pulse.android.ui.components.themed

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import app.pulse.android.Database
import app.pulse.android.LocalPlayerServiceBinder
import app.pulse.android.R
import app.pulse.android.models.Info
import app.pulse.android.models.Playlist
import app.pulse.android.models.SongPlaylistMap
import app.pulse.android.preferences.PlayerPreferences
import app.pulse.android.query
import app.pulse.android.service.PrecacheService
import app.pulse.android.service.isLocal
import app.pulse.android.transaction
import app.pulse.android.ui.items.SongItem
import app.pulse.android.ui.screens.albumRoute
import app.pulse.android.ui.screens.artistRoute
import app.pulse.android.ui.screens.home.HideSongDialog
import app.pulse.android.utils.addNext
import app.pulse.android.utils.asMediaItem
import app.pulse.android.utils.enqueue
import app.pulse.android.utils.forcePlay
import app.pulse.android.utils.formatAsDuration
import app.pulse.android.utils.isCached
import app.pulse.android.utils.launchYouTubeMusic
import app.pulse.android.utils.medium
import app.pulse.android.utils.rememberEqualizerLauncher
import app.pulse.android.utils.semiBold
import app.pulse.android.utils.thumbnail
import app.pulse.android.utils.toast
import app.pulse.core.data.enums.PlaylistSortBy
import app.pulse.core.data.enums.SortOrder
import app.pulse.core.data.models.Song
import app.pulse.core.data.models.SongEntity
import app.pulse.core.data.utils.songBundle
import app.pulse.core.ui.Dimensions
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.favoritesIcon
import app.pulse.core.ui.utils.px
import app.pulse.core.ui.utils.roundedShape
import app.pulse.providers.innertube.models.NavigationEndpoint
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InHistoryMediaItemMenu(
    onDismiss: () -> Unit,
    song: Song,
    modifier: Modifier = Modifier
) {
    var isHiding by rememberSaveable { mutableStateOf(false) }

    if (isHiding) HideSongDialog(
        song = song,
        onDismiss = { isHiding = false },
        onConfirm = onDismiss
    )

    InHistoryMediaItemMenu(
        onDismiss = onDismiss,
        song = song,
        onHideFromDatabase = { isHiding = true },
        modifier = modifier
    )
}

@Composable
fun InHistoryMediaItemMenu(
    onDismiss: () -> Unit,
    song: Song,
    onHideFromDatabase: () -> Unit,
    modifier: Modifier = Modifier
) = NonQueuedMediaItemMenu(
    mediaItem = song.asMediaItem,
    onDismiss = onDismiss,
    onHideFromDatabase = onHideFromDatabase,
    modifier = modifier
)

@Composable
fun InPlaylistMediaItemMenu(
    onDismiss: () -> Unit,
    playlistId: Long,
    positionInPlaylist: Int,
    song: Song,
    modifier: Modifier = Modifier
) = NonQueuedMediaItemMenu(
    mediaItem = song.asMediaItem,
    onDismiss = onDismiss,
    onRemoveFromPlaylist = {
        transaction {
            Database.move(playlistId, positionInPlaylist, Int.MAX_VALUE)
            Database.delete(SongPlaylistMap(song.id, playlistId, Int.MAX_VALUE))
        }
    },
    modifier = modifier
)

@Composable
fun NonQueuedMediaItemMenu(
    onDismiss: () -> Unit,
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onHideFromDatabase: (() -> Unit)? = null,
    onRemoveFromQuickPicks: (() -> Unit)? = null
) {
    val binder = LocalPlayerServiceBinder.current

    BaseMediaItemMenu(
        mediaItem = mediaItem,
        onDismiss = onDismiss,
        onStartRadio = {
            binder?.stopRadio()
            binder?.player?.forcePlay(mediaItem)
            binder?.setupRadio(
                NavigationEndpoint.Endpoint.Watch(
                    videoId = mediaItem.mediaId,
                    playlistId = mediaItem.mediaMetadata.extras?.getString("playlistId")
                )
            )
        },
        onPlayNext = { binder?.player?.addNext(mediaItem) },
        onEnqueue = { binder?.player?.enqueue(mediaItem) },
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onHideFromDatabase = onHideFromDatabase,
        onRemoveFromQuickPicks = onRemoveFromQuickPicks,
        modifier = modifier
    )
}

@Composable
fun QueuedMediaItemMenu(
    onDismiss: () -> Unit,
    mediaItem: MediaItem,
    indexInQueue: Int?,
    modifier: Modifier = Modifier
) {
    val binder = LocalPlayerServiceBinder.current

    BaseMediaItemMenu(
        mediaItem = mediaItem,
        onDismiss = onDismiss,
        onRemoveFromQueue = indexInQueue?.let { index -> { binder?.player?.removeMediaItem(index) } },
        modifier = modifier
    )
}

@Composable
fun BaseMediaItemMenu(
    onDismiss: () -> Unit,
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
    onGoToEqualizer: (() -> Unit)? = null,
    onShowSleepTimer: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onEnqueue: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onHideFromDatabase: (() -> Unit)? = null,
    onRemoveFromQuickPicks: (() -> Unit)? = null,
    onShowSpeedDialog: (() -> Unit)? = null,
    onShowNormalizationDialog: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current
    val defaultEqualizerLauncher by rememberEqualizerLauncher(audioSessionId = { binder?.player?.audioSessionId ?: 0 })

    MediaItemMenu(
        mediaItem = mediaItem,
        onDismiss = onDismiss,
        onGoToEqualizer = onGoToEqualizer ?: defaultEqualizerLauncher,
        onShowSleepTimer = onShowSleepTimer ?: {},
        onStartRadio = onStartRadio,
        onPlayNext = onPlayNext,
        onEnqueue = onEnqueue,
        onAddToPlaylist = { playlist, position ->
            transaction {
                Database.insert(mediaItem)
                Database.insert(
                    SongPlaylistMap(
                        songId = mediaItem.mediaId,
                        playlistId = Database.insert(playlist).takeIf { it != -1L } ?: playlist.id,
                        position = position
                    )
                )
            }
        },
        onHideFromDatabase = onHideFromDatabase,
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onRemoveFromQueue = onRemoveFromQueue,
        onGoToAlbum = albumRoute::global,
        onGoToArtist = artistRoute::global,
        onShare = {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "https://music.youtube.com/watch?v=${mediaItem.mediaId}"
                )
            }

            context.startActivity(Intent.createChooser(sendIntent, null))
        },
        onRemoveFromQuickPicks = onRemoveFromQuickPicks,
        onShowSpeedDialog = onShowSpeedDialog ?: {},
        onShowNormalizationDialog = onShowNormalizationDialog ?: {},
        modifier = modifier
    )
}

@Composable
private fun SongMenuEntry(
    @DrawableRes icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    isSelected: Boolean = false,
    enabled: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val (colorPalette, typography) = LocalAppearance.current
    val rowColor = if (isSelected) colorPalette.accent else colorPalette.text
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "menu_entry_scale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.4f)
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(rowColor),
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = text,
                style = typography.xs.medium.copy(color = rowColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (secondaryText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                BasicText(
                    text = secondaryText,
                    style = typography.xxs.medium.copy(color = colorPalette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        trailingContent?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItemMenu(
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    onGoToEqualizer: (() -> Unit)? = null,
    onShowSleepTimer: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onEnqueue: (() -> Unit)? = null,
    onHideFromDatabase: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onAddToPlaylist: ((Playlist, Int) -> Unit)? = null,
    onGoToAlbum: ((String) -> Unit)? = null,
    onGoToArtist: ((String) -> Unit)? = null,
    onRemoveFromQuickPicks: (() -> Unit)? = null,
    onShowSpeedDialog: (() -> Unit)? = null,
    onShowNormalizationDialog: (() -> Unit)? = null
) {
    val (colorPalette, typography) = LocalAppearance.current
    val uriHandler = LocalUriHandler.current
    val binder = LocalPlayerServiceBinder.current
    val context = LocalContext.current

    val isLocal by remember { derivedStateOf { mediaItem.isLocal } }

    var likedAt by remember { mutableStateOf<Long?>(null) }
    var isBlacklisted by remember { mutableStateOf(false) }
    var sleepTimerMillisLeft by remember { mutableLongStateOf(0L) }

    val extras = remember(mediaItem) { mediaItem.mediaMetadata.extras?.songBundle }

    var albumInfo by remember {
        mutableStateOf(
            extras?.albumId?.let {
                Info(id = it, name = null)
            }
        )
    }

    var artistsInfo by remember {
        mutableStateOf(
            extras?.artistNames?.let { names ->
                extras.artistIds?.let { ids ->
                    names.zip(ids) { name, id -> Info(id, name) }
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (albumInfo == null) albumInfo = Database.songAlbumInfo(mediaItem.mediaId)
            if (artistsInfo == null) artistsInfo = Database.songArtistInfo(mediaItem.mediaId)

            launch {
                Database.likedAt(mediaItem.mediaId).collect { likedAt = it }
            }
            launch {
                Database.blacklisted(mediaItem.mediaId).collect { isBlacklisted = it }
            }
        }
    }

    LaunchedEffect(binder, binder?.sleepTimerMillisLeft) {
        binder?.sleepTimerMillisLeft?.collectLatest {
            sleepTimerMillisLeft = it ?: 0L
        } ?: run { sleepTimerMillisLeft = 0L }
    }

    val startRadioText = stringResource(R.string.start_radio)
    val playNextText = stringResource(R.string.play_next)
    val enqueueText = stringResource(R.string.enqueue)
    val addToPlaylistText = stringResource(R.string.add_to_playlist)
    val goToAlbumText = stringResource(R.string.go_to_album)
    val sleepTimerText = stringResource(R.string.sleep_timer)
    val speedPitchText = stringResource(R.string.playback_settings)
    val volumeBoostText = stringResource(R.string.volume_boost)
    val equalizerText = stringResource(R.string.equalizer)
    val preCacheText = stringResource(R.string.pre_cache)
    val watchYoutubeText = stringResource(R.string.watch_on_youtube)
    val youtubeMusicText = stringResource(R.string.open_in_youtube_music)
    val removeBlacklistText = stringResource(R.string.remove_from_blacklist)
    val addBlacklistText = stringResource(R.string.add_to_blacklist)
    val removeQueueText = stringResource(R.string.remove_from_queue)
    val removePlaylistText = stringResource(R.string.remove_from_playlist)
    val hideText = stringResource(R.string.hide)
    val hideQuickPicksText = stringResource(R.string.hide_from_quick_picks)
    val isSongCached = if (!isLocal) isCached(mediaItem.mediaId) else false

    var selectedActionId by rememberSaveable { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(colorPalette.background1)
    ) {
        // 1. Ambient Background Artwork (Big, like the player)
        val artworkUri = mediaItem.mediaMetadata.artworkUri
        if (artworkUri != null) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(48.dp)
                    .alpha(0.5f)
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colorPalette.background0.copy(alpha = 0.6f),
                            colorPalette.background1.copy(alpha = 0.85f),
                            colorPalette.background1
                        )
                    )
                )
        )

        // 2. Main Sheet Content Column
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            // Drag handle indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(colorPalette.textSecondary.copy(alpha = 0.35f))
                )
            }

            // Song Information Header — Minimal Left-Aligned
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                AsyncImage(
                    model = artworkUri?.thumbnail(Dimensions.thumbnails.song.px) ?: artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = mediaItem.mediaMetadata.title?.toString() ?: "",
                        style = typography.s.semiBold.copy(color = colorPalette.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    BasicText(
                        text = mediaItem.mediaMetadata.artist?.toString() ?: "",
                        style = typography.xs.medium.copy(color = colorPalette.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                IconButton(
                    icon = if (likedAt == null) R.drawable.heart_outline else R.drawable.heart,
                    color = colorPalette.favoritesIcon,
                    onClick = {
                        query {
                            if (
                                Database.like(
                                    songId = mediaItem.mediaId,
                                    likedAt = if (likedAt == null) System.currentTimeMillis() else null
                                ) != 0
                            ) return@query

                            Database.insert(mediaItem, SongEntity::toggleLike)
                        }
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .padding(all = 4.dp)
                )
            }

            // Subtle divider under header
            HorizontalDivider(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .alpha(0.3f)
            )

            // 3. Full Vertical List of Actions (Buttery Smooth Virtualized Scrolling)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 4.dp)
            ) {
                if (onStartRadio != null) {
                    item(key = "start_radio") {
                        SongMenuEntry(
                            icon = R.drawable.radio,
                            text = startRadioText,
                            onClick = {
                                onDismiss()
                                onStartRadio()
                            }
                        )
                    }
                }

                if (onPlayNext != null) {
                    item(key = "play_next") {
                        SongMenuEntry(
                            icon = R.drawable.play_skip_forward,
                            text = playNextText,
                            onClick = {
                                onDismiss()
                                onPlayNext()
                            }
                        )
                    }
                }

                if (onEnqueue != null) {
                    item(key = "enqueue") {
                        SongMenuEntry(
                            icon = R.drawable.enqueue,
                            text = enqueueText,
                            onClick = {
                                onDismiss()
                                onEnqueue()
                            }
                        )
                    }
                }

                if (onAddToPlaylist != null) {
                    item(key = "add_to_playlist") {
                        SongMenuEntry(
                            icon = R.drawable.playlist,
                            text = addToPlaylistText,
                            isSelected = selectedActionId == "add_to_playlist",
                            trailingContent = {
                                Image(
                                    painter = painterResource(R.drawable.chevron_forward),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            onClick = {
                                selectedActionId =
                                    if (selectedActionId == "add_to_playlist") null else "add_to_playlist"
                            }
                        )
                    }
                }

                if (onShowSpeedDialog != null) {
                    item(key = "playback_settings") {
                        val isCustomSpeedOrPitch = PlayerPreferences.speed != 1.0f || PlayerPreferences.pitch != 1.0f
                        SongMenuEntry(
                            icon = R.drawable.speed,
                            text = speedPitchText,
                            secondaryText = if (isCustomSpeedOrPitch) {
                                "%.2fx / %.2fx".format(PlayerPreferences.speed, PlayerPreferences.pitch)
                            } else null,
                            isSelected = selectedActionId == "playback_settings",
                            onClick = {
                                selectedActionId =
                                    if (selectedActionId == "playback_settings") null else "playback_settings"
                            }
                        )
                    }
                }

                if (onShowNormalizationDialog != null) {
                    item(key = "volume_boost") {
                        var boost by remember { mutableFloatStateOf(0f) }
                        LaunchedEffect(mediaItem.mediaId) {
                            Database.loudnessBoost(mediaItem.mediaId)
                                .distinctUntilChanged()
                                .collect { boost = it ?: 0f }
                        }
                        SongMenuEntry(
                            icon = R.drawable.volume_up,
                            text = volumeBoostText,
                            secondaryText = if (boost != 0f) stringResource(
                                R.string.format_db,
                                "%.2f".format(boost)
                            ) else null,
                            isSelected = selectedActionId == "volume_boost",
                            onClick = {
                                selectedActionId = if (selectedActionId == "volume_boost") null else "volume_boost"
                            }
                        )
                    }
                }

                if (onShowSleepTimer != null) {
                    item(key = "sleep_timer") {
                        SongMenuEntry(
                            icon = R.drawable.alarm,
                            text = sleepTimerText,
                            secondaryText = if (sleepTimerMillisLeft > 0L) {
                                stringResource(R.string.format_time_left, formatAsDuration(sleepTimerMillisLeft))
                            } else null,
                            isSelected = selectedActionId == "sleep_timer",
                            trailingContent = {
                                if (sleepTimerMillisLeft > 0L) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(colorPalette.accent.copy(alpha = 0.2f))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        BasicText(
                                            text = formatAsDuration(sleepTimerMillisLeft),
                                            style = typography.xxs.semiBold.copy(color = colorPalette.accent)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                selectedActionId = if (selectedActionId == "sleep_timer") null else "sleep_timer"
                            }
                        )
                    }
                }

                if (onGoToEqualizer != null) {
                    item(key = "equalizer") {
                        SongMenuEntry(
                            icon = R.drawable.equalizer,
                            text = equalizerText,
                            onClick = {
                                onDismiss()
                                onGoToEqualizer()
                            }
                        )
                    }
                }

                if (!isLocal) {
                    item(key = "share") {
                        SongMenuEntry(
                            icon = R.drawable.share_social,
                            text = stringResource(R.string.share),
                            onClick = {
                                onDismiss()
                                onShare()
                            }
                        )
                    }
                }

                if (!isLocal && onGoToAlbum != null && albumInfo != null) {
                    item(key = "go_to_album") {
                        SongMenuEntry(
                            icon = R.drawable.disc,
                            text = goToAlbumText,
                            secondaryText = albumInfo?.name,
                            onClick = {
                                onDismiss()
                                onGoToAlbum(albumInfo!!.id)
                            }
                        )
                    }
                }

                val artists = artistsInfo
                if (!isLocal && onGoToArtist != null && artists != null) {
                    artists.forEach { (id, name) ->
                        if (name != null) {
                            item(key = "go_to_artist_$id") {
                                SongMenuEntry(
                                    icon = R.drawable.person,
                                    text = stringResource(R.string.format_go_to_artist, name),
                                    onClick = {
                                        onDismiss()
                                        onGoToArtist(id)
                                    }
                                )
                            }
                        }
                    }
                }

                if (!isLocal) {
                    item(key = "watch_youtube") {
                        SongMenuEntry(
                            icon = R.drawable.play,
                            text = watchYoutubeText,
                            onClick = {
                                onDismiss()
                                binder?.player?.pause()
                                uriHandler.openUri("https://youtube.com/watch?v=${mediaItem.mediaId}")
                            }
                        )
                    }

                    item(key = "open_youtube_music") {
                        SongMenuEntry(
                            icon = R.drawable.musical_notes,
                            text = youtubeMusicText,
                            onClick = {
                                onDismiss()
                                binder?.player?.pause()
                                if (!launchYouTubeMusic(context, "watch?v=${mediaItem.mediaId}")) {
                                    context.toast(context.getString(R.string.youtube_music_not_installed))
                                }
                            }
                        )
                    }
                }

                if (!isLocal && !isSongCached) {
                    item(key = "pre_cache") {
                        SongMenuEntry(
                            icon = R.drawable.download,
                            text = preCacheText,
                            onClick = {
                                onDismiss()
                                runCatching {
                                    PrecacheService.scheduleCache(
                                        context = context.applicationContext,
                                        mediaItem = mediaItem
                                    )
                                }.exceptionOrNull()?.printStackTrace()
                            }
                        )
                    }
                }

                if (!mediaItem.isLocal) {
                    item(key = "blacklist") {
                        SongMenuEntry(
                            icon = R.drawable.remove_circle_outline,
                            text = if (isBlacklisted) removeBlacklistText else addBlacklistText,
                            onClick = {
                                transaction {
                                    Database.insert(mediaItem)
                                    Database.toggleBlacklist(mediaItem.mediaId)
                                }
                            }
                        )
                    }
                }

                if (onRemoveFromQueue != null) {
                    item(key = "remove_from_queue") {
                        SongMenuEntry(
                            icon = R.drawable.trash,
                            text = removeQueueText,
                            onClick = {
                                onDismiss()
                                onRemoveFromQueue()
                            }
                        )
                    }
                }

                if (onRemoveFromPlaylist != null) {
                    item(key = "remove_from_playlist") {
                        SongMenuEntry(
                            icon = R.drawable.trash,
                            text = removePlaylistText,
                            onClick = {
                                onDismiss()
                                onRemoveFromPlaylist()
                            }
                        )
                    }
                }

                if (onHideFromDatabase != null) {
                    item(key = "hide_from_database") {
                        SongMenuEntry(
                            icon = R.drawable.trash,
                            text = hideText,
                            onClick = onHideFromDatabase
                        )
                    }
                }

                if (!isLocal && onRemoveFromQuickPicks != null) {
                    item(key = "hide_from_quick_picks") {
                        SongMenuEntry(
                            icon = R.drawable.trash,
                            text = hideQuickPicksText,
                            onClick = {
                                onDismiss()
                                onRemoveFromQuickPicks()
                            }
                        )
                    }
                }
            }

            // 4. Inline Extra Controls (Rendered smoothly directly above the bottom dock)
            AnimatedVisibility(
                visible = selectedActionId != null,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f)
                ) + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)),
                exit = shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f)
                ) + fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colorPalette.background2.copy(alpha = 0.55f))
                ) {
                    AnimatedContent(
                        targetState = selectedActionId,
                        transitionSpec = {
                            (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) togetherWith
                                fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        },
                        label = "inline_panel_content"
                    ) { currentActionId ->
                        when (currentActionId) {
                            "playback_settings" -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                ) {
                                    var speed by remember { mutableFloatStateOf(PlayerPreferences.speed) }
                                    var pitch by remember { mutableFloatStateOf(PlayerPreferences.pitch) }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(
                                            text = stringResource(R.string.playback_speed),
                                            style = typography.xs.semiBold.copy(color = colorPalette.text)
                                        )
                                        BasicText(
                                            text = "%.2fx".format(speed),
                                            style = typography.xs.medium.copy(color = colorPalette.accent)
                                        )
                                    }
                                    Slider(
                                        state = speed,
                                        setState = {
                                            speed = it
                                            PlayerPreferences.speed = it
                                        },
                                        onSlideComplete = {},
                                        range = 0.25f..2.0f,
                                        steps = 35,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(
                                            text = stringResource(R.string.playback_pitch),
                                            style = typography.xs.semiBold.copy(color = colorPalette.text)
                                        )
                                        BasicText(
                                            text = "%.2fx".format(pitch),
                                            style = typography.xs.medium.copy(color = colorPalette.accent)
                                        )
                                    }
                                    Slider(
                                        state = pitch,
                                        setState = {
                                            pitch = it
                                            PlayerPreferences.pitch = it
                                        },
                                        onSlideComplete = {},
                                        range = 0.5f..2.0f,
                                        steps = 30,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                    )

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SecondaryTextButton(
                                            text = stringResource(R.string.reset),
                                            onClick = {
                                                speed = 1f
                                                pitch = 1f
                                                PlayerPreferences.speed = 1f
                                                PlayerPreferences.pitch = 1f
                                            }
                                        )
                                    }
                                }
                            }
                            "volume_boost" -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                ) {
                                    var boost by remember { mutableFloatStateOf(0f) }
                                    LaunchedEffect(mediaItem.mediaId) {
                                        Database.loudnessBoost(mediaItem.mediaId)
                                            .distinctUntilChanged()
                                            .collect { boost = it ?: 0f }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(
                                            text = stringResource(R.string.volume_boost),
                                            style = typography.xs.semiBold.copy(color = colorPalette.text)
                                        )
                                        BasicText(
                                            text = stringResource(R.string.format_db, "%.2f".format(boost)),
                                            style = typography.xs.medium.copy(color = colorPalette.accent)
                                        )
                                    }
                                    Slider(
                                        state = boost,
                                        setState = { boost = it },
                                        onSlideComplete = {
                                            transaction {
                                                Database.setLoudnessBoost(
                                                    songId = mediaItem.mediaId,
                                                    loudnessBoost = boost.takeUnless { it == 0f }
                                                )
                                            }
                                        },
                                        range = -20f..20f,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                    )

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SecondaryTextButton(
                                            text = stringResource(R.string.reset),
                                            onClick = {
                                                boost = 0f
                                                transaction {
                                                    Database.setLoudnessBoost(
                                                        songId = mediaItem.mediaId,
                                                        loudnessBoost = null
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            "sleep_timer" -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (sleepTimerMillisLeft == 0L) {
                                        var amount by remember { mutableIntStateOf(1) }
                                        val presets = listOf(15, 30, 45, 60, 90)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            presets.forEach { presetMin ->
                                                val isSelected = (amount * 10) == presetMin
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(
                                                            if (isSelected) colorPalette.accent else
                                                                colorPalette.background2.copy(alpha = 0.75f)
                                                        )
                                                        .clickable { amount = presetMin / 10 }
                                                        .padding(vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    BasicText(
                                                        text = "${presetMin}m",
                                                        style = typography.xs.semiBold.copy(
                                                            color = if (isSelected) colorPalette.onAccent
                                                            else colorPalette.text
                                                        )
                                                    )
                                                }
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(colorPalette.background2)
                                                    .clickable(enabled = amount > 1) { amount-- }
                                            ) {
                                                BasicText(
                                                    "-",
                                                    style = typography.s.semiBold.copy(color = colorPalette.text)
                                                )
                                            }

                                            BasicText(
                                                text = "${amount * 10} minutes",
                                                style = typography.s.semiBold.copy(color = colorPalette.text)
                                            )

                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(colorPalette.background2)
                                                    .clickable(enabled = amount < 60) { amount++ }
                                            ) {
                                                BasicText(
                                                    "+",
                                                    style = typography.s.semiBold.copy(color = colorPalette.text)
                                                )
                                            }
                                        }

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.padding(top = 6.dp)
                                        ) {
                                            SecondaryTextButton(
                                                text = stringResource(R.string.sleep_timer_until_song_end),
                                                onClick = {
                                                    runCatching {
                                                        binder?.startSleepTimer(
                                                            binder.player.duration - binder.player.contentPosition
                                                        )
                                                    }
                                                }
                                            )

                                            SecondaryTextButton(
                                                text = stringResource(R.string.set),
                                                alternative = true,
                                                onClick = {
                                                    binder?.startSleepTimer(amount * 10 * 60 * 1000L)
                                                }
                                            )
                                        }
                                    } else {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp)
                                        ) {
                                            BasicText(
                                                text = stringResource(
                                                    R.string.format_time_left,
                                                    formatAsDuration(sleepTimerMillisLeft)
                                                ),
                                                style = typography.s.semiBold.copy(color = colorPalette.accent)
                                            )

                                            SecondaryTextButton(
                                                text = stringResource(R.string.stop),
                                                onClick = { binder?.cancelSleepTimer() }
                                            )
                                        }
                                    }
                                }
                            }
                            "add_to_playlist" -> {
                                val playlistPreviews by remember {
                                    Database.playlistPreviews(
                                        sortBy = PlaylistSortBy.DateAdded,
                                        sortOrder = SortOrder.Descending
                                    )
                                }.collectAsState(initial = emptyList(), context = Dispatchers.IO)

                                var isCreatingNewPlaylist by rememberSaveable { mutableStateOf(false) }

                                if (isCreatingNewPlaylist && onAddToPlaylist != null) TextFieldDialog(
                                    hintText = stringResource(R.string.enter_playlist_name_prompt),
                                    onDismiss = { isCreatingNewPlaylist = false },
                                    onAccept = { text ->
                                        onDismiss()
                                        onAddToPlaylist(Playlist(name = text), 0)
                                    }
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                ) {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        item {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(colorPalette.accent.copy(alpha = 0.2f))
                                                    .clickable { isCreatingNewPlaylist = true }
                                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                            ) {
                                                Image(
                                                    painter = painterResource(R.drawable.add),
                                                    contentDescription = null,
                                                    colorFilter = ColorFilter.tint(colorPalette.accent),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                BasicText(
                                                    text = stringResource(R.string.new_playlist),
                                                    style = typography.xs.semiBold.copy(color = colorPalette.accent)
                                                )
                                            }
                                        }

                                        items(playlistPreviews) { preview ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(colorPalette.background1)
                                                    .clickable {
                                                        onDismiss()
                                                        onAddToPlaylist?.invoke(preview.playlist, preview.songCount)
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                            ) {
                                                Image(
                                                    painter = painterResource(R.drawable.playlist),
                                                    contentDescription = null,
                                                    colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                BasicText(
                                                    text = preview.playlist.name,
                                                    style = typography.xs.medium.copy(color = colorPalette.text)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                Spacer(modifier = Modifier.height(0.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}
