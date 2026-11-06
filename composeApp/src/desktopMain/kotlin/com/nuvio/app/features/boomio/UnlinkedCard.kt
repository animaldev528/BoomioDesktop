package com.nuvio.app.features.boomio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioSurfaceCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.companion_cancel
import nuvio.composeapp.generated.resources.companion_connect
import nuvio.composeapp.generated.resources.companion_link_approval_hint
import nuvio.composeapp.generated.resources.companion_link_awaiting_approval
import nuvio.composeapp.generated.resources.companion_link_failed_expired
import nuvio.composeapp.generated.resources.companion_link_failed_start
import nuvio.composeapp.generated.resources.companion_linking
import org.jetbrains.compose.resources.stringResource

/**
 * The link machine, rendered.
 *
 * Ported from mobile's `CompanionScreen.UnlinkedCard`, which drives one state machine for two
 * screens. Desktop has only one of them — the first-run setup step — so the two headline strings
 * are **required** here rather than defaulted to the companion's copy. Mobile's defaults describe
 * controlling a *TV* from a phone; there is no such screen in this fork, and a default nobody
 * passes is a second description that can never be corrected.
 *
 * ⚠️ **The states are the repository's, not this file's.** `Idle → Starting → Linking →
 * AwaitingApproval → Idle | Failed` is what [BoomioSessionRepository.startLink] publishes, and
 * rendering it — rather than driving a second copy of the flow here — is what makes the cancel,
 * retry and completion paths the same ones the session already had. A state added there appears
 * here for free.
 *
 * ⚠️ **Success needs no callback.** The link finishes by writing a session; the gate observes
 * that session and advances by itself. Nothing in this card calls back on success, so there is no
 * path where a completed link leaves a stale screen behind.
 */
@Composable
internal fun UnlinkedCard(
    linkState: BoomioLinkState,
    title: String,
    description: String,
) {
    val connectLabel = stringResource(Res.string.companion_connect)
    val linkingLabel = stringResource(Res.string.companion_linking)
    val failStart = stringResource(Res.string.companion_link_failed_start)
    val failExpired = stringResource(Res.string.companion_link_failed_expired)
    val awaitingApprovalLabel = stringResource(Res.string.companion_link_awaiting_approval)
    val approvalHint = stringResource(Res.string.companion_link_approval_hint)
    val cancelLabel = stringResource(Res.string.companion_cancel)

    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (linkState) {
                BoomioLinkState.Idle -> {
                    Button(onClick = { BoomioSessionRepository.startLink() }) {
                        Text(connectLabel)
                    }
                }
                BoomioLinkState.Starting, BoomioLinkState.Linking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(linkingLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is BoomioLinkState.AwaitingApproval -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(awaitingApprovalLabel, style = MaterialTheme.typography.bodyMedium)
                        }
                        // ⚠️ Selectable, which mobile's copy is not: the code is typed into a
                        // *different* machine (the admin page), and a desktop has no long-press
                        // to copy with. One drag and a copy is the whole point of showing it.
                        SelectionContainer {
                            Text(linkState.userCode, style = MaterialTheme.typography.headlineSmall)
                        }
                        linkState.verificationUri?.let { uri ->
                            Text(
                                uri,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            approvalHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { BoomioSessionRepository.cancelLink() }) {
                            Text(cancelLabel)
                        }
                    }
                }
                is BoomioLinkState.Failed -> {
                    val message = when (linkState.reason) {
                        BoomioLinkFailure.Expired -> failExpired
                        BoomioLinkFailure.Start -> failStart
                    }
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { BoomioSessionRepository.startLink() }) {
                        Text(connectLabel)
                    }
                }
            }
        }
    }
}
