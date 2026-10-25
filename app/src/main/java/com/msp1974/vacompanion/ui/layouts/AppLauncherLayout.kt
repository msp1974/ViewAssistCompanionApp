package com.msp1974.vacompanion.ui.layouts

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

private data class LauncherApp(val packageName: String, val label: String)

/** Every app on the device that has a launcher icon, other than this one, sorted by name. */
private fun installedApps(context: Context): List<LauncherApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { LauncherApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** The device's other launcher (home) apps: what the page contains until the user edits it. */
private fun otherLauncherPackages(context: Context): List<String> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName }
        .distinct()
}

/** The app for [packageName], or null if it has since been uninstalled. */
private fun appFor(context: Context, packageName: String): LauncherApp? = try {
    val pm = context.packageManager
    LauncherApp(packageName, pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString())
} catch (_: PackageManager.NameNotFoundException) {
    null
}

private fun launch(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        Timber.w("No launch intent for $packageName")
        return
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Timber.w("Unable to launch $packageName: ${e.message}")
    }
}

@Composable
private fun AppIcon(packageName: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.Default) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(128, 128).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(modifier = Modifier.size(size)) {
        icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(size)) }
    }
}

/**
 * A page of tiles that launch apps chosen from those installed on the device. The list is kept in
 * shared preferences only (it is not shared with Home Assistant); until it is first edited it holds
 * the device's other launcher apps.
 */
@Composable
fun AppLauncherLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val config = viewModel.config
    var selected by remember {
        mutableStateOf(config.launcherApps ?: otherLauncherPackages(viewModel.getApplication<Application>()))
    }
    var picking by remember { mutableStateOf(false) }

    fun update(list: List<String>) {
        selected = list
        config.launcherApps = list
    }

    if (picking) {
        val installed = remember { installedApps(context) }
        MenuLayout(title = "Choose apps", level = 1, onClose = { picking = false }) {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(installed, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    Surface(
                        onClick = {
                            update(if (checked) selected - app.packageName else selected + app.packageName)
                        },
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            AppIcon(app.packageName, 40.dp)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
        }
        return
    }

    val apps = remember(selected) { selected.mapNotNull { appFor(context, it) } }
    MenuLayout(title = "App Launcher", level = 1, onClose = onBack) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(apps, key = { it.packageName }) { app ->
                LauncherTile(label = app.label, onClick = { launch(context, app.packageName) }) {
                    AppIcon(app.packageName, 56.dp)
                }
            }
            item(key = "add") {
                LauncherTile(label = "Add / remove apps", onClick = { picking = true }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LauncherTile(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
