package com.nuvio.app.features.boomio

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.BoomioServerOverride
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.ServerDiscoveryException
import com.nuvio.app.core.network.ServerDiscoveryFailure
import com.nuvio.app.core.network.ServerDiscoveryService
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.overlay.OverlayEnrollmentController
import com.nuvio.app.core.overlay.OverlaySession
import com.nuvio.app.features.auth.ServerSwitchFailure

/** A switch that failed after the address itself was accepted. */
internal class ServerSwitchException(val reason: ServerSwitchFailure) : Exception(reason.name)

/**
 * Points the whole app — account, streaming, casting, telemetry, pairing — at one typed server.
 *
 * ⚠️ **This is the desktop answer to a build-time `BOOMIO_BASE_URL`, and it exists because the
 * alternative is a rebuild.** The Windows build takes its media plane as a workflow input, so a
 * fresh install used to be welded to whatever box that run named. A first-run address replaces
 * that: one `<origin>/.well-known/nuvio` read gives the account backend *and* the publishable
 * key, and [BoomioServerOverride] derives the three collapsed-edge endpoints from the same
 * origin, so all of it moves together.
 *
 * ⚠️ **The discovery document is the validation, not a formality.** It is what proves the
 * address is a Nuvio server rather than a webserver that happens to answer, and it is the only
 * place the publishable key comes from. A typed address that does not serve it is refused with
 * the reason the document gave.
 */
internal object DesktopServerSwitch {

    /**
     * Switches to the server at [raw] and returns its origin.
     *
     * A bare host is tried over HTTPS first — the edge redirects HTTP to it anyway — and once
     * more over HTTP, which is the only thing a LAN box without a certificate for its bare name
     * can answer. An input that carries a scheme is used exactly as typed.
     */
    suspend fun connect(raw: String): Result<String> {
        val candidates = candidateInputs(raw)
        if (candidates.isEmpty()) {
            return Result.failure(
                ServerDiscoveryException(ServerDiscoveryFailure.InvalidUrl),
            )
        }

        var lastFailure: ServerDiscoveryException? = null
        for (candidate in candidates) {
            val server = ServerDiscoveryService.discover(candidate).fold(
                onSuccess = { it },
                onFailure = { error ->
                    lastFailure = error as? ServerDiscoveryException
                        ?: ServerDiscoveryException(
                            ServerDiscoveryFailure.ConnectionFailed,
                            cause = error,
                        )
                    null
                },
            ) ?: continue

            // The account backend changes, so whatever session belongs to the old server has to
            // go first — the same order `ServerConnectionController` uses.
            if (AuthRepository.prepareForServerSwitch().isFailure) {
                return Result.failure(ServerSwitchException(ServerSwitchFailure.SessionClear))
            }
            if (!ServerConfigurationRepository.saveCustom(server)) {
                return Result.failure(ServerSwitchException(ServerSwitchFailure.Save))
            }
            // Every boomio endpoint moves before anything re-reads it: the refresh below probes
            // the new server, and the pairing that follows dials the companion base.
            BoomioServerOverride.apply(server.backendUrl)
            // The old box's pairing and its cached tunnel belong to the server just left.
            // `unlink()` is wrong here — its unpair POST is aimed at the *old* companion base —
            // so the local session is dropped directly and the next pairing is a pairing to the
            // new server.
            BoomioSessionRepository.forgetLocalSession()
            OverlaySession.stop()
            OverlayEnrollmentController.forget()
            SupabaseProvider.reset()
            AuthRepository.reinitialize()
            NetworkStatusRepository.requestRefresh(force = true)
            return Result.success(server.backendUrl)
        }

        return Result.failure(
            lastFailure ?: ServerDiscoveryException(ServerDiscoveryFailure.ConnectionFailed),
        )
    }

    private fun candidateInputs(raw: String): List<String> {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.contains("://")) return listOf(trimmed)
        return listOf(trimmed, "http://$trimmed").distinct()
    }
}
