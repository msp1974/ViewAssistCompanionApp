package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/**
 * Definition of one entry in the Configuration screen. Each left-hand section has its own file
 * listing its items (see GeneralConfig.kt etc.); ConfigurationLayout builds the screen from them:
 * the section gives the left-hand list entry, [group] the card on the right and the rest the row.
 *
 * Read-only entries are [Info]. Entries that can be changed carry the Home Assistant settings
 * [key]; ConfigurationLayout applies the new value with `set` and sends it to Home Assistant under
 * that key (see VAViewModel.sendSettingToHA), so values are in the units HA uses.
 */
sealed class ConfigItem(
    val group: String,
    val label: String,
    val description: String?,
    val icon: ImageVector,
) {
    /**
     * Whether the item can be used. A row that can't (e.g. the device lacks the hardware) is still
     * shown, but dimmed and not changeable. Set with [enabledWhen].
     */
    var isEnabled: () -> Boolean = { true }
        private set

    fun enabledWhen(check: () -> Boolean): ConfigItem {
        isEnabled = check
        return this
    }

    /** A value that is only displayed. */
    class Info(
        group: String, label: String, description: String? = null, icon: ImageVector,
        val value: () -> String,
        val valueColor: (() -> Color?)? = null,
    ) : ConfigItem(group, label, description, icon)

    /**
     * A row that does something when tapped; [onClick] is given a function to open a link. An
     * optional [value] shows a current state, re-read whenever the app comes back to the front
     * (e.g. after changing a system setting that the tap opened).
     */
    class Action(
        group: String, label: String, description: String? = null, icon: ImageVector,
        val opensLink: Boolean = false,
        val value: (() -> String)? = null,
        val valueColor: (() -> Color?)? = null,
        val onClick: (openUri: (String) -> Unit) -> Unit,
    ) : ConfigItem(group, label, description, icon)

    class Toggle(
        group: String, label: String, description: String? = null, icon: ImageVector,
        val key: String,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
    ) : ConfigItem(group, label, description, icon)

    class IntValue(
        group: String, label: String, description: String? = null, icon: ImageVector,
        val key: String,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        val unit: String = "",
        val get: () -> Int,
        val set: (Int) -> Unit,
    ) : ConfigItem(group, label, description, icon)

    class FloatValue(
        group: String, label: String, description: String? = null, icon: ImageVector,
        val key: String,
        val min: Float,
        val max: Float,
        val step: Float = 0.1f,
        val unit: String = "",
        val get: () -> Float,
        val set: (Float) -> Unit,
    ) : ConfigItem(group, label, description, icon)

    /**
     * A choice from a set of strings: a fixed [options] list, or [optionsProvider] when the
     * choices depend on the device (evaluated each time the screen is shown). [toHaValue]
     * converts the chosen value to what HA expects when it is not a string.
     */
    class Options(
        group: String, label: String, description: String? = null, icon: ImageVector,
        val key: String,
        val options: List<ConfigOption> = emptyList(),
        val optionsProvider: (() -> List<ConfigOption>)? = null,
        val get: () -> String,
        val set: (String) -> Unit,
        val toHaValue: (String) -> Any = { it },
    ) : ConfigItem(group, label, description, icon) {
        fun resolveOptions(): List<ConfigOption> = optionsProvider?.invoke() ?: options
    }
}

data class ConfigOption(val value: String, val label: String)

/**
 * One entry in the left-hand list. [items] are grouped (by [ConfigItem.group], in order) into
 * cards on the right.
 */
class ConfigSection(
    val title: String,
    val icon: ImageVector,
    val items: List<ConfigItem>,
)

fun onOff(value: Boolean) = if (value) "On" else "Off"

/** "hey_jarvis" -> "Hey Jarvis". */
fun friendly(value: String): String =
    value.replace('_', ' ').replace('-', ' ').trim().split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
        .ifEmpty { "Not set" }

fun friendlyOptions(vararg values: String) = values.map { ConfigOption(it, friendly(it)) }
