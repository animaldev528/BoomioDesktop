package com.nuvio.app.features.boomio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.overlay.OverlayEndpointState
import com.nuvio.app.core.overlay.OverlayEndpointStatus
import com.nuvio.app.core.overlay.OverlayEndpointStatusRow
import com.nuvio.app.core.overlay.OverlayEnrollmentController
import com.nuvio.app.core.overlay.OverlayEnrollmentState
import com.nuvio.app.core.overlay.OverlayTunnelControl
import com.nuvio.app.core.overlay.OverlayTunnelUiState
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.device_setup_description
import nuvio.composeapp.generated.resources.device_setup_title
import nuvio.composeapp.generated.resources.vpn_enroll_failed
import nuvio.composeapp.generated.resources.vpn_enroll_pending
import nuvio.composeapp.generated.resources.vpn_enroll_retry
import nuvio.composeapp.generated.resources.vpn_intro
import nuvio.composeapp.generated.resources.vpn_label_address
import nuvio.composeapp.generated.resources.vpn_label_device_name
import nuvio.composeapp.generated.resources.vpn_label_endpoint
import nuvio.composeapp.generated.resources.vpn_label_overlay_address
import nuvio.composeapp.generated.resources.vpn_label_server_key
import nuvio.composeapp.generated.resources.vpn_not_enrolled_body
import nuvio.composeapp.generated.resources.vpn_not_enrolled_title
import nuvio.composeapp.generated.resources.vpn_private_key_note
import nuvio.composeapp.generated.resources.vpn_section_endpoint
import nuvio.composeapp.generated.resources.vpn_section_interface
import nuvio.composeapp.generated.resources.vpn_section_peer
import nuvio.composeapp.generated.resources.vpn_tunnel_connected
import nuvio.composeapp.generated.resources.vpn_tunnel_disconnect
import nuvio.composeapp.generated.resources.vpn_tunnel_disconnected
import nuvio.composeapp.generated.resources.vpn_tunnel_down
import nuvio.composeapp.generated.resources.vpn_tunnel_failed
import nuvio.composeapp.generated.resources.vpn_tunnel_reconnect
import nuvio.composeapp.generated.resources.vpn_tunnel_title
import nuvio.composeapp.generated.resources.vpn_tunnel_unsupported
import nuvio.composeapp.generated.resources.vpn_value_unset
import org.jetbrains.compose.resources.stringResource

/**
 * The WireGuard details this desktop is using to reach the box, as a settings page body.
 *
 * Mobile's `VpnScreen` is a whole screen with its own header because its settings navigate away to
 * it. Desktop's settings navigate *in place* — [SettingsPage] names a page and the screen swaps the
 * body — so this is a `LazyListScope` extension like every other `…SettingsContent` here, and the
 * sticky header and back arrow come from the settings screen rather than from this file.
 *
 * Read-only on purpose, and for the reason mobile's screen gives: what it reports is *learned*, not
 * configured. The device name, the tunnel address and the server's public key are written by
 * enrollment from what the server assigned, and the overlay address is derived rather than sent. A
 * screen that let someone type over them would offer an edit that the next enrollment silently
 * reverts — and, worse, an address the server never agreed to, which comes up as a handshake that
 * succeeds and then drops every return packet. So this reports; the things that change these values
 * are pairing a device and the endpoint ladder, and both live elsewhere.
 *
 * ⚠️ **The endpoint is shown twice, and the two are not the same fact.** [vpn_section_endpoint]
 * renders [OverlayEndpointStatusRow] — the *ladder's* answer to "where is the box", discovered and
 * typed, and the value that can actually bring a tunnel up. [vpn_label_endpoint] is
 * [BoomioConfig.overlayEndpoint], what this *build* was compiled with, which on most builds is blank
 * and inert. Reading one for the other is the mistake this layout exists to prevent, so neither is
 * labelled "Endpoint" alone.
 *
 * ⚠️ **Enrollment is [BoomioConfig.overlayDeviceName] being non-blank, not the endpoint.** The name
 * is the one field the server only ever writes through an enrollment, so it is the honest signal; a
 * build with a baked endpoint and no enrollment would otherwise claim to be enrolled, and the
 * address and key below would be build defaults rather than this device's.
 *
 * ⚠️ **The private key is deliberately absent, and its absence is stated out loud.**
 * [vpn_private_key_note] says so, because a VPN screen that lists everything *except* the key
 * otherwise reads as one that is hiding something.
 *
 * ⚠️ **Blank is a real state, not an error.** A build with no deployment baked in and no enrollment
 * yet has every field empty, so the page says "not enrolled" and still renders the tables — an
 * empty card would be indistinguishable from a broken one.
 *
 * ── What this adds over mobile's screen ─────────────────────────────────────
 * ⚠️ **A retry.** Enrollment runs when a session appears and at startup, and nowhere else: a
 * transient failure — the box briefly unreachable while the machine was booting, a ladder that had
 * not found the endpoint yet — used to leave the device unenrolled until the next launch, with no
 * visible way to ask again. [OverlayEnrollmentController.refresh] is that way, and it belongs here
 * because this is the one screen that can show *why* the last attempt failed. The button only
 * appears when there is a session to enroll with: without one there is nothing to ask as, and the
 * body above already says what is missing.
 */
