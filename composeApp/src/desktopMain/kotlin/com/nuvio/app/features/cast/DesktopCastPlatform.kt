package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Desktop's half of casting: discovery on the LAN, the per-protocol transports in
 * `CastPlayback`, and the one code a television may ask for.
 *
 * Discovery is two scans with different reach. SSDP finds DLNA televisions, Rokus
 * and Cast receivers through their DIAL advertisement. mDNS `_airplay._tcp` finds
 * AirPlay receivers — and for an Apple TV it is the *only* one that finds anything,
 * because an Apple TV answers no SSDP search at all. A scan that stopped at SSDP
 * reported "no screens" while the television sat on the same switch.
 *
 * Installed from `Main.kt`. Nothing here holds state that matters — [CastController]
 * does — except the code a pairing is waiting for, which lives only as long as the
 * cast that asked for it.
 */
internal object DesktopCastPlatform : CastPlatform {

    /** A television shows its code for a couple of minutes; this bounds the wait. */
    private const val PIN_TIMEOUT_MS = 180_000L

    /**
     * The cast waiting for a code. A cast blocks inside the sender until this is
     * completed, so nothing else can report progress meanwhile.
     */
    @Volatile
    private var pinWaiter: CompletableDeferred<String>? = null

    /**
     * A code typed before the cast reached the point of asking for it. The field
     * goes up as soon as [requiresPin] answers, which is before the push starts, so
     * a fast typist can beat the sender to it.
     */
    @Volatile
    private var typedPin: String? = null

    init {
        DesktopAirPlay.pinRequester = {
            val early = typedPin
            typedPin = null
            if (early != null) {
                early
            } else {
                // The sender has stopped to ask, which is the only moment that
                // proves a code is wanted: the TXT record does not always say so
                // (an Apple TV on tvOS 26 contradicts it).
                CastController.onPinRequired?.invoke()
                val waiter = CompletableDeferred<String>()
                pinWaiter = waiter
                try {
                    withTimeoutOrNull(PIN_TIMEOUT_MS) { waiter.await() }
                } finally {
                    if (pinWaiter === waiter) pinWaiter = null
                }
            }
        }
    }

    /**
     * How long the mDNS half may take before the panel is given what SSDP found.
     * JmDNS can block inside a socket call that ignores cancellation (a VPN or
     * filter driver is the usual culprit), so this is a ceiling on the wait, not a
     * claim that the scan has stopped.
     */
    private const val AIRPLAY_BUDGET_MS = 6_000L

    private val log = Logger.withTag("DesktopCastPlatform")

    override suspend fun discover(timeoutMillis: Long): List<CastDevice> =
        discoverProgressive(timeoutMillis) {}

    override suspend fun discoverProgressive(
        timeoutMillis: Long,
        onPartial: (List<CastDevice>) -> Unit,
    ): List<CastDevice> {
        // SSDP first, and published first: it is what finds the screens this build
        // has always been able to cast to. Whatever happens to mDNS after this,
        // those rows are already on the panel.
        val ssdp = runCatching { CastDiscovery.discover(timeoutMillis) }.getOrElse {
            log.w { "SSDP scan failed: ${it.message}" }
            emptyList()
        }
        onPartial(ssdp)

        // ⚠️ The mDNS half runs in its own scope, not as a child of this call.
        // A blocking native call cannot be cancelled, so under structured
        // concurrency a wedged interface would hold `await` — and with it the
        // whole scan — long past the panel's patience. Detached, the worst case is
        // a scan abandoned to its own timeout while the user still sees the SSDP
        // rows and can cast.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val airplay = scope.async {
            runCatching { AirPlayDiscovery.discover(timeoutMillis) }.getOrElse {
                log.w { "mDNS scan failed: ${it.message}" }
                emptyList()
            }
        }
        val found = withTimeoutOrNull(AIRPLAY_BUDGET_MS) { airplay.await() } ?: run {
            log.w { "mDNS scan gave no answer within ${AIRPLAY_BUDGET_MS}ms; listing SSDP screens only" }
            emptyList()
        }
        scope.cancel()

        // Keyed by protocol + address, so a screen that speaks two protocols keeps
        // one row per protocol (they are different ways to drive it) while a
        // duplicate from the same protocol collapses.
        return (ssdp + found).distinctBy { it.id }.also(onPartial)
    }

    override suspend fun play(
        device: CastDevice,
        url: String,
        title: String,
        sourceLabel: String?,
    ): CastPlayResult = playOnRenderer(device, url, title, sourceLabel)

    override suspend fun stop(device: CastDevice): CastPlayResult = stopOnRenderer(device)

    override suspend fun requiresPin(device: CastDevice): Boolean =
        DesktopAirPlay.needsPin(device)

    override fun submitPin(code: String) {
        if (code.isBlank()) return
        val waiting = pinWaiter
        if (waiting != null) {
            pinWaiter = null
            waiting.complete(code)
        } else {
            typedPin = code
        }
    }
}
