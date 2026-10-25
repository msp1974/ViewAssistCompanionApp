package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.ui.components.MenuLayout
import com.msp1974.vacompanion.ui.layouts.config.ConfigItem
import com.msp1974.vacompanion.ui.layouts.config.ConfigSection
import com.msp1974.vacompanion.ui.layouts.config.audioConfig
import com.msp1974.vacompanion.ui.layouts.config.deviceConfig
import com.msp1974.vacompanion.ui.layouts.config.displayConfig
import com.msp1974.vacompanion.ui.layouts.config.generalConfig
import com.msp1974.vacompanion.ui.layouts.config.homeAssistantConfig
import com.msp1974.vacompanion.ui.layouts.config.servicesConfig
import com.msp1974.vacompanion.ui.layouts.config.permissionsConfig
import com.msp1974.vacompanion.ui.layouts.config.projectConfig
import com.msp1974.vacompanion.ui.layouts.config.voiceConfig
import com.msp1974.vacompanion.ui.theme.CustomColours
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt

// Accent colour for each section, in list order, matching the Manage Custom Files screen.
val SECTION_COLORS = listOf(
    Color(0xFF6366F1), // indigo
    Color(0xFF14B8A6), // teal
    Color(0xFFF59E0B), // amber
    Color(0xFFEC4899), // pink
    Color(0xFF3B82F6), // blue
    Color(0xFF22C55E), // green
    Color(0xFFA855F7), // purple
    Color(0xFFEF4444), // red
    Color(0xFF06B6D4), // cyan
    Color(0xFFF97316), // orange
)

fun sectionColor(index: Int) = SECTION_COLORS[index % SECTION_COLORS.size]

// The accent of the section being shown, used by the rows and cards inside it.
private val LocalAccent = compositionLocalOf { Color.Unspecified }

// Changes whenever a setting changes elsewhere (e.g. Home Assistant sends one), so the rows that
// keep their own copy of a value read it again.
private val LocalSettingsVersion = compositionLocalOf { 0 }

/**
 * The Configuration screen, built from the definitions in the config package: each section file
 * gives an entry in the left-hand list, each item's group a card on the right, and the item the
 * row. Changing an editable item applies it on the device and sends it to Home Assistant.
 */
