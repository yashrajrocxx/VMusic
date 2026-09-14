package app.pulse.android.ui.screens.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pulse.android.R
import app.pulse.android.preferences.AccountPreferences
import app.pulse.android.ui.components.themed.ConfirmationDialog
import app.pulse.android.ui.components.themed.Scaffold
import app.pulse.android.ui.screens.GlobalRoutes
import app.pulse.android.ui.screens.Route
import app.pulse.android.ui.screens.loginRoute
import app.pulse.compose.routing.RouteHandler
import coil3.compose.AsyncImage

@Route
@Composable
fun AccountSettings() {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val pop: () -> Unit = { backDispatcher?.onBackPressed() }

    SettingsCategoryScreen(
        title = stringResource(R.string.account),
        onBackClick = pop,
    ) {
                    val isLoggedIn = AccountPreferences.isLoggedIn

                    SettingsGroup(title = stringResource(R.string.google_account)) {
                        if (isLoggedIn) {
                            SettingsEntry(
                                title = AccountPreferences.accountName.ifBlank { stringResource(R.string.google_account) },
                                text = AccountPreferences.accountEmail.ifBlank { stringResource(R.string.logged_in) },
                                onClick = { showLogoutConfirm = true },
                                trailingContent = {
                                    if (AccountPreferences.accountThumbnailUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = AccountPreferences.accountThumbnailUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape),
                                        )
                                    }
                                },
                            )

                            SettingsEntry(
                                title = stringResource(R.string.switch_channel),
                                text = stringResource(R.string.login_description),
                                onClick = { loginRoute.global() },
                            )

                            SettingsEntry(
                                title = stringResource(R.string.logout),
                                text = stringResource(R.string.logout_description),
                                onClick = { showLogoutConfirm = true },
                            )
                        } else {
                            SettingsEntry(
                                title = stringResource(R.string.login_with_google),
                                text = stringResource(R.string.login_description),
                                onClick = { loginRoute.global() },
                            )
                        }
                    }

                    SettingsGroup(title = stringResource(R.string.preferences)) {
                        SwitchSettingsEntry(
                            title = stringResource(R.string.use_login_for_browse),
                            text = stringResource(R.string.use_login_for_browse_description),
                            isChecked = AccountPreferences.useLoginForBrowse,
                            onCheckedChange = { AccountPreferences.useLoginForBrowse = it },
                            isEnabled = isLoggedIn,
                        )
                    }
                }

    if (showLogoutConfirm) {
        ConfirmationDialog(
            text = stringResource(R.string.logout_description),
            onDismiss = { showLogoutConfirm = false },
            onConfirm = {
                AccountPreferences.clear()
                showLogoutConfirm = false
            },
            confirmText = stringResource(R.string.logout),
        )
    }
}
