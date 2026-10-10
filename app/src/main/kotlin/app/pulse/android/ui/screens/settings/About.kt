package app.pulse.android.ui.screens.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pulse.android.BuildConfig
import app.pulse.android.R
import app.pulse.android.preferences.DataPreferences
import app.pulse.android.service.findBestApkAsset
import app.pulse.android.service.getNewerVersion
import app.pulse.android.ui.components.themed.CircularProgressIndicator
import app.pulse.android.ui.screens.Route
import app.pulse.android.utils.hasPermission
import app.pulse.android.utils.toast
import app.pulse.core.data.utils.version
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.utils.isAtLeastAndroid13
import app.pulse.core.ui.utils.isCompositionLaunched
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VERSION_NAME = BuildConfig.VERSION_NAME.substringBeforeLast("-")
private const val REPO_OWNER = "yashrajrocxx"
private const val REPO_NAME = "VMusic"

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private val permission = Manifest.permission.POST_NOTIFICATIONS

@Route
@Composable
fun About() {
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val pop: () -> Unit = { backDispatcher?.onBackPressed() }

    SettingsCategoryScreen(
        title = stringResource(R.string.about),
        description = stringResource(
            R.string.format_version_credits,
            VERSION_NAME
        ),
        onBackClick = pop
    ) {
                    val (_, typography) = LocalAppearance.current
                    val uriHandler = LocalUriHandler.current
                    val context = LocalContext.current

                    var hasPermission by remember(isCompositionLaunched()) {
                        mutableStateOf(
                            if (isAtLeastAndroid13) context.applicationContext.hasPermission(permission)
                            else true
                        )
                    }

                    val launcher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                        onResult = { hasPermission = it }
                    )

                    SettingsGroup(title = stringResource(R.string.social)) {
                        SettingsEntry(
                            title = stringResource(R.string.github),
                            text = stringResource(R.string.view_source),
                            onClick = {
                                uriHandler.openUri("https://github.com/yashrajrocxx/VMusic")
                            }
                        )
                    }

                    SettingsGroup(title = stringResource(R.string.special_thanks)) {
                        SettingsEntry(
                            title = stringResource(R.string.credits_vimusic_title),
                            text = stringResource(R.string.credits_vimusic_desc),
                            onClick = {
                                uriHandler.openUri("https://github.com/vfsfitvnm/ViMusic")
                            }
                        )

                        SettingsEntry(
                            title = stringResource(R.string.credits_innertune_title),
                            text = stringResource(R.string.credits_innertune_desc),
                            onClick = {
                                uriHandler.openUri("https://github.com/z-huang/InnerTune")
                            }
                        )

                        SettingsEntry(
                            title = stringResource(R.string.credits_metrolist_title),
                            text = stringResource(R.string.credits_metrolist_desc),
                            onClick = {
                                uriHandler.openUri("https://github.com/mostafa-a-elhariry/metrolist")
                            }
                        )

                        SettingsEntry(
                            title = "Instagram",
                            text = "@yashraj.rocxx",
                            onClick = {
                                uriHandler.openUri("https://www.instagram.com/yashraj.rocxx/")
                            }
                        )
                    }

                    SettingsGroup(title = stringResource(R.string.contact)) {
                        SettingsEntry(
                            title = stringResource(R.string.report_bug),
                            text = stringResource(R.string.report_bug_description),
                            onClick = {
                                uriHandler.openUri(
                                    @Suppress("MaximumLineLength")
                                    "https://github.com/$REPO_OWNER/$REPO_NAME/issues/new?assignees=&labels=bug&template=bug_report.yaml"
                                )
                            }
                        )

                        SettingsEntry(
                            title = stringResource(R.string.request_feature),
                            text = stringResource(R.string.redirect_github),
                            onClick = {
                                uriHandler.openUri(
                                    @Suppress("MaximumLineLength")
                                    "https://github.com/$REPO_OWNER/$REPO_NAME/issues/new?assignees=&labels=enhancement&template=feature_request.md"
                                )
                            }
                        )
                    }


                    SettingsGroup(title = stringResource(R.string.version)) {
                        var checkingUpdate by rememberSaveable { mutableStateOf(false) }
                        val updateScope = rememberCoroutineScope()

                        SettingsEntry(
                            title = stringResource(R.string.check_new_version),
                            text = if (checkingUpdate) stringResource(R.string.checking_for_updates)
                            else stringResource(R.string.current_version, VERSION_NAME),
                            onClick = {
                                if (!checkingUpdate) {
                                    checkingUpdate = true
                                    updateScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { VERSION_NAME.version.getNewerVersion() }.getOrNull()
                                        }
                                        val release = result?.getOrNull()
                                        when {
                                            result == null || result.isFailure ->
                                                context.toast(R.string.error_github)

                                            release != null -> {
                                                val asset = findBestApkAsset(release.assets)
                                                DataPreferences.pendingUpdateVersion =
                                                    release.tag.removePrefix("v")
                                                DataPreferences.pendingUpdateUrl =
                                                    asset?.downloadUrl?.toString()
                                                        ?: release.frontendUrl.toString()
                                                context.toast(
                                                    context.getString(
                                                        R.string.format_new_version_available,
                                                        release.tag
                                                    )
                                                )
                                            }

                                            else -> context.toast(R.string.up_to_date)
                                        }
                                        checkingUpdate = false
                                    }
                                }
                            },
                            trailingContent = {
                                if (checkingUpdate) CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )

                        EnumValueSelectorSettingsEntry(
                            title = stringResource(R.string.version_check),
                            selectedValue = DataPreferences.versionCheckPeriod,
                            onValueSelect = onSelect@{
                                DataPreferences.versionCheckPeriod = it
                                if (isAtLeastAndroid13 && it.period != null && !hasPermission)
                                    launcher.launch(permission)

                                app.pulse.android.service.VersionCheckWorker.upsert(context.applicationContext, it.period)
                            },
                            valueText = { it.displayName() }
                        )
                    }
                }
}