internal fun LazyListScope.vpnSettingsContent(isTablet: Boolean) {
    item {
        Text(
            text = stringResource(Res.string.vpn_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isTablet) 20.dp else 16.dp),
        )
    }

    // ⚠️ "Not enrolled" is two different states and they need two different screens.
    //
    // No boomio session at all: there is nothing to ask *as*, so the retry below would answer
    // `Unavailable` forever. That device gets the pairing card — the same one first-run setup
    // shows — because this page is the only place left that can reach it. Without this, a machine
    // that signed in before it paired can never pair: setup is only routed to from the
    // unauthenticated branch of `AppGate`, and signing in (or continuing without an account, which
    // creates an anonymous one) goes straight past it.
    //
    // Paired but not yet assigned: the retry, which asks again.
    //
    // The test lives *inside* the item, not in the builder above it. `overlayDeviceName` is a plain
    // `var` with no flow behind it — enrollment writes it — so a read while the list is being built
    // would be a value nothing observes, and a device that enrolled with this page open would keep
    // showing "not enrolled" until the row was recreated. Read during composition, the section
    // disappears the moment the assignment lands.
    item {
        val enrolled = BoomioConfig.overlayDeviceName.trim().isNotEmpty()
        val session by BoomioSessionRepository.session.collectAsStateWithLifecycle()
        if (!enrolled) {
            if (session == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 20.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val linkState by BoomioSessionRepository.linkState.collectAsStateWithLifecycle()
                    UnlinkedCard(
                        linkState = linkState,
                        title = stringResource(Res.string.device_setup_title),
                        description = stringResource(Res.string.device_setup_description),
                    )
                    // The card's Connect dials a companion URL it cannot discover, so the row that
                    // supplies one belongs with it rather than in the section below.
                    OverlayEndpointStatusRow(
                        modifier = Modifier.fillMaxWidth(),
                        showUnavailable = true,
                        alwaysOfferManual = true,
                    )
                }
            } else {
                SettingsSection(
                    title = stringResource(Res.string.vpn_not_enrolled_title),
                    isTablet = isTablet,
                ) {
                    SettingsGroup(isTablet = isTablet) {
                        EnrollmentStatus(isTablet = isTablet)
                    }
                }
            }
        }
    }

    // Above the endpoint card on purpose: this is the control, and the cards below are the facts it
    // acts on. Leading with the diagnosis would leave the one thing this page can *do* below the
    // fold.
    //
    // Hidden entirely on a platform with no tunnel. A card saying "unsupported" above a working
    // endpoint is noise; there is nothing here for that user.
    item {
        val tunnel by OverlayTunnelControl.state.collectAsStateWithLifecycle()
        val endpointStatus by OverlayEndpointState.status.collectAsStateWithLifecycle()
        if (tunnel != OverlayTunnelUiState.Unsupported) {
            SettingsSection(
                title = stringResource(Res.string.vpn_tunnel_title),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = overlayTunnelText(tunnel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                        Button(
                            onClick = {
                                // Real teardown, remembered across restarts -- see OverlaySession.
                                // The projection above is what comes back.
                                if (tunnel is OverlayTunnelUiState.Connected) {
                                    OverlayTunnelControl.disconnect()
                                } else {
                                    OverlayTunnelControl.reconnect()
                                }
                            },
                            // Nothing to reconnect to before the ladder has any answer at all. Once
                            // the user disconnected by hand it stays live even from `Idle`:
                            // Reconnect re-walks the ladders, so it is their retry.
                            enabled = tunnel !is OverlayTunnelUiState.Down ||
                                endpointStatus != OverlayEndpointStatus.Idle,
                        ) {
                            Text(
                                // The label is the action, not the state: a button reading
                                // "Connected" is one you press to find out what happens.
                                text = if (tunnel is OverlayTunnelUiState.Connected) {
                                    stringResource(Res.string.vpn_tunnel_disconnect)
                                } else {
                                    stringResource(Res.string.vpn_tunnel_reconnect)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // The ladder's own row, not a copy of it: `NeedsManual` is the one state that carries an action,
    // and that row is the only thing that renders the field which performs it. Hidden while the
    // ladder is `Idle` — a platform that never started it has nothing to say, and an empty card
    // would read as a broken one.
    item {
        val endpointStatus by OverlayEndpointState.status.collectAsStateWithLifecycle()
        if (endpointStatus != OverlayEndpointStatus.Idle) {
            SettingsSection(
                title = stringResource(Res.string.vpn_section_endpoint),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 8.dp),
                    ) {
                        OverlayEndpointStatusRow(
                            modifier = Modifier.fillMaxWidth(),
                            showUnavailable = true,
                        )
                    }
                }
            }
        }
    }

    item {
        VpnDetailSection(
            title = stringResource(Res.string.vpn_section_interface),
            isTablet = isTablet,
            rows = listOf(
                stringResource(Res.string.vpn_label_device_name) to BoomioConfig.overlayDeviceName,
                stringResource(Res.string.vpn_label_address) to BoomioConfig.overlayLocalCidr,
                stringResource(Res.string.vpn_label_overlay_address) to BoomioConfig.overlayServerAddress,
            ),
        )
    }

    item {
        VpnDetailSection(
            title = stringResource(Res.string.vpn_section_peer),
            isTablet = isTablet,
            rows = listOf(
                stringResource(Res.string.vpn_label_endpoint) to BoomioConfig.overlayEndpoint,
                stringResource(Res.string.vpn_label_server_key) to BoomioConfig.overlayServerPubKey,
            ),
        )
    }

    item {
        Text(
            text = stringResource(Res.string.vpn_private_key_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isTablet) 20.dp else 16.dp),
        )
    }
}

/**
 * What the last enrollment attempt did, and — when there is something to enroll *as* — the way to
 * ask again.
 *
 * ⚠️ **The failure reason is shown verbatim.** It is the enroller's own words for why the request
 * did not land; paraphrasing it would make it unsearchable in a log or an issue.
 *
 * ⚠️ **`Unavailable` is not a failure and gets no retry.** It means there is no companion session,
 * so [OverlayEnrollmentController.refresh] would answer the same thing every time — the body text
 * above already explains that state, and a button that cannot change it would be a lie.
 */
@Composable
private fun EnrollmentStatus(isTablet: Boolean) {
    val session by BoomioSessionRepository.session.collectAsStateWithLifecycle()
    val enrollment by OverlayEnrollmentController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.vpn_not_enrolled_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        when (val state = enrollment) {
            is OverlayEnrollmentState.Failed -> Text(
                text = stringResource(Res.string.vpn_enroll_failed, state.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
            OverlayEnrollmentState.Enrolling -> Text(
                text = stringResource(Res.string.vpn_enroll_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
            else -> Unit
        }
        if (session != null) {
            TextButton(
                onClick = { scope.launch { OverlayEnrollmentController.refresh() } },
                enabled = enrollment !is OverlayEnrollmentState.Enrolling,
            ) {
                Text(stringResource(Res.string.vpn_enroll_retry))
            }
        }
    }
}

/**
 * One titled group of label/value pairs.
 *
 * Values are monospaced because every one of them is a machine token someone may need to compare
 * character by character — a base64 key, an address, a `host:port`. Proportional digits make two
 * keys that differ in one character look identical at a glance, which is the one mistake this page
 * exists to make impossible.
 */
@Composable
private fun VpnDetailSection(
    title: String,
    isTablet: Boolean,
    rows: List<Pair<String, String>>,
) {
    SettingsSection(title = title, isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                    Text(
                        // Resolved here rather than inside the row so the empty case is one string
                        // for every value on the page.
                        text = value.trim().ifEmpty { stringResource(Res.string.vpn_value_unset) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * What the Private tunnel row says about the tunnel.
 *
 * ⚠️ **`Disconnected` and `Down` are different sentences on purpose.** `Down` is the app's answer
 * before anything has happened — a cold start, or enrollment that has not supplied an address — and
 * telling someone "you disconnected" when they did not would send them looking for a switch they
 * never touched. Only [OverlayTunnelUiState.Disconnected] means a person asked for this, and only
 * that one promises the state will hold.
 *
 * ⚠️ **The failure reason is shown verbatim.** It is the binding's own words for why `up()`
 * refused; paraphrasing it would make it unsearchable in a log or an issue.
 */
@Composable
private fun overlayTunnelText(state: OverlayTunnelUiState): String = when (state) {
    OverlayTunnelUiState.Connected -> stringResource(Res.string.vpn_tunnel_connected)
    OverlayTunnelUiState.Disconnected -> stringResource(Res.string.vpn_tunnel_disconnected)
    OverlayTunnelUiState.Down -> stringResource(Res.string.vpn_tunnel_down)
    OverlayTunnelUiState.Unsupported -> stringResource(Res.string.vpn_tunnel_unsupported)
    is OverlayTunnelUiState.Failed ->
        stringResource(Res.string.vpn_tunnel_failed, state.reason)
}
