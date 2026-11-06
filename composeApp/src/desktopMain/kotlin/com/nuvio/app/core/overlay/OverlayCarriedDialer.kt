package com.nuvio.app.core.overlay

/**
 * The tunnelled route as the relay asks for it: "give me a dialler, or null if there is no
 * tunnel right now."
 *
 * **Why this is its own object rather than a call into `OverlaySession`.** On Android the relay
 * reads `OverlaySession.dialerOrNull()` — but `OverlaySession` is the bring-up *policy*, and it
 * pulls in the discovery ladder (`OverlayEndpointDiscovery`, `OverlayLocalDiscovery`) and the
 * status probe (`OverlayTunnel`), none of which exist on desktop yet. The relay needs exactly one
 * thing from that object, and it is this function; taking it as a dependency would have made the
 * whole relay wait on the discovery plane for no reason.
 *
 * ⚠️ **Read live, per dial, never captured.** [OverlayRelay.start] runs once per process and is
 * called before the tunnel can possibly be up, so a dialler captured at start-up would hold
 * `TunnelState.Down` for the life of the process. This looks the state up when a connection
 * actually arrives, which is what lets the relay come up first and still carry traffic once the
 * ladder converges. Same rule as [DeferredTunnelDialer]'s own doc.
 *
 * When `OverlaySession` is ported it will delegate here rather than duplicate the body, so this
 * stays the single definition of "which dialler the relay carries traffic with".
 */
internal object OverlayCarriedDialer {

    /** A dialler backed by the running tunnel, or null when it is not up. */
    fun current(): OverlayDialer? =
        OverlayWgTunnelController.instance
            ?.takeIf { it.state.value is TunnelState.Up }
            ?.let { OverlayWgDialer(it.binding) }
}
