package com.msp1974.vacompanion.ui.layouts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.layouts.config.permissionsConfig

/**
 * The permissions on their own, for the connection screen where the Configuration screen isn't
 * available. It shows the same section the Configuration screen has.
 */
@Composable
fun PermissionsLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val section = remember { permissionsConfig(viewModel) }
    RefreshPermissionsOnResume(viewModel)
    ConfigSectionLayout(
        title = "App Permissions",
        section = section,
        viewModel = viewModel,
        onBack = onBack,
        modifier = modifier
    )
}

// Permissions can be changed in system settings, so check them when shown and when returned to.
@Composable
fun RefreshPermissionsOnResume(viewModel: VAViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissionsStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.refreshPermissionsStatus() }
}
