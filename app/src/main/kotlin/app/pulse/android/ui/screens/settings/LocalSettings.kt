package app.pulse.android.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pulse.android.R
import app.pulse.android.preferences.LocalPreferences
import app.pulse.android.ui.screens.Route
import app.pulse.android.utils.toast
import app.pulse.core.ui.LocalAppearance

/**
 * On-device music library: which folders get scanned plus what the
 * automatic filters do. The scan itself is a single MediaStore query with
 * in-memory path filtering, so allowlists cost nothing in RAM or storage.
 */
@Route
@Composable
fun LocalSettings() {
    val context = LocalContext.current
    val (colorPalette, _) = LocalAppearance.current
    // Snapshot-backed holder: reading it here resubscribes, so the list
    // refreshes automatically when folders change.
    val folders = LocalPreferences.musicFolders

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (!LocalPreferences.addFolder(uri)) {
            context.toast(context.getString(R.string.invalid_folder))
        }
    }

    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val pop: () -> Unit = { backDispatcher?.onBackPressed() }

    SettingsCategoryScreen(
        title = stringResource(R.string.local_music),
        onBackClick = pop,
    ) {
        SettingsGroup(title = stringResource(R.string.music_folders)) {
            SettingsDescription(text = stringResource(R.string.music_folders_description))

            if (folders.isEmpty()) {
                SettingsDescription(text = stringResource(R.string.no_folders_added))
            } else {
                folders.sorted().forEach { folder ->
                    SettingsEntry(
                        title = folder,
                        text = stringResource(R.string.tap_to_remove),
                        onClick = { LocalPreferences.removeFolder(folder) },
                        trailingContent = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { LocalPreferences.removeFolder(folder) }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.trash),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                }
            }

            SettingsEntry(
                title = stringResource(R.string.add_folder),
                text = stringResource(R.string.add_folder_description),
                onClick = {
                    try {
                        folderPicker.launch(null)
                    } catch (_: ActivityNotFoundException) {
                        context.toast(context.getString(R.string.no_file_chooser_installed))
                    }
                }
            )
        }

        SettingsGroup(title = stringResource(R.string.automatic_filters)) {
            SettingsDescription(text = stringResource(R.string.automatic_filters_description))
        }
    }
}
