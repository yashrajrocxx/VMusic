package app.pulse.android.ui.screens.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pulse.android.Database
import app.pulse.android.R
import app.pulse.core.data.models.Song
import app.pulse.core.data.models.toEntity
import app.pulse.android.preferences.LocalPreferences
import app.pulse.android.preferences.OrderPreferences
import app.pulse.android.service.LOCAL_KEY_PREFIX
import app.pulse.android.transaction
import app.pulse.android.ui.components.themed.SecondaryTextButton
import app.pulse.android.ui.screens.Route
import app.pulse.android.utils.AudioMediaCursor
import app.pulse.android.utils.hasPermission
import app.pulse.android.utils.medium
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.utils.isAtLeastAndroid13
import app.pulse.core.ui.utils.isCompositionLaunched
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val permission = if (isAtLeastAndroid13) Manifest.permission.READ_MEDIA_AUDIO
else Manifest.permission.READ_EXTERNAL_STORAGE

@Route
@Composable
fun HomeLocalSongs(onSearchClick: () -> Unit) = with(OrderPreferences) {
    val context = LocalContext.current
    val (_, typography) = LocalAppearance.current

    var hasPermission by remember(isCompositionLaunched()) {
        mutableStateOf(context.applicationContext.hasPermission(permission))
    }

    // Restart the scan when the folder allowlist changes: MediaStore version
    // polling alone would never notice a settings change.
    val musicFolders = LocalPreferences.musicFolders

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasPermission = it }
    )

    LaunchedEffect(hasPermission, musicFolders) {
        if (hasPermission) context.musicFilesAsFlow(this).collect()
    }

    if (hasPermission) HomeSongs(
        onSearchClick = onSearchClick,
        songProvider = {
            Database.songs(
                sortBy = localSongSortBy,
                sortOrder = localSongSortOrder,
                isLocal = true
            ).map { songs -> songs.filter { it.durationText != "0:00" } }
        },
        sortBy = localSongSortBy,
        setSortBy = { localSongSortBy = it },
        sortOrder = localSongSortOrder,
        setSortOrder = { localSongSortOrder = it },
        title = stringResource(R.string.local)
    ) else {
        LaunchedEffect(Unit) { launcher.launch(permission) }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                text = stringResource(R.string.media_permission_declined),
                modifier = Modifier.fillMaxWidth(0.75f),
                style = typography.m.medium
            )
            Spacer(modifier = Modifier.height(12.dp))
            SecondaryTextButton(
                text = stringResource(R.string.open_settings),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }
            )
        }
    }
}

/**
 * Tracks shorter than this are skipped: recorders and voice notes that slip
 * through IS_MUSIC plus ringtones/notification blips. Real songs are
 * virtually always longer; the folder rules below handle the long ones.
 */
private const val MIN_TRACK_DURATION_MS = 30_000

/**
 * Directory segments that mark system/non-music audio (call recordings,
 * ringtones, alarms...). Matched per path segment so a song merely *named*
 * "Record" is unaffected. An explicitly user-added folder always wins over
 * these exclusions.
 */
private fun isSystemAudioDir(audioPath: String?): Boolean {
    if (audioPath.isNullOrBlank()) return false
    return audioPath.split('/').any { segment ->
        val s = segment.trim().lowercase()
        "record" in s || "callrecord" in s ||
            s in setOf("ringtones", "ringtone", "notifications", "notification", "alarms", "alarm")
    }
}

/**
 * Whether a storage location falls inside one of the user-picked folders.
 * Handles both RELATIVE_PATH dirs ("Music/") and legacy absolute file paths.
 */
private fun isInFolders(audioPath: String?, folders: Set<String>): Boolean {
    if (audioPath.isNullOrBlank()) return false
    return folders.any { folder ->
        val f = folder.trim('/').lowercase()
        if (f.isEmpty()) return@any false
        if (audioPath.startsWith("/")) {
            "/$f/" in audioPath.lowercase()
        } else {
            val dir = audioPath.trimEnd('/')
            dir.equals(f, ignoreCase = true) || dir.startsWith("$f/", ignoreCase = true)
        }
    }
}

fun Context.musicFilesAsFlow(scope: CoroutineScope): StateFlow<List<Song>> = flow {
    var version: String? = null

    while (currentCoroutineContext().isActive) {
        val newVersion = MediaStore.getVersion(applicationContext)

        if (version != newVersion) {
            version = newVersion

            AudioMediaCursor.query(contentResolver) {
                buildList {
                    // Read once per scan: snapshot the folder allowlist so a
                    // mid-scan settings change can't half-apply.
                    val folders = LocalPreferences.musicFolders
                    while (next()) {
                        if (!isMusic || duration < MIN_TRACK_DURATION_MS) continue
                        val path = audioPath
                        if (folders.isNotEmpty()) {
                            if (!isInFolders(path, folders)) continue
                        } else if (isSystemAudioDir(path)) continue
                        add(
                            Song(
                                id = "$LOCAL_KEY_PREFIX$id",
                                title = name,
                                artistsText = artist,
                                durationText = duration.milliseconds.toComponents { minutes, seconds, _ ->
                                    "$minutes:${seconds.toString().padStart(2, '0')}"
                                },
                                thumbnailUrl = albumUri.toString()
                            )
                        )
                    }
                }
            }?.let { emit(it) }
        }
        delay(5.seconds)
    }
}.distinctUntilChanged()
    .onEach { songs -> transaction { songs.forEach { Database.insert(it.toEntity()) } } }
    // Upstream stays on IO regardless of the sharing scope (the collector is
    // Main): MediaStore query + DB inserts must never run on the UI thread.
    .flowOn(Dispatchers.IO)
    // Scoped sharing: polling stops a few seconds after the screen is left
    // instead of running for the whole app lifetime on a static scope.
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), listOf())
