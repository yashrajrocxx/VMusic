package app.pulse.android.ui.screens.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
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
        onBackClick = pop
    ) {
        val (colorPalette, typography) = LocalAppearance.current
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

        // Hero: wordmark, version pill and maintainer line — text only, so
        // the header scales cleanly and matches the settings typography.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
        ) {
            BasicText(
                text = "VMusic",
                style = typography.xxl.semiBold.copy(color = colorPalette.text),
                modifier = Modifier.padding(top = 8.dp)
            )

            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colorPalette.accent.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                BasicText(
                    text = "v$VERSION_NAME",
                    style = typography.xs.semiBold.copy(color = colorPalette.accent)
                )
            }

            BasicText(
                text = stringResource(R.string.format_version_credits),
                style = typography.xs.secondary,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .alpha(0.75f)
            )
        }

        var checkingUpdate by rememberSaveable { mutableStateOf(false) }
        val updateScope = rememberCoroutineScope()

        SettingsGroup(title = stringResource(R.string.version)) {
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
                icon = R.drawable.sync,
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (checkingUpdate) CircularProgressIndicator(
                            modifier = Modifier.size(20.dp)
                        )
                        SettingsChevron()
                    }
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
                valueText = { it.displayName() },
                icon = R.drawable.time
            )
        }

        MasterSettingsGroup(title = stringResource(R.string.social)) {
            SettingsMenuEntry(
                title = stringResource(R.string.github),
                description = stringResource(R.string.view_source),
                icon = R.drawable.globe,
                onClick = {
                    uriHandler.openUri("https://github.com/yashrajrocxx/VMusic")
                }
            )
            SettingsMenuEntry(
                title = "Instagram",
                description = "@yashrajrocxx",
                icon = R.drawable.person,
                onClick = {
                    uriHandler.openUri("https://www.instagram.com/yashrajrocxx/")
                }
            )
        }

        MasterSettingsGroup(title = stringResource(R.string.special_thanks)) {
            SettingsMenuEntry(
                title = stringResource(R.string.credits_vimusic_title),
                description = stringResource(R.string.credits_vimusic_desc),
                icon = R.drawable.musical_notes,
                onClick = {
                    uriHandler.openUri("https://github.com/vfsfitvnm/ViMusic")
                }
            )
            SettingsMenuEntry(
                title = stringResource(R.string.credits_innertune_title),
                description = stringResource(R.string.credits_innertune_desc),
                icon = R.drawable.star,
                onClick = {
                    uriHandler.openUri("https://github.com/z-huang/InnerTune")
                }
            )
            SettingsMenuEntry(
                title = stringResource(R.string.credits_metrolist_title),
                description = stringResource(R.string.credits_metrolist_desc),
                icon = R.drawable.heart,
                onClick = {
                    uriHandler.openUri("https://github.com/mostafa-a-elhariry/metrolist")
                }
            )
        }

        MasterSettingsGroup(title = stringResource(R.string.contact)) {
            SettingsMenuEntry(
                title = stringResource(R.string.report_bug),
                description = stringResource(R.string.report_bug_description),
                icon = R.drawable.bug_outline,
                onClick = {
                    uriHandler.openUri(
                        @Suppress("MaximumLineLength")
                        "https://github.com/$REPO_OWNER/$REPO_NAME/issues/new?assignees=&labels=bug&template=bug_report.yaml"
                    )
                }
            )
            SettingsMenuEntry(
                title = stringResource(R.string.request_feature),
                description = stringResource(R.string.redirect_github),
                icon = R.drawable.shapes,
                onClick = {
                    uriHandler.openUri(
                        @Suppress("MaximumLineLength")
                        "https://github.com/$REPO_OWNER/$REPO_NAME/issues/new?assignees=&labels=enhancement&template=feature_request.md"
                    )
                }
            )
        }

        BasicText(
            text = stringResource(R.string.about_description),
            style = typography.xxs.secondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .alpha(0.6f),
        )
    }
}
