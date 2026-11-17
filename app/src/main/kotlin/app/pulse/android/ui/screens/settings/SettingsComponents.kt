package app.pulse.android.ui.screens.settings

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pulse.android.R
import app.pulse.android.ui.components.themed.NumberFieldDialog
import app.pulse.android.ui.components.themed.Slider
import app.pulse.android.ui.components.themed.Switch
import app.pulse.android.ui.components.themed.ValueSelectorDialog
import app.pulse.android.utils.color
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
import app.pulse.core.ui.LocalAppearance
import app.pulse.core.ui.dockBackground
import app.pulse.core.ui.dockBorder
import app.pulse.core.ui.dockSelected
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** True while composing inside a [SettingsGroup] card: rows render flat
 * (no per-row card) so a section reads as one minimal grouped blob. */
val LocalInSettingsCard = compositionLocalOf { false }

/** Shared card look for every settings row: 20dp radius like the master
 * screen, elevated surface in AMOLED, hairline border on pure black. */
val SettingsCardShape = RoundedCornerShape(20.dp)

@Composable
fun Modifier.settingsCard(): Modifier {
    val (colorPalette) = LocalAppearance.current
    return this
        .padding(vertical = 4.dp)
        .clip(SettingsCardShape)
        .background(colorPalette.dockBackground)
        .border(1.dp, colorPalette.dockBorder, SettingsCardShape)
}

/** Hairline separator between rows inside a group card. */
@Composable
private fun SettingsRowDivider() {
    val (colorPalette) = LocalAppearance.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.5.dp)
            .background(colorPalette.textDisabled.copy(alpha = 0.12f))
    )
}

/** The standard trailing chevron, shared by every navigable settings row. */
@Composable
fun SettingsChevron() {
    val (colorPalette) = LocalAppearance.current

    Image(
        painter = painterResource(R.drawable.chevron_forward),
        contentDescription = null,
        colorFilter = ColorFilter.tint(colorPalette.textSecondary.copy(alpha = 0.6f)),
        modifier = Modifier.size(16.dp)
    )
}

/** Accent pill showing the selected value + chevron, used by selectors. */
@Composable
private fun ValueChip(text: String) {
    val (colorPalette, typography) = LocalAppearance.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colorPalette.accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        BasicText(
            text = text,
            style = typography.xxs.semiBold.copy(color = colorPalette.accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp)
        )
        Image(
            painter = painterResource(R.drawable.chevron_forward),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorPalette.accent),
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
inline fun <reified T : Enum<T>> EnumValueSelectorSettingsEntry(
    title: String,
    selectedValue: T,
    noinline onValueSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    noinline valueText: @Composable (T) -> String = { it.name },
    noinline trailingContent: (@Composable () -> Unit)? = null,
    icon: Int? = null
) = ValueSelectorSettingsEntry(
    title = title,
    selectedValue = selectedValue,
    values = enumValues<T>().toList().toImmutableList(),
    onValueSelect = onValueSelect,
    modifier = modifier,
    isEnabled = isEnabled,
    valueText = valueText,
    trailingContent = trailingContent,
    icon = icon
)

@Composable
fun <T> ValueSelectorSettingsEntry(
    title: String,
    selectedValue: T,
    values: ImmutableList<T>,
    onValueSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    isEnabled: Boolean = true,
    usePadding: Boolean = true,
    valueText: @Composable (T) -> String = { it.toString() },
    trailingContent: (@Composable () -> Unit)? = null,
    icon: Int? = null
) {
    var isShowingDialog by remember { mutableStateOf(false) }

    if (isShowingDialog) ValueSelectorDialog(
        onDismiss = { isShowingDialog = false },
        title = title,
        selectedValue = selectedValue,
        values = values,
        onValueSelect = onValueSelect,
        valueText = valueText
    )

    // The selected value lives in the accent chip; the card body keeps an
    // explicit description only when the caller passes one.
    SettingsEntry(
        modifier = modifier,
        title = title,
        text = text,
        onClick = { isShowingDialog = true },
        isEnabled = isEnabled,
        valueChip = valueText(selectedValue),
        trailingContent = trailingContent,
        icon = icon
    )
}

@Composable
fun SwitchSettingsEntry(
    title: String,
    text: String?,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    usePadding: Boolean = true
) = SettingsEntry(
    modifier = modifier,
    title = title,
    text = text,
    onClick = { onCheckedChange(!isChecked) },
    isEnabled = isEnabled,
    usePadding = usePadding,
    showChevron = false,
    trailingContent = { Switch(isChecked = isChecked) }
)

@Composable
fun SliderSettingsEntry(
    title: String,
    text: String,
    state: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onSlide: (Float) -> Unit = { },
    onSlideComplete: () -> Unit = { },
    toDisplay: @Composable (Float) -> String = { it.toString() },
    steps: Int = 0,
    isEnabled: Boolean = true,
    usePadding: Boolean = true,
    showTicks: Boolean = steps != 0
) {
    val (colorPalette, typography) = LocalAppearance.current
    val inCard = LocalInSettingsCard.current

    // Title row and slider share one card so the control never dangles
    // outside the blob — flat when already inside a group card.
    Column(
        modifier = modifier
            .then(if (inCard) Modifier else Modifier.settingsCard())
            .alpha(if (isEnabled) 1f else 0.5f)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (inCard) 14.dp else 16.dp,
                bottom = if (inCard) 12.dp else 16.dp
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = typography.m.semiBold.copy(color = colorPalette.text)
                )
                BasicText(
                    text = "$text (${toDisplay(state)})",
                    style = typography.xs.semiBold.secondary
                )
            }
        }

        Slider(
            state = state,
            setState = onSlide,
            onSlideComplete = onSlideComplete,
            range = range,
            steps = steps,
            showTicks = showTicks,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
inline fun IntSettingsEntry(
    title: String,
    text: String,
    currentValue: Int,
    crossinline setValue: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    defaultValue: Int = 0,
    isEnabled: Boolean = true,
    usePadding: Boolean = true
) {
    var isShowingDialog by remember { mutableStateOf(false) }

    if (isShowingDialog) NumberFieldDialog(
        onDismiss = { isShowingDialog = false },
        onAccept = {
            setValue(it)
            isShowingDialog = false
        },
        initialValue = currentValue,
        defaultValue = defaultValue,
        convert = { it.toIntOrNull() },
        range = range
    )

    SettingsEntry(
        modifier = modifier,
        title = title,
        text = text,
        onClick = { isShowingDialog = true },
        isEnabled = isEnabled,
        usePadding = usePadding
    )
}

/** The one settings row: optional tinted icon chip, title + description,
 * and exactly one trailing affordance — switch, value chip, spinner, or
 * chevron. Every settings screen composes from this so rows can't drift. */
@Composable
fun SettingsEntry(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    isEnabled: Boolean = true,
    usePadding: Boolean = true,
    valueChip: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    icon: Int? = null,
    showChevron: Boolean = trailingContent == null && valueChip == null,
    showDivider: Boolean = false
) {
    val (colorPalette, typography) = LocalAppearance.current
    val inCard = LocalInSettingsCard.current

    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) SettingsRowDivider()

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (inCard) Modifier else Modifier.settingsCard())
                .clickable(enabled = isEnabled, onClick = onClick)
                .alpha(if (isEnabled) 1f else 0.5f)
                .padding(horizontal = 16.dp)
                .padding(vertical = 14.dp)
                .fillMaxWidth()
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colorPalette.dockSelected)
                        .border(1.dp, colorPalette.dockBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(icon),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorPalette.text),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = typography.m.semiBold.copy(color = colorPalette.text)
                )

                if (text != null) BasicText(
                    text = text,
                    style = typography.xs.semiBold.secondary
                )
            }

            if (valueChip != null) ValueChip(text = valueChip)

            when {
                trailingContent != null -> trailingContent()
                showChevron -> SettingsChevron()
            }
        }
    }
}

