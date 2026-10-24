package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout
import com.msp1974.vacompanion.ui.layouts.config.ConfigItem
import com.msp1974.vacompanion.ui.layouts.config.ConfigSection
import com.msp1974.vacompanion.ui.layouts.config.aboutConfig
import com.msp1974.vacompanion.ui.layouts.config.audioConfig
import com.msp1974.vacompanion.ui.layouts.config.cameraConfig
import com.msp1974.vacompanion.ui.layouts.config.displayConfig
import com.msp1974.vacompanion.ui.layouts.config.generalConfig
import com.msp1974.vacompanion.ui.layouts.config.mediaConfig
import com.msp1974.vacompanion.ui.layouts.config.projectConfig
import com.msp1974.vacompanion.ui.layouts.config.voiceConfig
import com.msp1974.vacompanion.ui.layouts.config.wakeScreenConfig
import com.msp1974.vacompanion.ui.theme.CustomColours
import java.util.Locale
import kotlin.math.roundToInt

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
            voiceConfig(viewModel),
            audioConfig(viewModel),
            displayConfig(viewModel),
            wakeScreenConfig(viewModel),
            cameraConfig(viewModel),
            mediaConfig(viewModel),
            aboutConfig(viewModel),
            projectConfig(viewModel),
        )
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val section = sections[selected.coerceIn(0, sections.lastIndex)]

    MenuLayout(
        title = "Configuration",
        level = 1,
        onClose = onBack,
        modifier = modifier
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .width(210.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(sections.size) { index ->
                    SectionRow(sections[index], index == selected) { selected = index }
                }
            }
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            SectionContent(section, viewModel, Modifier.weight(1f).fillMaxHeight())
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
        items(groups) { (group, items) ->
            SectionCard(group) {
                items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    ConfigItemRow(item, viewModel)
                }
            }
        }
    }
}

@Composable
private fun SectionRow(section: ConfigSection, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
        ) {
            val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
            Icon(section.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(
                text = section.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp)
            )
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
        is ConfigItem.Action -> ActionRow(item)
        is ConfigItem.Toggle -> ToggleRow(item, viewModel)
        is ConfigItem.IntValue -> IntRow(item, viewModel)
        is ConfigItem.FloatValue -> FloatRow(item, viewModel)
        is ConfigItem.Options -> OptionsRow(item, viewModel)
    }
}

@Composable
private fun RowIcon(item: ConfigItem) {
    Icon(
        imageVector = item.icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun Label(item: ConfigItem) {
    Text(text = item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}

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
        supportingContent = item.description?.let { { Description(it) } },
        leadingContent = { RowIcon(item) },
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
    var checked by remember { mutableStateOf(item.get()) }
    ListItem(
        headlineContent = { Label(item) },
        supportingContent = item.description?.let { { Description(it) } },
        leadingContent = { RowIcon(item) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = {
                checked = it
                item.set(it)
                viewModel.sendSettingToHA(item.key, it)
            })
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun IntRow(item: ConfigItem.IntValue, viewModel: VAViewModel) {
    var value by remember { mutableIntStateOf(item.get().coerceIn(item.min, item.max)) }
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
            item.set(value)
            viewModel.sendSettingToHA(item.key, value)
        }
    )
}

@Composable
private fun FloatRow(item: ConfigItem.FloatValue, viewModel: VAViewModel) {
    var value by remember { mutableFloatStateOf(item.get().coerceIn(item.min, item.max)) }
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
            item.set(value)
            viewModel.sendSettingToHA(item.key, value)
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
        supportingContent = {
            Column {
                Description(item.description)
                Slider(
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
    var current by remember { mutableStateOf(item.get()) }
    var expanded by remember { mutableStateOf(false) }
    // Worked out each time the menu is shown, as the choices can depend on the device's state.
    val options = if (expanded) item.resolveOptions() else emptyList()
    val currentLabel = remember(current, expanded) {
        item.resolveOptions().firstOrNull { it.value == current }?.label ?: current
    }

    Box {
        ListItem(
            headlineContent = { Label(item) },
            supportingContent = item.description?.let { { Description(it) } },
            leadingContent = { RowIcon(item) },
            trailingContent = {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            modifier = Modifier.clickable { expanded = true },
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
                            item.set(option.value)
                            viewModel.sendSettingToHA(item.key, item.toHaValue(option.value))
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
    ListItem(
        headlineContent = { Label(item) },
        supportingContent = item.description?.let { { Description(it) } },
        leadingContent = { RowIcon(item) },
        trailingContent = if (item.opensLink) {
            {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            content()
        }
    }
}
