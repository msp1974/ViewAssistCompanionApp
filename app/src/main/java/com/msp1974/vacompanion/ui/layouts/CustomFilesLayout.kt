package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.msp1974.vacompanion.data.AvailableAlarm
import com.msp1974.vacompanion.data.AvailableWakeSound
import com.msp1974.vacompanion.ui.CustomFilesState
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.theme.AppTheme
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.WakeWordType

private data class FileCategory(val title: String, val icon: ImageVector, val color: Color)

private val FILE_CATEGORIES = listOf(
    FileCategory("Wake Words", Icons.Default.Mic, Color(0xFF6366F1)),        // indigo
    FileCategory("Wake Sounds", Icons.Default.MusicNote, Color(0xFF14B8A6)), // teal
    FileCategory("Alarms", Icons.Default.Alarm, Color(0xFFF59E0B)),          // amber
)

private val ENGINES = listOf("microWakeWord", "openWakeWord", "openWakeWord_rt")

private fun engineType(index: Int) = when (index) {
    1 -> WakeWordType.OPENWAKEWORD
    2 -> WakeWordType.OPENWAKEWORD_RT
    else -> WakeWordType.MICROWAKEWORD
}

private fun categoryCount(files: CustomFilesState, index: Int): Int = when (index) {
    0 -> files.microWakeWords.size + files.openWakeWords.size + files.openWakeWordsRT.size
    1 -> files.sounds.size
    else -> files.alarms.size
}

@Composable
fun CustomFilesLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.vacaState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedEngineTab by remember { mutableIntStateOf(0) }

    // Multi-selection state
    var selectedFiles by remember { mutableStateOf(setOf<String>()) }
    var selectionMode by remember { mutableStateOf(false) }

    val category = FILE_CATEGORIES[selectedTab]

    val files = when (selectedTab) {
        0 -> when (selectedEngineTab) {
            0 -> state.customFiles.microWakeWords
            1 -> state.customFiles.openWakeWords
            2 -> state.customFiles.openWakeWordsRT
            else -> emptyList()
        }
        1 -> state.customFiles.sounds
        2 -> state.customFiles.alarms
        else -> emptyList()
    }

    // Reset selection when changing category or engine
    LaunchedEffect(selectedTab, selectedEngineTab) {
        selectedFiles = emptySet()
        selectionMode = false
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCustomFiles()
    }

    Column(
        modifier = Modifier
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
            if (selectionMode) {
                IconButton(onClick = {
                    selectionMode = false
                    selectedFiles = emptySet()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                }
                Text(
                    text = "${selectedFiles.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                IconButton(onClick = {
                    selectedFiles = if (selectedFiles.size == files.size) {
                        emptySet()
                    } else {
                        files.map {
                            when (it) {
                                is AvailableWakeSound -> it.filename
                                is AvailableAlarm -> it.filename
                                is String -> it
                                else -> ""
                            }
                        }.toSet()
                    }
                }) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                }
                IconButton(
                    onClick = {
                        if (selectedTab == 0) {
                            viewModel.deleteWakeWordModels(engineType(selectedEngineTab), selectedFiles.toList())
                        } else {
                            val subDir = if (selectedTab == 1) CustomFileDownloader.SOUNDS_DIR else CustomFileDownloader.ALARMS_DIR
                            viewModel.deleteCustomFiles(subDir, selectedFiles.toList())
                        }
                        selectionMode = false
                        selectedFiles = emptySet()
                    },
                    enabled = selectedFiles.isNotEmpty()
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Selected",
                        tint = if (selectedFiles.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            } else {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ArrowLeft,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "Manage Custom Files",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = { viewModel.syncCustomFiles() }) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sync", fontWeight = FontWeight.Medium)
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // Wide screens get a labelled rail like the Configuration screen; small ones an icon rail.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(if (compact) 84.dp else 210.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    FILE_CATEGORIES.forEachIndexed { index, cat ->
                        CategoryRow(
                            category = cat,
                            count = categoryCount(state.customFiles, index),
                            selected = index == selectedTab,
                            compact = compact,
                            onClick = { selectedTab = index }
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedTab == 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ENGINES.forEachIndexed { index, title ->
                                FilterChip(
                                    selected = selectedEngineTab == index,
                                    onClick = { selectedEngineTab = index },
                                    label = { Text(title.replace("_", " ").capitalizeWords()) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = category.color.copy(alpha = 0.22f),
                                        selectedLabelColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(files) { item ->
                            val (id, displayName) = when (item) {
                                is AvailableWakeSound -> item.filename to item.name
                                is AvailableAlarm -> item.filename to item.name
                                is String -> item to item.replace("_", " ").capitalizeWords()
                                else -> "" to ""
                            }

                            val isSelected = selectedFiles.contains(id)
                            FileListItem(
                                name = displayName,
                                icon = category.icon,
                                accent = category.color,
                                isSelected = isSelected,
                                selectionMode = selectionMode,
                                onToggleSelection = {
                                    if (!selectionMode) selectionMode = true
                                    selectedFiles = if (isSelected) selectedFiles - id else selectedFiles + id
                                },
                                onDelete = {
                                    if (selectedTab == 0) {
                                        viewModel.deleteWakeWordModel(engineType(selectedEngineTab), id)
                                    } else {
                                        val subDir = if (selectedTab == 1) CustomFileDownloader.SOUNDS_DIR else CustomFileDownloader.ALARMS_DIR
                                        viewModel.deleteCustomFile(subDir, id)
                                    }
                                }
                            )
                        }

                        if (files.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        category.icon,
                                        contentDescription = null,
                                        tint = category.color.copy(alpha = 0.6f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "No custom ${category.title.lowercase()} found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.customFiles.isSyncing) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("Synchronising") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Syncing custom files with server...")
                }
            },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }
}

@Composable
private fun CategoryRow(
    category: FileCategory,
    count: Int,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) category.color.copy(alpha = 0.2f) else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(onClick = onClick)
    ) {
        val textColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        val badge: @Composable () -> Unit = {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(category.color, RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            )
        }
        if (compact) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 12.dp)
            ) {
                Icon(category.icon, contentDescription = null, tint = category.color, modifier = Modifier.size(26.dp))
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                badge()
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Icon(category.icon, contentDescription = null, tint = category.color, modifier = Modifier.size(22.dp))
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
                badge()
            }
        }
    }
}

@Composable
fun FileListItem(
    name: String,
    icon: ImageVector,
    accent: Color,
    isSelected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelection() },
        color = if (isSelected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                    modifier = Modifier.padding(end = 8.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(accent.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
            }

            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (!selectionMode) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 300)
@Composable
fun CustomFilesLayoutPreview() {
    AppTheme {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Hey Jarvis", "Alexa", "Ok Google").forEach {
                FileListItem(
                    name = it,
                    icon = Icons.Default.Mic,
                    accent = Color(0xFF6366F1),
                    isSelected = it == "Alexa",
                    selectionMode = false,
                    onToggleSelection = {},
                    onDelete = {}
                )
            }
        }
    }
}
