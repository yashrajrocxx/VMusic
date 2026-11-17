package app.pulse.android.ui.screens.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pulse.android.Database
import app.pulse.android.R
import app.pulse.android.backup.AutoBackupManager
import app.pulse.android.backup.AutoBackupScheduler
import app.pulse.android.backup.BackupProtocol.BackupKind
import app.pulse.android.backup.PulseBackup
import app.pulse.android.preferences.DataPreferences
import app.pulse.android.query
import app.pulse.android.transaction
import app.pulse.android.ui.components.themed.CircularProgressIndicator
import app.pulse.android.ui.components.themed.ConfirmationDialog
import app.pulse.android.ui.components.themed.TimePickerDialog
import app.pulse.android.ui.screens.Route
import app.pulse.android.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.pulse.compose.routing.RouteHandler
import app.pulse.android.ui.screens.GlobalRoutes
import app.pulse.android.ui.components.themed.Scaffold

@SuppressLint("RestrictedApi")
@Route
@Composable
fun DatabaseSettings() {
    with(DataPreferences) {
        val context = LocalContext.current

        val eventsCount by remember { Database.eventsCount().distinctUntilChanged() }
            .collectAsState(initial = 0)

        val blacklistLength by remember { Database.blacklistLength().distinctUntilChanged() }
            .collectAsState(initial = 0)

        val scope = rememberCoroutineScope()

        val backupLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(mimeType = PulseBackup.exportMime())
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            scope.launch(Dispatchers.IO) {
                val ok = PulseBackup.exportNative(context, uri)
                withContext(Dispatchers.Main) {
                    context.toast(
                        if (ok) context.getString(R.string.backup_done)
                        else context.getString(R.string.backup_failed)
                    )
                }
            }
        }

        val metroBackupLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(mimeType = PulseBackup.exportMime())
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            scope.launch(Dispatchers.IO) {
                val ok = PulseBackup.exportMetro(context, uri)
                withContext(Dispatchers.Main) {
                    context.toast(
                        if (ok) context.getString(R.string.backup_done)
                        else context.getString(R.string.backup_failed)
                    )
                }
            }
        }

        var pendingRestore by remember { mutableStateOf<PulseBackup.Ready?>(null) }

        var showTimePicker by remember { mutableStateOf(false) }
        var backupNowInProgress by remember { mutableStateOf(false) }

        val restoreLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            scope.launch(Dispatchers.IO) {
                when (val result = PulseBackup.stageForRestore(context, uri)) {
                    is PulseBackup.StageResult.Ok -> withContext(Dispatchers.Main) {
                        pendingRestore = result.ready
                    }

                    is PulseBackup.StageResult.Rejected -> withContext(Dispatchers.Main) {
                        context.toast(
                            when (result.reason) {
                                PulseBackup.Failure.Incompatible ->
                                    context.getString(R.string.restore_failed_incompatible)

                                PulseBackup.Failure.Unrecognized ->
                                    context.getString(R.string.restore_failed_unrecognized)

                                else -> context.getString(R.string.restore_failed_io)
                            }
                        )
                    }
                }
            }
        }

        val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        val pop: () -> Unit = { backDispatcher?.onBackPressed() }

        SettingsCategoryScreen(
            title = stringResource(R.string.database),
            onBackClick = pop
        ) {
                        SettingsGroup(title = stringResource(R.string.cleanup)) {
                            SwitchSettingsEntry(
                                title = stringResource(R.string.pause_playback_history),
                                text = stringResource(R.string.pause_playback_history_description),
                                isChecked = pauseHistory,
                                onCheckedChange = { pauseHistory = !pauseHistory }
                            )

                            AnimatedVisibility(visible = pauseHistory) {
                                SettingsDescription(
                                    text = stringResource(R.string.pause_playback_history_warning),
                                    important = true
                                )
                            }

                            AnimatedVisibility(visible = !(pauseHistory && eventsCount == 0)) {
                                SettingsEntry(
                                    title = stringResource(R.string.reset_quick_picks),
                                    text = if (eventsCount > 0) pluralStringResource(
                                        R.plurals.format_reset_quick_picks_amount,
                                        eventsCount,
                                        eventsCount
                                    )
                                    else stringResource(R.string.quick_picks_empty),
                                    onClick = { query(Database::clearEvents) },
                                    isEnabled = eventsCount > 0
                                )
                            }

                            SwitchSettingsEntry(
                                title = stringResource(R.string.pause_playback_time),
                                text = stringResource(
                                    R.string.format_pause_playback_time_description,
                                    topListLength
                                ),
                                isChecked = pausePlaytime,
                                onCheckedChange = { pausePlaytime = !pausePlaytime }
                            )

                            SettingsEntry(
                                title = stringResource(R.string.reset_blacklist),
                                text = if (blacklistLength > 0) pluralStringResource(
                                    R.plurals.format_reset_blacklist_description,
                                    blacklistLength,
                                    blacklistLength
                                ) else stringResource(R.string.blacklist_empty),
                                isEnabled = blacklistLength > 0,
                                onClick = {
                                    transaction {
                                        Database.resetBlacklist()
                                    }
                                }
                            )
                        }
                        SettingsGroup(
                            title = stringResource(R.string.backup),
                            description = stringResource(R.string.backup_description)
                        ) {
                            val errorMsg = stringResource(R.string.no_file_chooser_installed)
                            val dateFormat = remember {
                                SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
                            }

                            SettingsEntry(
                                title = stringResource(R.string.backup_full),
                                text = stringResource(R.string.backup_full_description),
                                onClick = {
                                    try {
                                        backupLauncher.launch("Pulse_backup_${dateFormat.format(Date())}.zip")
                                    } catch (_: ActivityNotFoundException) {
                                        context.toast(errorMsg)
                                    }
                                }
                            )

                            SettingsEntry(
                                title = stringResource(R.string.backup_metro),
                                text = stringResource(R.string.backup_metro_description),
                                onClick = {
                                    try {
                                        metroBackupLauncher.launch("Pulse_${dateFormat.format(Date())}.backup")
                                    } catch (_: ActivityNotFoundException) {
                                        context.toast(errorMsg)
                                    }
                                }
                            )
                        }

                        val autoBackupFolderLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.OpenDocumentTree()
                        ) { uri ->
                            if (uri == null) return@rememberLauncherForActivityResult

                            runCatching {
                                AutoBackupManager.takePersistableAccess(context, uri)
                            }.onFailure {
                                context.toast(R.string.backup_failed)
                                return@rememberLauncherForActivityResult
                            }
                            autoBackupTreeUri = uri.toString()
                            autoBackupEnabled = true
                            AutoBackupScheduler.reschedule(context)
                        }

                        SettingsGroup(
                            title = stringResource(R.string.automatic_backup),
                            description = stringResource(R.string.automatic_backup_description)
                        ) {
                            val lastRunFormat = remember {
                                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                            }

                            // Storage-provider query: never on Main, reload on folder change.
                            var backupFolderName by remember { mutableStateOf<String?>(null) }
                            LaunchedEffect(autoBackupTreeUri) {
                                backupFolderName = withContext(Dispatchers.IO) {
                                    AutoBackupManager.folderName(context)
                                }
                            }

                            SwitchSettingsEntry(
                                title = stringResource(R.string.automatic_backup_enabled),
                                text = stringResource(R.string.automatic_backup_enabled_description),
                                isChecked = autoBackupEnabled,
                                onCheckedChange = { checked ->
                                    if (checked && autoBackupTreeUri.isBlank()) {
                                        try {
                                            autoBackupFolderLauncher.launch(null)
                                        } catch (_: ActivityNotFoundException) {
                                            context.toast(R.string.no_file_chooser_installed)
                                        }
                                    } else {
                                        autoBackupEnabled = checked
                                        AutoBackupScheduler.reschedule(context)
                                    }
                                }
                            )

                            SettingsEntry(
                                title = stringResource(R.string.backup_folder),
                                text = backupFolderName
                                    ?: stringResource(R.string.backup_folder_none),
                                showDivider = true,
                                onClick = {
                                    try {
                                        autoBackupFolderLauncher.launch(null)
                                    } catch (_: ActivityNotFoundException) {
                                        context.toast(R.string.no_file_chooser_installed)
                                    }
                                }
                            )

                            SettingsEntry(
                                title = stringResource(R.string.backup_time),
                                valueChip = String.format(
                                    Locale.US,
                                    "%02d:%02d",
                                    autoBackupHour,
                                    autoBackupMinute
                                ),
                                showDivider = true,
                                onClick = { showTimePicker = true }
                            )

                            EnumValueSelectorSettingsEntry(
                                title = stringResource(R.string.backup_frequency),
                                selectedValue = autoBackupFrequency,
                                onValueSelect = {
                                    autoBackupFrequency = it
                                    AutoBackupScheduler.reschedule(context)
                                },
                                valueText = { it.displayName() }
                            )

                            SettingsEntry(
                                title = stringResource(R.string.backup_now),
                                text = when {
                                    backupNowInProgress ->
                                        stringResource(R.string.auto_backup_running)

                                    autoBackupLastRun == 0L ->
                                        stringResource(R.string.auto_backup_never)

                                    autoBackupLastSuccess -> stringResource(
                                        R.string.auto_backup_last_run,
                                        lastRunFormat.format(Date(autoBackupLastRun))
                                    )

                                    else -> stringResource(
                                        R.string.auto_backup_last_failed,
                                        autoBackupLastError.ifBlank {
                                            stringResource(R.string.auto_backup_unknown_error)
                                        }
                                    )
                                },
                                showDivider = true,
                                isEnabled = autoBackupTreeUri.isNotBlank() && !backupNowInProgress,
                                trailingContent = if (backupNowInProgress) {
                                    {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    backupNowInProgress = true
                                    scope.launch(Dispatchers.IO) {
                                        val ok = AutoBackupManager.runBackup(
                                            context,
                                            requireEnabled = false
                                        )
                                        withContext(Dispatchers.Main) {
                                            backupNowInProgress = false
                                            context.toast(
                                                if (ok) context.getString(R.string.backup_done)
                                                else context.getString(R.string.backup_failed)
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        SettingsGroup(
                            title = stringResource(R.string.restore),
                            description = stringResource(R.string.restore_warning),
                            important = true
                        ) {
                            val errorMsg = stringResource(R.string.no_file_chooser_installed)

                            SettingsEntry(
                                title = stringResource(R.string.restore_any),
                                text = stringResource(R.string.restore_any_description),
                                onClick = {
                                    try {
                                        restoreLauncher.launch(PulseBackup.restoreMimeTypes())
                                    } catch (_: ActivityNotFoundException) {
                                        context.toast(errorMsg)
                                    }
                                }
                            )
                        }

                        pendingRestore?.let { ready ->
                            val preview = ready.preview
                            val accountLine = if (preview.hasAuthData) {
                                stringResource(
                                    R.string.restore_preview_account,
                                    preview.accountEmail ?: preview.accountName ?: "Google"
                                )
                            } else {
                                stringResource(R.string.restore_preview_no_account)
                            }
                            ConfirmationDialog(
                                text = listOf(
                                    stringResource(
                                        when (preview.kind) {
                                            BackupKind.PulseZip ->
                                                R.string.restore_preview_source_pulse

                                            BackupKind.LegacyDb ->
                                                R.string.restore_preview_source_legacy

                                            else -> R.string.restore_preview_source_metro
                                        }
                                    ),
                                    stringResource(R.string.restore_preview_songs, preview.songs) +
                                        "  " + stringResource(R.string.restore_preview_liked, preview.liked),
                                    stringResource(R.string.restore_preview_playlists, preview.playlists) +
                                        "  " + stringResource(R.string.restore_preview_history, preview.events) +
                                        "  " + stringResource(R.string.restore_preview_searches, preview.searches),
                                    accountLine,
                                    stringResource(R.string.restore_preview_warning)
                                ).joinToString("\n"),
                                onDismiss = { pendingRestore = null },
                                onConfirm = {
                                    pendingRestore = null
                                    scope.launch(Dispatchers.IO) {
                                        val ok = PulseBackup.restore(context, ready.staged)
                                        if (!ok) withContext(Dispatchers.Main) {
                                            context.toast(context.getString(R.string.restore_failed_io))
                                        }
                                    }
                                },
                                confirmText = stringResource(R.string.restore)
                            )
                        }

                        if (showTimePicker) TimePickerDialog(
                            initialHour = autoBackupHour,
                            initialMinute = autoBackupMinute,
                            onDismiss = { showTimePicker = false },
                            onAccept = { hour, minute ->
                                autoBackupHour = hour
                                autoBackupMinute = minute
                                if (autoBackupEnabled) {
                                    AutoBackupScheduler.reschedule(context)
                                }
                            }
                        )
                    }
                }
}
