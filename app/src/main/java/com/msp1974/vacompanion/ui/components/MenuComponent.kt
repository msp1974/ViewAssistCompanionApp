package com.msp1974.vacompanion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.automirrored.filled.ReadMore
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DisabledByDefault
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Start
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.theme.AppTheme

/**
 * Data class representing an option in the menu.
 * @param title The main text to display.
 * @param subtitle Optional secondary text to display below the title.
 * @param icon Optional icon to display to the left of the text.
 * @param checked When set, show the current value as a switch.
 * @param onClick Callback function to execute when the item is clicked.
 */
data class MenuOption(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val iconColor: androidx.compose.ui.graphics.Color? = null,
    val checked: Boolean? = null,
    val onClick: () -> Unit
)

/**
 * A standard menu layout component that displays a list of [MenuOption]s.
 */
@Composable
fun MenuLayout(
    modifier: Modifier = Modifier,
    title: String = "VACA Settings",
    level: Int = 0,
    onClose: () -> Unit,
    options: List<MenuOption>
) {
    MenuLayout(
        modifier = modifier,
        title = title,
        level = level,
        onClose = onClose
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(options) { option ->
                MenuListItem(option = option)
            }
        }
    }
}

/**
 * A flexible menu layout component that allows custom content.
 */
@Composable
fun MenuLayout(
    modifier: Modifier = Modifier,
    title: String = "VACA Settings",
    level: Int = 0,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = if (level == 0) Icons.Default.Close else Icons.AutoMirrored.Default.ArrowLeft,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        content()
    }
}

/**
 * Individual menu list item component.
 */
@Composable
fun MenuListItem(
    option: MenuOption,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { option.onClick() },
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 16.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (option.icon != null) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = option.iconColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(24.dp))
            } else {
                Spacer(modifier = Modifier.width(48.dp)) // Maintain alignment if no icon
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
                if (option.subtitle != null) {
                    Text(
                        text = option.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            option.checked?.let { checked ->
                Switch(
                    checked = checked,
                    onCheckedChange = { option.onClick() }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MenuLayoutPreview() {
    AppTheme {
        MenuLayout(
            title = "VACA Settings",
            level = 1,
            onClose = {},
            options = listOf(
                MenuOption(
                    title = "Unpair Device",
                    subtitle = "Unpair from current HA server",
                    icon = Icons.Default.Cancel,
                    onClick = {}
                ),
                MenuOption(
                    title = "Manage Custom Files",
                    subtitle = "Manage custom wake words, sounds and alarms",
                    icon = Icons.Default.FileCopy,
                    onClick = {}
                ),
                MenuOption(
                    title = "Permissions Info",
                    subtitle = "View and manage required permissions",
                    icon = Icons.Default.Security,
                    onClick = {}
                ),
            )
        )
    }
}
