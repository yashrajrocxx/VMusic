package app.pulse.android.ui.screens.recognition

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.Intent
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.pulse.android.Database
import app.pulse.android.LocalPlayerAwareWindowInsets
import app.pulse.android.R
import app.pulse.android.models.Playlist
import app.pulse.android.models.SongPlaylistMap
import app.pulse.android.query
import app.pulse.android.recognition.MusicRecognitionService
import app.pulse.android.shazamkit.models.RecognitionResult
import app.pulse.android.shazamkit.models.RecognitionStatus
import app.pulse.android.transaction
import app.pulse.android.ui.components.themed.DefaultDialog
import app.pulse.android.ui.components.themed.Header
import app.pulse.android.ui.components.themed.SecondaryTextButton
import app.pulse.android.ui.components.themed.TextFieldDialog
import app.pulse.android.ui.screens.searchResultRoute
import app.pulse.android.utils.bold
import app.pulse.android.utils.center
import app.pulse.android.utils.medium
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
import app.pulse.android.utils.toast
import app.pulse.core.data.models.SongEntity
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.utils.roundedShape
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun RecognitionScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val (colorPalette, typography) = LocalAppearance.current
    val coroutineScope = rememberCoroutineScope()
    val recognitionStatus by MusicRecognitionService.recognitionStatus.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasPermission = isGranted
            if (isGranted) {
                coroutineScope.launch {
                    MusicRecognitionService.recognize(context)
                }
            }
        }

    DisposableEffect(Unit) {
        onDispose {
            MusicRecognitionService.cancelRecognition()
        }
    }

    fun startRecognition() {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            MusicRecognitionService.startRecognition(coroutineScope, context)
        }
    }

    val insets = LocalPlayerAwareWindowInsets.current
        .only(WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal)
        .asPaddingValues()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(insets),
    ) {
        Header(title = stringResource(R.string.recognize))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = recognitionStatus,
                label = "recognition_content",
            ) { status ->
                when (status) {
                    is RecognitionStatus.Success -> {
                        ResultCard(
                            result = status.result,
                            onPlayClick = {
                                searchResultRoute.global("${status.result.title} ${status.result.artist}")
                            },
                            onTryAgain = {
                                MusicRecognitionService.reset()
                                startRecognition()
                            },
                        )
                    }

                    is RecognitionStatus.NoMatch -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(colorPalette.background1),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.mic),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                                    modifier = Modifier.size(32.dp),
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            BasicText(
                                text = stringResource(R.string.no_match_found),
                                style = typography.m.bold.copy(color = colorPalette.text),
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            BasicText(
                                text = stringResource(R.string.recognize_music_description),
                                style = typography.xs.secondary.center,
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            SecondaryTextButton(
                                text = stringResource(R.string.try_again),
                                onClick = {
                                    MusicRecognitionService.reset()
                                    startRecognition()
                                },
                            )
                        }
                    }

                    is RecognitionStatus.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            BasicText(
                                text = status.message,
                                style = typography.s.medium.copy(
                                    color = colorPalette.red,
                                    textAlign = TextAlign.Center,
                                ),
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            SecondaryTextButton(
                                text = stringResource(R.string.try_again),
                                onClick = {
                                    MusicRecognitionService.reset()
                                    startRecognition()
                                },
                            )
                        }
                    }

                    else -> {
                        val isListening = status is RecognitionStatus.Listening || status is RecognitionStatus.Processing
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            RecognitionRadarButton(
                                isListening = isListening,
                                onClick = {
                                    if (!isListening) {
                                        startRecognition()
                                    }
                                },
                            )

                            Spacer(modifier = Modifier.height(32.dp))

                            BasicText(
                                text = when (status) {
                                    is RecognitionStatus.Listening -> stringResource(R.string.listening)
                                    is RecognitionStatus.Processing -> stringResource(R.string.identifying)
                                    else -> stringResource(R.string.tap_to_recognize)
                                },
                                style = typography.l.semiBold.copy(color = colorPalette.text),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            BasicText(
                                text = stringResource(R.string.recognize_music_description),
                                style = typography.xs.secondary.center,
                            )

                            if (isListening) {
                                Spacer(modifier = Modifier.height(24.dp))
                                SecondaryTextButton(
                                    text = stringResource(R.string.cancel),
                                    onClick = { MusicRecognitionService.cancelRecognition() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecognitionRadarButton(
    isListening: Boolean,
    onClick: () -> Unit,
) {
    val (colorPalette) = LocalAppearance.current
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.28f else 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = if (isListening) 0.5f else 0.15f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring_alpha",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(200.dp),
    ) {
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .scale(pulseScale)
                    .graphicsLayer { alpha = ringAlpha }
                    .background(colorPalette.accent.copy(alpha = 0.25f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(pulseScale * 0.92f)
                    .graphicsLayer { alpha = ringAlpha * 1.4f }
                    .background(colorPalette.accent.copy(alpha = 0.35f), CircleShape),
            )
        }

        Box(
            modifier = Modifier
                .size(110.dp)
                .scale(if (isListening) pulseScale * 0.95f else 1f)
                .clip(CircleShape)
                .background(if (isListening) colorPalette.accent else colorPalette.background2)
                .pressScale(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.mic),
                contentDescription = stringResource(R.string.recognize),
                colorFilter = ColorFilter.tint(
                    if (isListening) colorPalette.onAccent else colorPalette.accent,
                ),
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

@Composable
private fun ResultCard(
    result: RecognitionResult,
    onPlayClick: () -> Unit,
    onTryAgain: () -> Unit,
) {
    val context = LocalContext.current
    val (colorPalette, typography) = LocalAppearance.current

    val songId = remember(result) {
        result.youtubeVideoId ?: "shazam_${result.trackId}"
    }

    val coverUrl = result.coverArtHqUrl ?: result.coverArtUrl

    val mediaItem = remember(result) {
        MediaItem.Builder()
            .setMediaId(songId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(result.title)
                    .setArtist(result.artist)
                    .setAlbumTitle(result.album)
                    .setArtworkUri(coverUrl?.toUri())
                    .build()
            )
            .build()
    }

    // Like state — observed from Room database
    val likedAt by Database.likedAt(songId).collectAsState(initial = null, context = Dispatchers.IO)
    val isLiked = likedAt != null

    var showPlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var isCreatingNewPlaylist by rememberSaveable { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(24.dp.roundedShape)
            .background(colorPalette.background1)
            .padding(24.dp),
    ) {
        // Album art
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(160.dp)
                    .clip(16.dp.roundedShape),
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Title
        BasicText(
            text = result.title,
            style = typography.m.bold.copy(
                color = colorPalette.text,
                textAlign = TextAlign.Center,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Artist · Album
        val artistAlbum = buildString {
            append(result.artist)
            result.album?.let { append(" · $it") }
        }
        BasicText(
            text = artistAlbum,
            style = typography.s.semiBold.copy(
                color = colorPalette.textSecondary,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons row: [♥ Like] [☰+ Playlist] [▶ Play Now] [↗ Share]
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Like button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (isLiked) colorPalette.accent.copy(alpha = 0.2f) else colorPalette.background2)
                    .clickable {
                        query {
                            if (
                                Database.like(
                                    songId = mediaItem.mediaId,
                                    likedAt = if (isLiked) null else System.currentTimeMillis()
                                ) != 0
                            ) return@query

                            Database.insert(mediaItem, SongEntity::toggleLike)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(
                        if (isLiked) R.drawable.heart else R.drawable.heart_outline
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(
                        if (isLiked) colorPalette.accent else colorPalette.text
                    ),
                    modifier = Modifier.size(22.dp),
                )
            }

            // Save to Playlist button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(colorPalette.background2)
                    .clickable { showPlaylistDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.playlist),
                    contentDescription = stringResource(R.string.add_to_playlist),
                    colorFilter = ColorFilter.tint(colorPalette.text),
                    modifier = Modifier.size(22.dp),
                )
            }

            // Play Now — accent pill button with icon + text
            Row(
                modifier = Modifier
                    .height(46.dp)
                    .clip(CircleShape)
                    .background(colorPalette.accent)
                    .clickable(onClick = onPlayClick)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.onAccent),
                    modifier = Modifier.size(18.dp),
                )
                BasicText(
                    text = stringResource(R.string.play_now),
                    style = typography.s.semiBold.copy(color = colorPalette.onAccent),
                )
            }

            // Share button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(colorPalette.background2)
                    .clickable {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                result.shazamUrl ?: "${result.title} - ${result.artist}"
                            )
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.share_social),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.text),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Secondary: Try Again
        SecondaryTextButton(
            text = stringResource(R.string.try_again),
            onClick = onTryAgain,
        )
    }

    // Playlist Selection Dialog
    if (showPlaylistDialog) {
        val playlistPreviews by Database.playlistPreviewsByNameAsc()
            .collectAsState(initial = emptyList(), context = Dispatchers.IO)

        DefaultDialog(
            onDismiss = { showPlaylistDialog = false }
        ) {
            BasicText(
                text = stringResource(R.string.add_to_playlist),
                style = typography.m.bold.copy(color = colorPalette.text),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // New playlist option
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(colorPalette.accent.copy(alpha = 0.15f))
                    .clickable {
                        showPlaylistDialog = false
                        isCreatingNewPlaylist = true
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.add),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.accent),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                BasicText(
                    text = stringResource(R.string.new_playlist),
                    style = typography.s.semiBold.copy(color = colorPalette.accent)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Existing playlists list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(weight = 1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                playlistPreviews.forEach { preview ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(12.dp.roundedShape)
                            .clickable {
                                transaction {
                                    Database.insert(mediaItem)
                                    Database.insert(
                                        SongPlaylistMap(
                                            songId = mediaItem.mediaId,
                                            playlistId = preview.playlist.id,
                                            position = preview.songCount
                                        )
                                    )
                                }
                                showPlaylistDialog = false
                                context.toast("Added to ${preview.playlist.name}")
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.playlist),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText(
                                text = preview.playlist.name,
                                style = typography.s.medium.copy(color = colorPalette.text),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            BasicText(
                                text = "${preview.songCount} songs",
                                style = typography.xs.secondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    if (isCreatingNewPlaylist) {
        TextFieldDialog(
            hintText = stringResource(R.string.enter_playlist_name_prompt),
            onDismiss = { isCreatingNewPlaylist = false },
            onAccept = { text ->
                if (text.isNotBlank()) {
                    transaction {
                        val newId = Database.insert(Playlist(name = text.trim()))
                        Database.insert(mediaItem)
                        Database.insert(
                            SongPlaylistMap(
                                songId = mediaItem.mediaId,
                                playlistId = newId,
                                position = 0
                            )
                        )
                    }
                    context.toast("Added to ${text.trim()}")
                }
                isCreatingNewPlaylist = false
            }
        )
    }
}

@Composable
private fun Modifier.pressScale(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press_scale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}
