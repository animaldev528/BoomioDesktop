package com.nuvio.app.features.cast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * The screen picker, usable from anywhere — not just the player.
 *
 * Choosing a screen here is the *first* step of a cast, not the last: the
 * selection is handed back to the caller, which records it in [CastLaunchStore]
 * and opens the streams list so the viewer picks a release the screen can play.
 * Scanning happens on open, and again on request, exactly as it does in the
 * player's own picker.
 */
@Composable
internal fun CastLaunchDialog(
    onSelect: (CastDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val devices by CastController.devices.collectAsState()
    val scanning by CastController.scanning.collectAsState()
    val active by CastController.active.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (devices.isEmpty()) runCatching { CastController.scan() }
    }

    CastPickerDialog(
        devices = devices,
        scanning = scanning,
        activeDeviceId = active?.device?.id,
        progressDeviceName = null,
        stopping = false,
        errorDeviceName = null,
        errorReason = null,
        onRescan = { scope.launch { runCatching { CastController.scan() } } },
        onSelect = onSelect,
        onStop = {},
        onDismiss = onDismiss,
    )
}
