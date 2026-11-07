package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The platform half of casting, installed once at startup.
 *
 * It is an injected interface rather than `expect`/`actual` because this fork
 * compiles for iOS and Android too, and a seam that answers "no renderers" on
 * those targets must not require a real implementation on either. The desktop's
 * implementation is the only one that is actually installed.
 */
interface CastPlatform {
    suspend fun discover(timeoutMillis: Long): List<CastDevice>
    suspend fun play(device: CastDevice, url: String, title: String): CastPlayResult
    suspend fun stop(device: CastDevice): CastPlayResult
}

/** The renderer the player currently believes it is casting to. */
data class ActiveCast(val device: CastDevice, val title: String)

/**
 * App-wide cast state: what was found, what is being scanned, and where the
 * player is currently pointed.
 *
 * Deliberately holds no URL: a stream token is short-lived and bound to a
 * renderer address, and keeping one here would outlive its authority. The
 * player mints a fresh one at each cast (see `BoomioStreamResolver.resolveForCast`).
 */
object CastController {
    /** Installed by the desktop entry point; null leaves every call inert. */
    var platform: CastPlatform? = null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _active = MutableStateFlow<ActiveCast?>(null)
    val active: StateFlow<ActiveCast?> = _active.asStateFlow()

    /** True when this build can cast at all — false hides the button rather than failing on tap. */
    fun isAvailable(): Boolean = platform != null

    suspend fun scan(): List<CastDevice> {
        val platform = platform ?: return emptyList()
        _scanning.value = true
        return try {
            platform.discover(CastDiscoveryDefaults.SCAN_WINDOW_MS).also { _devices.value = it }
        } finally {
            _scanning.value = false
        }
    }

    suspend fun cast(device: CastDevice, url: String, title: String): CastPlayResult {
        val platform = platform ?: return CastPlayResult.failed("Casting is not available in this build")
        val result = platform.play(device, url, title)
        if (result.ok) _active.value = ActiveCast(device, title) else _active.value = null
        return result
    }

    suspend fun stop(): CastPlayResult {
        val platform = platform ?: return CastPlayResult.Ok
        val active = _active.value ?: return CastPlayResult.Ok
        val result = runCatching { platform.stop(active.device) }
            .getOrElse { CastPlayResult.failed(it.message ?: "stop failed") }
        // The player is resumed by the caller either way: a renderer we cannot
        // reach is already not playing anything we control.
        _active.value = null
        return result
    }
}

object CastDiscoveryDefaults {
    /** One bounded scan; long enough for an M-SEARCH round trip on a busy LAN. */
    const val SCAN_WINDOW_MS = 2_500L
}
