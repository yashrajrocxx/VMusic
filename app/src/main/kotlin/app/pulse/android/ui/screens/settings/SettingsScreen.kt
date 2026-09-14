@file:Suppress("TooManyFunctions")

package app.pulse.android.ui.screens.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pulse.android.LocalPlayerAwareWindowInsets
import app.pulse.android.R
import app.pulse.android.preferences.AccountPreferences
import app.pulse.android.ui.components.themed.HeaderCircleIconButton
import app.pulse.android.ui.screens.GlobalRoutes
import app.pulse.android.ui.screens.Route
import app.pulse.android.ui.screens.aboutSettingsRoute
import app.pulse.android.ui.screens.accountSettingsRoute
import app.pulse.android.ui.screens.appearanceSettingsRoute
import app.pulse.android.ui.screens.cacheSettingsRoute
import app.pulse.android.ui.screens.databaseSettingsRoute
import app.pulse.android.ui.screens.logsRoute
import app.pulse.android.ui.screens.otherSettingsRoute
import app.pulse.android.ui.screens.playerSettingsRoute
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
import app.pulse.compose.routing.RouteHandler
import app.pulse.core.ui.LocalAppearance
import coil3.compose.AsyncImage

@Route
@Composable
fun SettingsScreen(
    onBackClick: (() -> Unit)? = null
) {
    MasterSettingsCategoryScreen(
        title = stringResource(R.string.settings),
        onBackClick = onBackClick
    ) {
        SettingsAccountCard(
            onClick = { accountSettingsRoute.global() }
        )

        MasterSettingsGroup(title = stringResource(R.string.appearance)) {
            SettingsMenuEntry(
                title = stringResource(R.string.appearance),
                description = stringResource(R.string.appearance_description),
                icon = R.drawable.color_palette,
                onClick = { appearanceSettingsRoute.global() }
            )
        }

        MasterSettingsGroup(title = stringResource(R.string.player)) {
            SettingsMenuEntry(
                title = stringResource(R.string.player),
                description = stringResource(R.string.player_description),
                icon = R.drawable.play,
                onClick = { playerSettingsRoute.global() }
            )
        }

        MasterSettingsGroup(title = stringResource(R.string.cache_and_database)) {
            SettingsMenuEntry(
                title = stringResource(R.string.cache),
                description = stringResource(R.string.cache_description),
                icon = R.drawable.trash,
                onClick = { cacheSettingsRoute.global() }
            )
            SettingsMenuEntry(
                title = stringResource(R.string.database),
                description = stringResource(R.string.database_description),
                icon = R.drawable.server,
                onClick = { databaseSettingsRoute.global() },
                showDivider = true
            )
        }

        MasterSettingsGroup(title = stringResource(R.string.other)) {
            SettingsMenuEntry(
                title = stringResource(R.string.other),
                description = stringResource(R.string.other_description),
                icon = R.drawable.shapes,
                onClick = { otherSettingsRoute.global() }
            )
            if (app.pulse.android.BuildConfig.DEBUG) {
                SettingsMenuEntry(
                    title = stringResource(R.string.logs),
                    description = stringResource(R.string.logs_description),
                    icon = R.drawable.bug_outline,
                    onClick = { logsRoute.global() },
                    showDivider = true
                )
            }
        }

        MasterSettingsGroup(title = stringResource(R.string.about)) {
            SettingsMenuEntry(
                title = stringResource(R.string.about),
                description = stringResource(R.string.about_description),
                icon = R.drawable.information_circle_outline,
                onClick = { aboutSettingsRoute.global() }
            )
        }
    }
}

@Composable
fun SettingsAccountCard(
    onClick: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val isLoggedIn = AccountPreferences.isLoggedIn

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        BasicText(
            text = stringResource(R.string.account),
            style = typography.xs.semiBold.copy(color = colorPalette.accent),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colorPalette.background1)
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoggedIn && AccountPreferences.accountThumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = AccountPreferences.accountThumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, colorPalette.accent, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isLoggedIn) colorPalette.accent.copy(alpha = 0.15f) else colorPalette.background2),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.person),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(if (isLoggedIn) colorPalette.accent else colorPalette.text),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = if (isLoggedIn) AccountPreferences.accountName.ifBlank { stringResource(R.string.google_account) }
                           else stringResource(R.string.login_with_google),
                    style = typography.m.semiBold.copy(color = colorPalette.text)
                )
                Spacer(modifier = Modifier.height(2.dp))
                BasicText(
                    text = if (isLoggedIn) AccountPreferences.accountEmail.ifBlank { stringResource(R.string.logged_in) }
                           else stringResource(R.string.login_description),
                    style = typography.xs.secondary,
                    modifier = Modifier.alpha(0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Image(
                painter = painterResource(R.drawable.chevron_forward),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.textSecondary.copy(alpha = 0.6f)),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun MasterSettingsCategoryScreen(
    title: String,
    description: String? = null,
    onBackClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues()
            )
            .padding(horizontal = 16.dp)
            .padding(bottom = 80.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (onBackClick != null) {
                HeaderCircleIconButton(
                    icon = R.drawable.chevron_back,
                    onClick = onBackClick
                )
            }

            BasicText(
                text = title,
                style = typography.xxl.semiBold.copy(color = colorPalette.text)
            )
        }

        if (description != null) {
            BasicText(
                text = description,
                style = typography.s.secondary,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .alpha(0.7f)
            )
        }

        content()
    }
}

@Composable
fun MasterSettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        BasicText(
            text = title,
            style = typography.xs.semiBold.copy(color = colorPalette.accent),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colorPalette.background1)
        ) {
            content()
        }
    }
}

@Composable
fun SettingsMenuEntry(
    title: String,
    description: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    showDivider: Boolean = false,
) {
    val (colorPalette, typography) = LocalAppearance.current

    Column {
        if (showDivider) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .padding(horizontal = 16.dp)
                    .background(colorPalette.textDisabled.copy(alpha = 0.12f))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colorPalette.background2),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(icon),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.text),
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = typography.m.semiBold.copy(color = colorPalette.text)
                )
                Spacer(modifier = Modifier.height(2.dp))
                BasicText(
                    text = description,
                    style = typography.xs.secondary,
                    modifier = Modifier.alpha(0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Image(
                painter = painterResource(R.drawable.chevron_forward),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.textSecondary.copy(alpha = 0.6f)),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