@Composable
fun ConfigurationLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sections = remember {
        listOf(
            generalConfig(viewModel),
            deviceConfig(viewModel),
            permissionsConfig(viewModel),
            displayConfig(viewModel),
            audioConfig(viewModel),
            homeAssistantConfig(viewModel),
            voiceConfig(viewModel),
            servicesConfig(viewModel),
            projectConfig(viewModel),
        )
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    RefreshPermissionsOnResume(viewModel)

    var settingsVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(viewModel) {
        val listener = object : EventListener {
            override fun onEventTriggered(event: Event) {
                if (event.oldValue != event.newValue) settingsVersion++
            }
        }
        viewModel.config.eventBroadcaster.addListener(listener)
        onDispose { viewModel.config.eventBroadcaster.removeListener(listener) }
    }
    val section = sections[selected.coerceIn(0, sections.lastIndex)]

    MenuLayout(
        title = "Configuration",
        level = 1,
        onClose = onBack,
        modifier = modifier
    ) {
        // Wide screens get a labelled rail; small ones an icon rail.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .width(if (compact) 84.dp else 210.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(sections.size) { index ->
                        SectionRow(sections[index], sectionColor(index), index == selected, compact) { selected = index }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                CompositionLocalProvider(
                    LocalAccent provides sectionColor(selected.coerceIn(0, sections.lastIndex)),
                    LocalSettingsVersion provides settingsVersion
                ) {
                    SectionContent(section, viewModel, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** One section on its own, with the same look as the Configuration screen. */
@Composable
fun ConfigSectionLayout(
    title: String,
    section: ConfigSection,
    viewModel: VAViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    MenuLayout(title = title, level = 1, onClose = onBack, modifier = modifier) {
        CompositionLocalProvider(LocalAccent provides sectionColor(1)) {
            SectionContent(section, viewModel, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SectionContent(section: ConfigSection, viewModel: VAViewModel, modifier: Modifier) {
    // Items keep their order; each distinct group becomes a card.
    val groups = remember(section) { section.items.groupBy { it.group }.toList() }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Keyed so a row's remembered value isn't carried over to a different section's row
        // that happens to be at the same position.
        items(groups, key = { (group, _) -> "${section.title}/$group" }) { (group, items) ->
            SectionCard(group) {
                items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    key(item) { ConfigItemRow(item, viewModel) }
                }
            }
        }
    }
}

@Composable
private fun SectionRow(section: ConfigSection, color: Color, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) color.copy(alpha = 0.2f) else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(onClick = onClick)
    ) {
        val textColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        if (compact) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 12.dp)
            ) {
                Icon(section.icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Icon(section.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfigItemRow(item: ConfigItem, viewModel: VAViewModel) {
    when (item) {
        is ConfigItem.Info -> {
            // Read here so the row is redrawn when the live state its value comes from changes.
            viewModel.vacaState.collectAsState().value
            InfoRow(item)
        }
        is ConfigItem.Action -> {
            // Read here so a shown value is redrawn when the state it comes from changes.
            if (item.value != null) viewModel.vacaState.collectAsState().value
            ActionRow(item)
        }
        is ConfigItem.Toggle -> ToggleRow(item, viewModel)
        is ConfigItem.IntValue -> IntRow(item, viewModel)
        is ConfigItem.FloatValue -> FloatRow(item, viewModel)
        is ConfigItem.Options -> OptionsRow(item, viewModel)
    }
}

@Composable
private fun RowIcon(item: ConfigItem) {
    val accent = LocalAccent.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(accent.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun Label(item: ConfigItem) {
    Text(text = item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}

// The description, or why the row can't be used when it is disabled.
private fun descriptionOf(item: ConfigItem): String? =
    if (item.isEnabled()) item.description else "Not supported on this device"

private fun dimmed(item: ConfigItem): Modifier = if (item.isEnabled()) Modifier else Modifier.alpha(0.5f)

@Composable
private fun Description(text: String?) {
    if (text != null) Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun InfoRow(item: ConfigItem.Info) {
    ListItem(
        headlineContent = { Label(item) },
        supportingContent = descriptionOf(item)?.let { { Description(it) } },
        leadingContent = { RowIcon(item) },
        modifier = dimmed(item),
        trailingContent = {
            Text(
                text = item.value(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = item.valueColor?.invoke() ?: MaterialTheme.colorScheme.onSurface
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun ToggleRow(item: ConfigItem.Toggle, viewModel: VAViewModel) {
    var checked by remember(LocalSettingsVersion.current) { mutableStateOf(item.get()) }
    ListItem(
        headlineContent = { Label(item) },
        supportingContent = descriptionOf(item)?.let { { Description(it) } },
        leadingContent = { RowIcon(item) },
        modifier = dimmed(item),
        trailingContent = {
            Switch(
                checked = checked,
                enabled = item.isEnabled(),
                onCheckedChange = {
                    checked = it
                    applyAndSend(viewModel, item.key, it) { item.set(it) }
                })
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun IntRow(item: ConfigItem.IntValue, viewModel: VAViewModel) {
    var value by remember(LocalSettingsVersion.current) { mutableIntStateOf(item.get().coerceIn(item.min, item.max)) }
    SliderRow(
        item = item,
        valueText = "$value${item.unit}",
        value = value.toFloat(),
        range = item.min.toFloat()..item.max.toFloat(),
        steps = ((item.max - item.min) / item.step - 1).coerceAtLeast(0),
        onChange = {
            value = (item.min + ((it - item.min) / item.step).roundToInt() * item.step).coerceIn(item.min, item.max)
        },
        onFinished = {
            applyAndSend(viewModel, item.key, value) { item.set(value) }
        }
    )
}

@Composable
private fun FloatRow(item: ConfigItem.FloatValue, viewModel: VAViewModel) {
    var value by remember(LocalSettingsVersion.current) { mutableFloatStateOf(item.get().coerceIn(item.min, item.max)) }
    SliderRow(
        item = item,
        valueText = String.format(Locale.US, "%.1f", value) + item.unit,
        value = value,
        range = item.min..item.max,
        steps = ((item.max - item.min) / item.step).roundToInt().minus(1).coerceAtLeast(0),
        onChange = {
            // Snap to the step and keep one decimal so the value sent to HA is tidy.
            val snapped = item.min + ((it - item.min) / item.step).roundToInt() * item.step
            value = ((snapped * 10).roundToInt() / 10f).coerceIn(item.min, item.max)
        },
        onFinished = {
            applyAndSend(viewModel, item.key, value) { item.set(value) }
        }
    )
}

@Composable
private fun SliderRow(
    item: ConfigItem,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    ListItem(
        headlineContent = { Label(item) },
        modifier = dimmed(item),
        supportingContent = {
            Column {
                Description(descriptionOf(item))
                Slider(
                    enabled = item.isEnabled(),
                    value = value,
                    onValueChange = onChange,
                    onValueChangeFinished = onFinished,
                    valueRange = range,
                    steps = steps,
                )
            }
        },
        leadingContent = { RowIcon(item) },
        trailingContent = {
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = CustomColours.GREEN
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun OptionsRow(item: ConfigItem.Options, viewModel: VAViewModel) {
    var current by remember(LocalSettingsVersion.current) { mutableStateOf(item.get()) }
    var expanded by remember { mutableStateOf(false) }
    // Worked out each time the menu is shown, as the choices can depend on the device's state.
    val options = if (expanded) item.resolveOptions() else emptyList()
    val currentLabel = remember(current, expanded) {
        item.resolveOptions().firstOrNull { it.value == current }?.label ?: current
    }

    Box {
        ListItem(
            headlineContent = { Label(item) },
            supportingContent = descriptionOf(item)?.let { { Description(it) } },
            leadingContent = { RowIcon(item) },
            trailingContent = {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = LocalAccent.current
                )
            },
            modifier = dimmed(item).clickable(enabled = item.isEnabled()) { expanded = true },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        if (option.value != current) {
                            current = option.value
                            applyAndSend(viewModel, item.key, item.toHaValue(option.value)) {
                                item.set(option.value)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionRow(item: ConfigItem.Action) {
    val uriHandler = LocalUriHandler.current
    // Bumped each time the app returns to the front so a shown value is read again.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ListItem(
        headlineContent = { Label(item) },
        supportingContent = item.description?.let { { Description(it) } },
        leadingContent = { RowIcon(item) },
        trailingContent = if (item.value != null || item.opensLink) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.value != null) {
                        refresh // read so the value is recomputed when it changes
                        Text(
                            text = item.value.invoke(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = item.valueColor?.invoke() ?: MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (item.opensLink) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = LocalAccent.current,
                            modifier = Modifier.padding(start = 8.dp).size(20.dp)
                        )
                    }
                }
            }
        } else null,
        modifier = Modifier.clickable { item.onClick { uriHandler.openUri(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

// One group of related items in a rounded card, with its title above the items.
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = LocalAccent.current,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            content()
        }
    }
}

// Applies a changed setting on the device and sends it to HA. Applying can trigger listeners
// elsewhere in the app, so a failure there is logged but must not stop HA being told.
private fun applyAndSend(viewModel: VAViewModel, key: String, haValue: Any, apply: () -> Unit) {
    try {
        apply()
    } catch (e: Exception) {
        Timber.e("Error applying setting $key: $e")
    }
    viewModel.sendSettingToHA(key, haValue)
}
