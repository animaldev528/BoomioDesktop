package com.nuvio.app.features.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cast_close
import nuvio.composeapp.generated.resources.cast_empty
import nuvio.composeapp.generated.resources.cast_failed
import nuvio.composeapp.generated.resources.cast_preparing
import nuvio.composeapp.generated.resources.cast_rescan
import nuvio.composeapp.generated.resources.cast_scanning
import nuvio.composeapp.generated.resources.cast_stop
import nuvio.composeapp.generated.resources.cast_stopping
import nuvio.composeapp.generated.resources.cast_title
import org.jetbrains.compose.resources.stringResource

/**
 * The cast sheet: what is on the network, and which one the player is on.
 *
 * It stays open for the whole exchange — the scan, the push and a failure — the
 * same way the phone's does. Closing on the tap would hide the only place a
 * "Roku is in Limited mode" can be read, and that message is the whole reason
 * the person is looking at this sheet.
 */
@Composable
internal fun CastPickerDialog(
    devices: List<CastDevice>,
    scanning: Boolean,
    activeDeviceId: String?,
    progressDeviceName: String?,
    stopping: Boolean,
    errorDeviceName: String?,
    errorReason: String?,
    onRescan: () -> Unit,
    onSelect: (CastDevice) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val progressText = progressDeviceName?.let {
        if (stopping) {
            stringResource(Res.string.cast_stopping, it)
        } else {
            stringResource(Res.string.cast_preparing, it)
        }
    }
    val errorText = if (errorDeviceName != null && errorReason != null) {
        stringResource(Res.string.cast_failed, errorDeviceName, errorReason)
    } else {
        null
    }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.cast_title),
    ) {
        when {
            progressText != null -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text(text = progressText, style = MaterialTheme.typography.bodyMedium)
                }
            }

            scanning -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text(
                        text = stringResource(Res.string.cast_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            devices.isEmpty() -> {
                Text(
                    text = stringResource(Res.string.cast_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    devices.forEach { device ->
                        CastDeviceRow(
                            device = device,
                            isActive = device.id == activeDeviceId,
                            onClick = { onSelect(device) },
                        )
                    }
                }
            }
        }

        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRescan, enabled = !scanning && progressText == null) {
                Text(stringResource(Res.string.cast_rescan))
            }
            if (activeDeviceId != null) {
                TextButton(onClick = onStop, enabled = progressText == null) {
                    Text(stringResource(Res.string.cast_stop))
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            // Disabled, not hidden: the exchange is in flight and the renderer
            // may already be playing, so the sheet has to stay until it answers.
            TextButton(onClick = onDismiss, enabled = progressText == null) {
                Text(stringResource(Res.string.cast_close))
            }
        }
    }
}

@Composable
private fun CastDeviceRow(device: CastDevice, isActive: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive) tokens.colors.textPrimary else tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The protocol is a promise about what can be handed over: DLNA
            // plays H.264 directly, a Cast/Roku target is remuxed to HLS, and
            // AirPlay is refused on this build. Naming it means the person can
            // match a failure to the thing that failed.
            Text(
                text = device.protocol.name,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textSecondary,
            )
        }
        if (isActive) {
            Text(
                text = "●",
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.accent,
            )
        }
    }
}
