package com.msp1974.vacompanion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.theme.AppTheme
import kotlin.math.max
import kotlin.math.min

/** One button in the settings tile grid, with its own colour theme. */
data class SettingsTile(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val color: Color,
    val onClick: () -> Unit
)

// Tiles are at least this wide and tall: well over the 48dp minimum touch target, and they grow
// with the system font size rather than clipping their text.
private val MIN_TILE_WIDTH = 168.dp
private val MIN_TILE_HEIGHT = 112.dp
private const val MAX_COLUMNS = 4

/**
 * A grid of [SettingsTile]s. The number of columns follows the width available, so it adapts to
 * the screen size and to rotation; with only one column the tiles lay out as wide rows. The grid
 * scrolls when the tiles don't fit, e.g. in landscape on a small screen.
 */
@Composable
fun SettingsTileGrid(
    tiles: List<SettingsTile>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val gap = 12.dp
        val columns = min(MAX_COLUMNS, max(1, ((maxWidth - gap) / (MIN_TILE_WIDTH + gap)).toInt()))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            items(tiles) { tile ->
                SettingsTileButton(tile, wide = columns == 1)
            }
        }
    }
}

@Composable
private fun SettingsTileButton(tile: SettingsTile, wide: Boolean) {
    Surface(
        onClick = tile.onClick,
        shape = RoundedCornerShape(16.dp),
        color = tile.color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, tile.color.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TILE_HEIGHT)
    ) {
        val icon: @Composable () -> Unit = {
            Surface(
                shape = CircleShape,
                color = tile.color.copy(alpha = 0.25f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(tile.icon, contentDescription = null, tint = tile.color, modifier = Modifier.size(28.dp))
                }
            }
        }
        val text: @Composable (Modifier) -> Unit = { textModifier ->
            Column(modifier = textModifier) {
                Text(
                    text = tile.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (tile.subtitle != null) {
                    Text(
                        text = tile.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (wide) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp)
            ) {
                icon()
                Spacer(Modifier.width(16.dp))
                text(Modifier.weight(1f))
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                icon()
                text(Modifier)
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 400)
@Composable
fun SettingsTileGridPreview() {
    AppTheme {
        SettingsTileGrid(
            tiles = listOf(
                SettingsTile("Manage Custom Files", "Wake words, sounds and alarms", Icons.Default.FileCopy, Color(0xFF14B8A6)) {},
                SettingsTile("Configuration", "View the settings provided by Home Assistant", Icons.Default.Tune, Color(0xFF6366F1)) {},
            )
        )
    }
}
