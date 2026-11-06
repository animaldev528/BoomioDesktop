package com.nuvio.app.features.boomio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.overlay.OverlayEndpointStatusRow
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.device_setup_description
import nuvio.composeapp.generated.resources.device_setup_skip
import nuvio.composeapp.generated.resources.device_setup_title
import org.jetbrains.compose.resources.stringResource

/**
 * The first-run setup step: pair this desktop with its server, **before** the sign-in screen.
 *
 * Ported from mobile unchanged in structure. The reasoning is the same one and it is worth
 * restating, because it is the reason this screen exists at all: pairing is not a preference but
 * the thing that has to happen first. A device that has never paired has no address to reach the
 * server on, so it cannot sign in *first* — there would be nothing to sign in *to*. Leaving the
 * machine in a Settings destination meant a fresh install could only reach it by getting past a
 * login it needed pairing to do.
 *
 * ── What is different on desktop ────────────────────────────────────────────
 * ⚠️ **There is no provisioning channel here, so this walks the LAN path.** Mobile's
 * `startLink` first tries the channel — a forwarded port that needs no prior credential — because
 * a phone off the LAN has nothing else. Desktop has no such transport in this fork, so every
 * attempt dials [BoomioConfig.companionRestBaseUrl], which is baked at build time from
 * `BOOMIO_COMPANION_URL` (or derived from `BOOMIO_BASE_URL`). A build with neither is *inert*:
 * [BoomioSessionRepository.startLink] refuses and reports it rather than dialing a bad URL. That is
 * the correct behaviour for a stock build and the reason the skip below is not optional.
 *
 * ⚠️ **The device-code flow needs no account, and that is deliberate.** `startLink` is not gated
 * on `AuthState.Authenticated`, so a signed-out device runs the same flow; a signed-in one may
 * additionally self-approve. Being signed out means taking the human path, not failing.
 *
 * ⚠️ **Skipping is allowed, and it is not a nicety.** Pairing is a property of *this deployment*,
 * not of Nuvio: someone pointing the app at an ordinary server has nothing to pair with, and a
 * gate with no way past it would lock them out of the app entirely.
 *
 * Success needs no callback: the link finishes by writing a session, the gate observes that
 * session, and it moves on by itself. `onSkip` exists only for the case where there is nothing to
 * wait for.
 *
 * ⚠️ **The endpoint row is on this screen because pairing is not the first thing that has to
 * work — reaching the server is.** A device that cannot find the overlay endpoint has nothing to
 * pair *against*. `alwaysOfferManual` shows the field from the first frame rather than only once
 * the walk has failed, which on desktop — where the ladder has one rung and no discovery — is the
 * difference between an offer and a dead end.
 */
@Composable
internal fun DeviceSetupGate(
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkState by BoomioSessionRepository.linkState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background)
            // Scrollable because the awaiting-approval state grows — a code, a URI and a hint —
            // and this is the first screen a fresh install shows, so it is also the one most
            // likely to meet a small window.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The card owns the headline and the explainer; passing the setup wording in keeps one
        // copy of each rather than a centred pair above a card that repeats them.
        UnlinkedCard(
            linkState = linkState,
            title = stringResource(Res.string.device_setup_title),
            description = stringResource(Res.string.device_setup_description),
        )

        Spacer(Modifier.height(16.dp))
        // ⚠️ Below the card and above the skip, because it is the *other* way to get moving: the
        // card's Connect is blocked on an address that this row is what supplies.
        OverlayEndpointStatusRow(
            modifier = Modifier.fillMaxWidth(),
            showUnavailable = true,
            alwaysOfferManual = true,
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onSkip) {
            Text(stringResource(Res.string.device_setup_skip))
        }
    }
}
