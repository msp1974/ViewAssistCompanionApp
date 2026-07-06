package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.msp1974.vacompanion.data.AvailableAlarm
import com.msp1974.vacompanion.data.AvailableWakeSound
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.theme.AppTheme
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.WakeWordType

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

    val tabs = listOf("Wake Words", "Wake Sounds", "Alarms")
    val engines = listOf("microWakeWord", "openWakeWord", "openWakeWord_rt")

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

    // Reset selection when changing tabs or engine
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
                    if (selectedFiles.size == files.size) {
                        selectedFiles = emptySet()
                    } else {
                        selectedFiles = files.map {
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
                            val type = when (selectedEngineTab) {
                                0 -> WakeWordType.MICROWAKEWORD
                                1 -> WakeWordType.OPENWAKEWORD
                                2 -> WakeWordType.OPENWAKEWORD_RT
                                else -> WakeWordType.MICROWAKEWORD
                            }
                            viewModel.deleteWakeWordModels(type, selectedFiles.toList())
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
            }
            
            if (!selectionMode) {
                IconButton(onClick = { viewModel.syncCustomFiles() }) {
                    Icon(Icons.Default.Sync, contentDescription = "Sync Custom Files", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        if (selectedTab == 0) {
            SecondaryTabRow(
                selectedTabIndex = selectedEngineTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                engines.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedEngineTab == index,
                        onClick = { selectedEngineTab = index },
                        text = { Text(title.replace("_", " ").capitalizeWords(), style = MaterialTheme.typography.bodyMedium) }
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
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
                    isSelected = isSelected,
                    selectionMode = selectionMode,
                    onToggleSelection = {
                        if (!selectionMode) selectionMode = true
                        selectedFiles = if (isSelected) {
                            selectedFiles - id
                        } else {
                            selectedFiles + id
                        }
                    },
                    onDelete = {
                        if (selectedTab == 0) {
                            val type = when (selectedEngineTab) {
                                0 -> WakeWordType.MICROWAKEWORD
                                1 -> WakeWordType.OPENWAKEWORD
                                2 -> WakeWordType.OPENWAKEWORD_RT
                                else -> WakeWordType.MICROWAKEWORD
                            }
                            viewModel.deleteWakeWordModel(type, id)
                        } else {
                            val subDir = if (selectedTab == 1) CustomFileDownloader.SOUNDS_DIR else CustomFileDownloader.ALARMS_DIR
                            viewModel.deleteCustomFile(subDir, id)
                        }
                    }
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
            
            if (files.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No custom ${tabs[selectedTab].lowercase()} found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
fun FileListItem(
    name: String,
    isSelected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelection() },
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            
            Text(
                text = name, // Use raw ID/name as provided
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
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

@Preview(showBackground = true)
@Composable
fun CustomFilesLayoutPreview() {
    AppTheme {
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
                IconButton(onClick = {}) {
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
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            TabRow(selectedTabIndex = 0) {
                Tab(selected = true, onClick = {}, text = { Text("Wake Words") })
                Tab(selected = false, onClick = {}, text = { Text("Wake Sounds") })
                Tab(selected = false, onClick = {}, text = { Text("Alarms") })
            }

            SecondaryTabRow(
                selectedTabIndex = 0,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Tab(selected = true, onClick = {}, text = { Text("microWakeWord".replace("_", " ").capitalizeWords(), style = MaterialTheme.typography.bodySmall) })
                Tab(selected = false, onClick = {}, text = { Text("openWakeWord".replace("_", " ").capitalizeWords(), style = MaterialTheme.typography.bodySmall) })
                Tab(selected = false, onClick = {}, text = { Text("openWakeWord_rt".replace("_", " ").capitalizeWords(), style = MaterialTheme.typography.bodySmall) })
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(listOf("hey_jarvis", "alexa", "ok_google")) { name ->
                    FileListItem(
                        name = name.replace("_", " ").capitalizeWords(),
                        isSelected = name == "alexa",
                        selectionMode = true,
                        onToggleSelection = {},
                        onDelete = {}
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
