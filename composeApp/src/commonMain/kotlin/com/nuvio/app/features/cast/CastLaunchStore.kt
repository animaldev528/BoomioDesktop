package com.nuvio.app.features.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The screen a viewer chose before there was a stream to hand it. */
data class PendingCast(val device: CastDevice, val target: CastTarget)

/**
 * The cast a viewer started from a title's action menu, if one is in flight.
 *
 * This is the flow the phone has always had: pick the screen first, then ask bsf
 * for what *that* screen can play. The desktop used to do the reverse — take the
 * stream already playing and re-resolve it for the renderer afterwards — which
 * handed the television releases its own caps filter had excluded.
 *
 * The device lives here rather than in the route because [CastTarget] is the only
 * thing the streams list needs, and the list, not this store, is what chooses a
 * release. Held as compose state so the streams screen recomposes when a new
 * screen is picked.
 */
object CastLaunchStore {
    var pending: PendingCast? by mutableStateOf(null)
        private set

    fun begin(device: CastDevice) {
        pending = PendingCast(device, CastTarget.of(device))
    }

    fun clear() {
        pending = null
    }
}
