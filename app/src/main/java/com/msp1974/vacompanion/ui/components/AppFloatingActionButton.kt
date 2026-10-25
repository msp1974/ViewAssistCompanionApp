package com.msp1974.vacompanion.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A reusable Floating Action Button component.
 *
 * @param onClick Function to be executed when the button is pressed.
 * @param modifier Modifier to be applied to the button.
 * @param icon The icon to be displayed inside the button.
 * @param contentDescription Accessibility description for the icon.
 */
@Composable
fun AppFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Settings,
    contentDescription: String? = null
) {
    FloatingActionButton(
        onClick = onClick,
        // Apply default padding and the passed modifier
        modifier = modifier.padding(16.dp),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AppFloatingActionButtonBoxPreview() {
    // Demonstrating positioning in a Box (Bottom Right)
    Box(modifier = Modifier.fillMaxSize()) {
        AppFloatingActionButton(
            onClick = { /* Handle click */ },
            modifier = Modifier.align(Alignment.BottomEnd),
            icon = Icons.Default.Settings,
            contentDescription = "Menu"
        )
    }
}
