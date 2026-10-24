package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Update
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.VAViewModel

private const val PROJECT = "Project"

fun projectConfig(vm: VAViewModel) = ConfigSection(
    "Project", Icons.Default.Code, listOf(
        ConfigItem.Action(PROJECT, "Check for Updates", "Check for latest version of this app",
            Icons.Default.Update, onClick = { vm.checkForUpdate() }),
        ConfigItem.Action(PROJECT, "GitHub Repository", "Link to this projects github repo",
            Icons.Default.Download, opensLink = true,
            onClick = { openUri -> openUri(APPConfig.GITHUB_RELEASES_URL) }),
    )
)
