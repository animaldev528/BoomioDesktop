package com.nuvio.app.features.cast

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

    /**
     * As [discover], but hands the answers found so far to [onPartial] while the
     * scan is still running.
     *
     * ⚠️ This exists because discovery is more than one scan. A platform that
     * probes two protocols of different reach and publishes only the merged list
     * lets a slow or failing half withhold the other's results — the panel then
     * shows nothing at all, including screens that were listed a version earlier.
     * Publishing per protocol means a broken half costs its own rows and no more.
     */
    suspend fun discoverProgressive(
        timeoutMillis: Long,
        onPartial: (List<CastDevice>) -> Unit,
    ): List<CastDevice> = discover(timeoutMillis).also(onPartial)

    /**
     * [sourceLabel] is the name of the stream being handed over (`⚡ TB · 69.0 GB`),
     * carried only so a desktop with no console can record which release a cast
     * actually pushed — the reason a failed cast used to be undiagnosable.
     */
    suspend fun play(
        device: CastDevice,
        url: String,
        title: String,
        sourceLabel: String? = null,
    ): CastPlayResult
    suspend fun stop(device: CastDevice): CastPlayResult

    /**
     * True when casting to [device] will first ask for a code its screen shows.
     * AirPlay on a television it has never paired with answers yes; every other
     * protocol answers no, so the field never appears where it cannot be needed.
     * Asked *before* the cast starts, because the cast blocks inside `play` while
     * it waits for an answer and the UI would have nothing to show meanwhile.
     */
    suspend fun requiresPin(device: CastDevice): Boolean = false

    /** Hands the code the user typed to a cast that is waiting for one. */
    fun submitPin(code: String) = Unit
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

    /**
     * Raised when a cast blocks because the *receiver* asked for a code, as opposed
     * to [CastPlatform.requiresPin] having predicted one.
     *
     * ⚠️ Both paths exist because the prediction is only a reading of the TXT
     * record, and a receiver can contradict it. Measured on an Apple TV 4K
     * (`AppleTV6,2`, tvOS 26.6.1, `flags=0x20244`): the record does not set
     * `PIN_REQUIRED` (`0x8`) and does advertise system pairing, so the library
     * predicts "pairs without a code" — and then the television puts a code on
     * screen at pair-setup anyway, because it refuses transient pairing. A field
     * driven only by the prediction never appears, and the cast waits out its
     * timeout with the code showing on a screen the app cannot answer.
     *
     * Set by the player while its cast panel can show a numeric field. Invoked
     * from an I/O thread.
     */
    var onPinRequired: (() -> Unit)? = null

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
            // A scan that throws used to leave the panel empty forever: the state
            // that says "scanning" was cleared, but no list ever replaced it.
            runCatching {
                platform.discoverProgressive(CastDiscoveryDefaults.SCAN_WINDOW_MS) {
                    _devices.value = it
                }.also { _devices.value = it }
            }.getOrElse { emptyList() }
        } finally {
            _scanning.value = false
        }
    }

    /**
     * Hands [url] to [device], one renderer at a time.
     *
     * ⚠️ The previous screen is stopped **first**, and if it will not stop the
     * cast is refused. There is one `_active` slot and one Stop button: leaving
     * the old television playing while the new one starts means the film is now
     * running on two screens with a control for only one of them, and the first
     * screen can only be silenced with its own remote.
     *
     * The push itself runs `NonCancellable`: a player screen going away mid-push
     * must not lose the answer, because the television may have started playing
     * regardless and [_active] is the only thing that remembers it.
     */
    suspend fun cast(device: CastDevice, url: String, title: String): CastPlayResult {
        val platform = platform ?: return CastPlayResult.failed("Casting is not available in this build")
        val previous = _active.value
        if (previous != null && previous.device.id != device.id) {
            val stopped = runCatching { platform.stop(previous.device) }
                .getOrElse { CastPlayResult.failed(it.message ?: "could not stop the other screen") }
            if (!stopped.ok) {
                return CastPlayResult.failed(
                    "${previous.device.name} is still playing and could not be stopped " +
                        "(${stopped.reason})",
                )
            }
            _active.value = null
        }
        val result = withContext(NonCancellable) { platform.play(device, url, title) }
        if (result.ok) _active.value = ActiveCast(device, title)
        return result
    }

    /**
     * Stops the active renderer, and forgets it **only if it stopped**.
     *
     * A refused stop (a Roku in Limited mode, a TV answering UPnP 701) leaves the
     * screen playing; clearing [_active] then would remove the only control that
     * can try again and report nothing.
     */
    suspend fun stop(): CastPlayResult {
        val platform = platform ?: return CastPlayResult.Ok
        val active = _active.value ?: return CastPlayResult.Ok
        val result = runCatching { platform.stop(active.device) }
            .getOrElse { CastPlayResult.failed(it.message ?: "stop failed") }
        if (result.ok) _active.value = null
        return result
    }

    /**
     * Whether casting to [device] will need a code from its screen, asked before
     * the cast starts so the UI can put the field up.
     */
    suspend fun requiresPin(device: CastDevice): Boolean =
        platform?.requiresPin(device) ?: false

    /** Hands the typed code to the cast that is waiting for it. */
    fun submitPin(code: String) {
        platform?.submitPin(code)
    }
}

object CastDiscoveryDefaults {
    /** One bounded scan; long enough for an M-SEARCH round trip on a busy LAN. */
    const val SCAN_WINDOW_MS = 2_500L
}
