package com.nuvio.app.core.overlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.overlay_endpoint_found
import nuvio.composeapp.generated.resources.overlay_endpoint_needs_manual
import nuvio.composeapp.generated.resources.overlay_endpoint_searching
import nuvio.composeapp.generated.resources.overlay_endpoint_unavailable
import nuvio.composeapp.generated.resources.overlay_manual_hint
import nuvio.composeapp.generated.resources.overlay_manual_label
import nuvio.composeapp.generated.resources.overlay_manual_submit
import org.jetbrains.compose.resources.stringResource

/**
 * One line describing what the endpoint ladder found, or nothing at all — and, when the
 * ladder is out of rungs, the field that answers it.
 *
 * Ported from mobile unchanged in behaviour. [LocalServerStatusRow] answers a different
 * question — *where boomio traffic is going* — and the two can disagree; a device whose ladder has
 * exhausted every rung has no server to report at all, so folding this into that row would make
 * the one state that needs an action the one state that is invisible.
 *
 * ── ⚠️ Why this row matters *more* on desktop ──────────────────────────────
 * Desktop's ladder has one rung (see `OverlayEndpointDiscovery`): it uses the endpoint enrollment
 * wrote, and nothing else. Mobile races mDNS against a DuckDNS TXT record first, so a phone that
 * comes back to a changed network usually finds its server again by itself; a desktop that does
 * not have an assignment has no way to look. That makes [OverlayEndpointStatus.NeedsManual] not a
 * fallback but the ordinary path for a machine whose enrollment never landed, and it is why the
 * field below is the substantive half of this file rather than a convenience.
 *
 * ⚠️ **[OverlayEndpointStatus.NeedsManual] renders unconditionally, and the rest do not.**
 * The design has no LAN fallback, so a discovery failure must fail *visibly* and carry the action
 * the user can take. Every other state is either progress or noise, so those stay behind
 * [showUnavailable].
 *
 * ⚠️ **[alwaysOfferManual] breaks that pairing on purpose, and only the first-run gate uses it.**
 * On the gate the user is watching a walk they did not ask for, and the state they will most
 * likely be in while they read it is `Searching`, where tying the field to `NeedsManual` would
 * hide it for the whole of the walk. The gate therefore shows the field from the first frame, so
 * it is an offer rather than an answer to an error.
 */
@Composable
internal fun OverlayEndpointStatusRow(
    modifier: Modifier = Modifier,
    showUnavailable: Boolean = false,
    alwaysOfferManual: Boolean = false,
) {
    val current by OverlayEndpointState.status.collectAsState()
    val tokens = MaterialTheme.nuvio

    val text: String? = when (val status = current) {
        // Not started, or the seam is switched off — the same silence the LAN row keeps.
        OverlayEndpointStatus.Idle -> null

        OverlayEndpointStatus.Searching ->
            if (showUnavailable) stringResource(Res.string.overlay_endpoint_searching) else null

        // ⚠️ Silent here on purpose. A rung having found an endpoint is not the same as the
        // app using it — the tunnel still has to come up and answer — and when it does,
        // `LocalServerStatusRow` already reports the address the traffic is actually taking.
        is OverlayEndpointStatus.Found ->
            if (showUnavailable) stringResource(Res.string.overlay_endpoint_found, status.endpoint.authority) else null

        // See the doc: never gated.
        is OverlayEndpointStatus.NeedsManual ->
            stringResource(Res.string.overlay_endpoint_needs_manual, status.reason)

        is OverlayEndpointStatus.Unavailable ->
            if (showUnavailable) stringResource(Res.string.overlay_endpoint_unavailable, status.reason) else null
    }

    val showField = alwaysOfferManual || current is OverlayEndpointStatus.NeedsManual
    if (text == null && !showField) return

    Column(modifier = modifier) {
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                // Amber for the actionable failure, muted for everything else — so the one line the
                // user can *do* something about does not look like the three they cannot.
                color = if (current is OverlayEndpointStatus.NeedsManual) tokens.colors.warning else tokens.colors.textMuted,
            )
        }

        if (showField) {
            if (text != null) Spacer(Modifier.height(8.dp))
            ManualEndpointField()
        }
    }
}

/**
 * Rung 3's input: a server address a person types.
 *
 * ⚠️ **One field, not two — the server's public key is deliberately not asked for.** It is a
 * base64 X25519 key published by the mDNS advert and the DuckDNS TXT record, and a person cannot
 * read it off anything they own. Rung 3 falls back to the key this process holds from enrollment,
 * and when even that is absent the row's own reason says so. A key input would be a field nobody
 * could fill in correctly, which is worse than no field.
 *
 * ⚠️ **The draft outlives the status it was typed under.** Submitting republishes through the
 * ladder — `Searching`, then `Found` or a fresh `NeedsManual` — so this composable is recomposed
 * and possibly left and re-entered while the user is still looking at it. It is `rememberSaveable`
 * for that reason, and it is deliberately *not* cleared on submit: a rejected address is one the
 * user wants to correct, and clearing the box would make them retype it from memory to find their
 * own typo.
 */
@Composable
private fun ManualEndpointField() {
    var draft by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val trimmed = draft.trim()

    // ⚠️ The handler is registered by `OverlaySession.initialize()` at startup, ahead of the first
    // composition, so in the app this is never null — `NeedsManual` can only be published by the
    // ladder that also registers it. It is read rather than observed because a plain `var` has no
    // snapshot invalidation and none is wanted: there is one ladder per process and it exists for
    // the whole of it. A null handler is a test or a pre-initialise composition, and a disabled
    // button says so rather than failing silently on click.
    val wired = OverlayEndpointState.manualSubmit != null
    val enabled = wired && trimmed.isNotEmpty()

    fun submit() {
        if (!enabled) return
        scope.launch { OverlayEndpointState.submitManual(trimmed) }
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        enabled = wired,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(Res.string.overlay_manual_label)) },
        placeholder = { Text(stringResource(Res.string.overlay_manual_hint)) },
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

    Spacer(Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = { submit() }, enabled = enabled) {
            Text(stringResource(Res.string.overlay_manual_submit))
        }
    }
}
