package com.nuvio.app.core.overlay

import kotlinx.coroutines.flow.StateFlow

/**
 * What the VPN screen is allowed to say about the private tunnel.
 *
 * ⚠️ **A projection, not a second state machine.** The device behind this has exactly one state
 * (`TunnelState`); this adds exactly one fact the device cannot know — whether a *person* asked
 * for it to stay down — and [overlayTunnelUiState] is the whole of the relation. Anything that
 * needed to remember more would be two sources of truth, and two sources is how "Disconnect"
 * ends up showing "Connected" while the tunnel is down.
 *
 * ⚠️ On mobile this is an `expect` object, because the screen that draws it lives in
 * `commonMain` and cannot see the Android overlay package. The desktop fork has no such split —
 * the overlay package is `desktopMain` only — so it is a plain object here, and the `Unsupported`
 * case stays in the type for the platforms that do need it.
 */
sealed interface OverlayTunnelUiState {

    /** Carrying traffic: `up()` succeeded and a handshake completed. */
    data object Connected : OverlayTunnelUiState

    /** The user asked for it down. **Wins over a device that still reports `Up`.** */
    data object Disconnected : OverlayTunnelUiState

    /** Not up, and nobody asked for that: no endpoint yet, or it is still coming up. */
    data object Down : OverlayTunnelUiState

    /**
     * This platform has no tunnel at all. Distinct from [Down], which promises that connecting
     * is merely a matter of time, and from [Failed], which offers a reason worth retrying.
     */
    data object Unsupported : OverlayTunnelUiState

    /** `up()` refused. [reason] is the binding's own words, shown verbatim so it can be searched. */
    data class Failed(val reason: String) : OverlayTunnelUiState
}

/** The manual Disconnect / Reconnect control, for the VPN screen. */
internal object OverlayTunnelControl {
    val state: StateFlow<OverlayTunnelUiState>
        get() = OverlaySession.connectionState

    fun disconnect() {
        OverlaySession.disconnect()
    }

    fun reconnect() {
        OverlaySession.reconnect()
    }
}

/**
 * Projects the device plus the user's choice onto one displayable state.
 *
 * ⚠️ **The manual flag is checked first, before `Up`.** The teardown is asynchronous — the flag
 * is set on the caller's thread and `down()` runs on a background scope — so between those two
 * moments the device genuinely reports `Up`. Reading that as "Connected" for a frame would flip
 * the button back to "Disconnect" under the user's finger, which reads as the press having
 * failed. The user's intent is the earlier and stronger fact, so it wins.
 */
internal fun overlayTunnelUiState(
    state: TunnelState,
    manuallyDisconnected: Boolean,
): OverlayTunnelUiState = when {
    manuallyDisconnected -> OverlayTunnelUiState.Disconnected
    state is TunnelState.Up -> OverlayTunnelUiState.Connected
    state is TunnelState.Failed -> OverlayTunnelUiState.Failed(state.reason)
    else -> OverlayTunnelUiState.Down
}
