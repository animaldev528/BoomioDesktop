package com.nuvio.app.features.cast

import kotlinx.coroutines.CompletableDeferred
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

    override suspend fun discover(timeoutMillis: Long): List<CastDevice> {
        val ssdp = CastDiscovery.discover(timeoutMillis)
        val airplay = AirPlayDiscovery.discover(timeoutMillis)
        // Keyed by protocol + address, so a screen that speaks two protocols keeps
        // one row per protocol (they are different ways to drive it) while a
        // duplicate from the same protocol collapses.
        return (ssdp + airplay).distinctBy { it.id }
    }

    override suspend fun play(device: CastDevice, url: String, title: String): CastPlayResult =
        playOnRenderer(device, url, title)

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