@Composable
fun SettingsDescription(
    text: String,
    modifier: Modifier = Modifier,
    important: Boolean = false
) {
    val (colorPalette, typography) = LocalAppearance.current

    BasicText(
        text = text,
        style = if (important) typography.xxs.semiBold.color(colorPalette.red)
        else typography.xxs.secondary,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun SettingsEntryGroupText(
    title: String,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    BasicText(
        text = title,
        style = typography.xs.semiBold.copy(color = colorPalette.accent),
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { text = AnnotatedString(text = title) }
    )
}

@Composable
fun SettingsGroupSpacer(modifier: Modifier = Modifier) = Spacer(modifier = modifier.height(16.dp))

/** Same shell as the master screen — title row with back button, then
 * group content. Kept as an alias so category screens can't drift from
 * [MasterSettingsCategoryScreen] again; scrollState is legacy and unused
 * (the master shell owns scrolling), accepted for call-site stability. */
@Composable
fun SettingsCategoryScreen(
    title: String,
    scrollState: ScrollState? = rememberScrollState(),
    description: String? = null,
    onBackClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) = MasterSettingsCategoryScreen(
    title = title,
    description = description,
    onBackClick = onBackClick,
    scrollState = scrollState ?: rememberScrollState(),
    content = content
)

/** One grouped section: accent label on top, rows inside a single 20dp
 * card. This is the canonical group used by every settings screen —
 * [MasterSettingsGroup] on the master screen delegates to it. */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    important: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) = Column(modifier = modifier.padding(vertical = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsEntryGroupText(title = title)

        Spacer(modifier = Modifier.weight(1f))

        trailingContent?.invoke()
    }

    description?.let { description ->
        SettingsDescription(
            text = description,
            important = important
        )
    }

    CompositionLocalProvider(LocalInSettingsCard provides true) {
        Column(
            modifier = Modifier
                .clip(SettingsCardShape)
                .background(LocalAppearance.current.colorPalette.dockBackground)
                .border(1.dp, LocalAppearance.current.colorPalette.dockBorder, SettingsCardShape),
            content = content
        )
    }
}
