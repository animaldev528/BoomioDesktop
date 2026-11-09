package com.nuvio.app.features.boomio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.ServerDiscoveryException
import com.nuvio.app.core.network.ServerDiscoveryFailure
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.auth.ServerSwitchFailure
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.desktop_server_action
import nuvio.composeapp.generated.resources.desktop_server_checking
import nuvio.composeapp.generated.resources.desktop_server_connected
import nuvio.composeapp.generated.resources.desktop_server_hint
import nuvio.composeapp.generated.resources.overlay_manual_label
import nuvio.composeapp.generated.resources.server_error_auth
import nuvio.composeapp.generated.resources.server_error_connection
import nuvio.composeapp.generated.resources.server_error_http
import nuvio.composeapp.generated.resources.server_error_invalid_document
import nuvio.composeapp.generated.resources.server_error_invalid_url
import nuvio.composeapp.generated.resources.server_error_missing_configuration
import nuvio.composeapp.generated.resources.server_error_not_self_hosted
import nuvio.composeapp.generated.resources.server_error_official_active
import nuvio.composeapp.generated.resources.server_error_response_too_large
import nuvio.composeapp.generated.resources.server_error_save
import nuvio.composeapp.generated.resources.server_error_service
import nuvio.composeapp.generated.resources.server_error_session_clear
import nuvio.composeapp.generated.resources.server_error_version
import org.jetbrains.compose.resources.stringResource

/**
 * The first-run "which server is this?" field.
 *
 * ⚠️ **Why this replaced the overlay endpoint field as the gate's primary input.** The overlay
 * field asked for a WireGuard `host:port`, and a person could not complete it anyway — the
 * handshake also needs the server's public key, which enrollment assigns and nobody can type.
 * A server *address* is the thing the user actually knows, and one `/.well-known/nuvio` read
 * turns it into the account backend, the media plane and the companion base at once. The overlay
 * row still lives on the VPN settings page for the tunnel's own diagnostics.
 *
 * ⚠️ **A successful switch needs no callback.** Saving the server reinitialises the auth stack
 * and republishes the active configuration; the gate stays put (there may still be a pairing to
 * do) and this row reports the address in force.
 */
@Composable
internal fun ServerAddressRow(modifier: Modifier = Modifier) {
    var draft by rememberSaveable { mutableStateOf("") }
    var connectedOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tokens = MaterialTheme.nuvio

    fun submit() {
        if (checking) return
        val target = draft.trim()
        if (target.isEmpty()) return
        checking = true
        failure = null
        scope.launch {
            DesktopServerSwitch.connect(target).fold(
                onSuccess = { origin ->
                    connectedOrigin = origin
                    failure = null
                },
                onFailure = { error ->
                    connectedOrigin = null
                    failure = error
                },
            )
            checking = false
        }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                failure = null
            },
            enabled = !checking,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(Res.string.overlay_manual_label)) },
            placeholder = { Text(stringResource(Res.string.desktop_server_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        val connected = connectedOrigin
        if (connected != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.desktop_server_connected, connected),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.accent,
            )
        }

        val problem = failure
        if (problem != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = switchErrorMessage(problem),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { submit() },
                enabled = !checking && draft.isNotBlank(),
                shape = tokens.shapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.colors.accent,
                    contentColor = tokens.colors.onAccent,
                ),
            ) {
                if (checking) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NuvioLoadingIndicator(
                            modifier = Modifier.size(16.dp),
                            color = tokens.colors.onAccent,
                        )
                        Text(stringResource(Res.string.desktop_server_checking))
                    }
                } else {
                    Text(stringResource(Res.string.desktop_server_action))
                }
            }
        }
    }
}

/**
 * A typed server that did not work, in the user's terms.
 *
 * The discovery document's own failure modes are reused rather than restated: each one already
 * says what the server did wrong, and a switch that failed *after* validation (clearing the old
 * session, saving) has its own messages.
 */
@Composable
private fun switchErrorMessage(error: Throwable): String = when (error) {
    is ServerSwitchException -> when (error.reason) {
        ServerSwitchFailure.SessionClear -> stringResource(Res.string.server_error_session_clear)
        ServerSwitchFailure.Save -> stringResource(Res.string.server_error_save)
        ServerSwitchFailure.Restart -> stringResource(Res.string.server_error_connection)
    }

    is ServerDiscoveryException -> when (error.failure) {
        ServerDiscoveryFailure.InvalidUrl -> stringResource(Res.string.server_error_invalid_url)
        ServerDiscoveryFailure.OfficialServer -> stringResource(Res.string.server_error_official_active)
        ServerDiscoveryFailure.ConnectionFailed -> stringResource(Res.string.server_error_connection)
        ServerDiscoveryFailure.HttpError -> stringResource(
            Res.string.server_error_http,
            error.statusCode ?: 0,
        )
        ServerDiscoveryFailure.ResponseTooLarge ->
            stringResource(Res.string.server_error_response_too_large)
        ServerDiscoveryFailure.InvalidDocument -> stringResource(Res.string.server_error_invalid_document)
        ServerDiscoveryFailure.UnsupportedVersion -> stringResource(Res.string.server_error_version)
        ServerDiscoveryFailure.WrongService -> stringResource(Res.string.server_error_service)
        ServerDiscoveryFailure.NotSelfHosted -> stringResource(Res.string.server_error_not_self_hosted)
        ServerDiscoveryFailure.MissingConfiguration ->
            stringResource(Res.string.server_error_missing_configuration)
        ServerDiscoveryFailure.UnsupportedAuthentication -> stringResource(Res.string.server_error_auth)
    }

    else -> stringResource(Res.string.server_error_connection)
}
