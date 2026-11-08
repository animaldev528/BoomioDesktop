package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.CredentialStore
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.Receiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * AirPlay casting on the desktop, through the vendored `airkast` sender
 * (`vendor/airkast-core`, Apache-2.0) — the same library the phone app casts with.
 *
 * This replaced a hand-written `POST /play`. Measured against an Apple TV 4K
 * (`AppleTV6,2`, tvOS 26.6.1), an unauthenticated `POST /play` on `:7000` is
 * answered **403**, so a bare URL push cannot work: AirPlay 2 needs a paired
 * session (HAP pair-setup, then Curve25519 + ChaCha20 on every control channel).
 * The library owns that whole handshake; all this file adds is a receiver record,
 * a place to keep pairings and a way to ask for the code.
 *
 * A PIN is asked for **once per television**, never per cast: pair-setup leaves
 * long-term credentials, and those are replayed by pair-verify afterwards. They
 * live beside the app's other state (`DesktopStorage.rootDir`) rather than in
 * memory, so a restart does not make the user find the remote again.
 */
internal object DesktopAirPlay {

    private val log = Logger.withTag("DesktopAirPlay")

    private val credentials: CredentialStore by lazy {
        CredentialStore.file(DesktopStorage.rootDir.resolve("airplay-pairings.properties").toFile())
    }

    /**
     * tvOS and macOS need opposite timing settings, and they are properties of the
     * sender rather than of a single cast, so there are two of them:
     *
     *  - **Apple TV**: announcing `"NTP"` makes tvOS stall for about 32 s and then
     *    answer SETUP with 400; the vendored patch sends `"PTP"` when NTP is off,
     *    which it opens in a fraction of a second. This is mandatory on tvOS.
     *  - **Everything else**: a Mac answers SETUP with 500 *without* NTP timing.
     *
     * A receiver is routed by `model`, which the TXT record carries (`AppleTV6,2`).
     */
    private val appleTvClient: Airkast by lazy { newClient(ntpTiming = false) }
    private val defaultClient: Airkast by lazy { newClient(ntpTiming = true) }

    private fun newClient(ntpTiming: Boolean): Airkast = Airkast {
        this.ntpTiming = ntpTiming
        this.credentialStore = credentials
    }

    private val sessionLock = Mutex()
    private var session: AirkastSession? = null

    /**
     * Where a code comes from, set by the player while its cast panel is able to
     * show a numeric field. Null means nobody can ask, and pairing fails with a
     * sentence rather than hanging.
     */
    @Volatile
    var pinRequester: (suspend () -> String?)? = null

    /** The `flags` bit a receiver sets when it wants a code before it will pair. */
    private const val PIN_REQUIRED_FLAG = 0x8L

    private fun isAppleTv(properties: Map<String, String>): Boolean =
        properties["model"]?.startsWith("AppleTV", ignoreCase = true) == true

    private fun receiverOf(device: CastDevice): Receiver = Receiver(
        name = device.name,
        host = device.ip,
        port = device.port.takeIf { it > 0 } ?: Receiver.DEFAULT_PORT,
        properties = if (isAppleTv(device.raw)) forcePin(device.raw) else device.raw,
    )

    /**
     * Makes the library ask for a code on a receiver that claims not to need one.
     *
     * ⚠️ Measured on an Apple TV 4K (`AppleTV6,2`, tvOS 26.6.1, `flags=0x20244`):
     * `PIN_REQUIRED` is `0x8`, the record leaves that bit clear and advertises
     * system pairing, so the sender takes the **transient** path. That path calls
     * `/pair-pin-start` — which is what puts `3939` on the television, the
     * library's own transient PIN — and is then answered **470 Connection
     * Authorization Required**. Transient pairing asks nobody for anything, so the
     * cast waits behind a code that can never be entered, and no PIN field can
     * appear because no question was ever asked.
     *
     * Setting the bit makes the library take the ordinary PIN path instead: a
     * fresh code appears on the television, [pinRequester] is asked for it, and
     * pair-setup leaves the long-term credentials that pair-verify replays on
     * every later cast. The rest of the record is untouched.
     */
    private fun forcePin(properties: Map<String, String>): Map<String, String> {
        val raw = properties["flags"].orEmpty().trim().removePrefix("0x").removePrefix("0X")
        val flags = (raw.toLongOrNull(16) ?: 0L) or PIN_REQUIRED_FLAG
        return properties + ("flags" to "0x%X".format(flags))
    }

    private fun clientFor(receiver: Receiver): Airkast =
        if (isAppleTv(receiver.properties)) appleTvClient else defaultClient

    /**
     * One line per cast decision, appended beside the stored pairings.
     *
     * A cast fails on a television the developer cannot see, and the player has no
     * console on a Windows desktop: without a file, "the code appears and nothing
     * happens" is all there is to go on.
     */
    private fun trace(line: String) = castTrace("airplay $line")

    /**
     * True when playing on [device] will ask for a code: the receiver says it
     * wants one and no pairing is stored yet. Checked before connecting so the UI
     * can put the field up first, instead of the cast stalling inside `connect`.
     */
    suspend fun needsPin(device: CastDevice): Boolean = withContext(Dispatchers.IO) {
        val receiver = receiverOf(device)
        if (receiver.compatibility != Compatibility.NeedsPin &&
            receiver.compatibility != Compatibility.NeedsPassword
        ) {
            return@withContext false
        }
        runCatching { credentials.get(receiver) }.getOrNull() == null
    }

    /**
     * Sends [url] to [device]. The URL must already be one the receiver can decode
     * (see [castPlaybackUrl] — an Apple TV is HLS-native and no better at Matroska
     * than a Cast receiver is).
     */
    suspend fun play(device: CastDevice, url: String): CastPlayResult = withContext(Dispatchers.IO) {
        val receiver = receiverOf(device)
        val ask = pinRequester
        when (receiver.compatibility) {
            Compatibility.NoVideo -> return@withContext CastPlayResult.failed(
                "${device.name} does not play video from a URL"
            )
            Compatibility.AccessRestricted -> return@withContext CastPlayResult.failed(
                "${device.name} accepts only its owner's own devices; set its AirPlay " +
                    "setting to \"Anyone on the Same Network\""
            )
            else -> Unit
        }
        if (ask == null && needsPin(device)) {
            return@withContext CastPlayResult.failed(
                "${device.name} needs the code it shows on screen, and this build has no way to ask for it"
            )
        }
        trace(
            "play ${device.name} ip=${device.ip} model=${receiver.model} " +
                "compat=${receiver.compatibility} ask=${ask != null}",
        )
        try {
            sessionLock.withLock {
                // One cast at a time: the sender holds a control channel to the
                // receiver it is driving, and a second session would leave the
                // first one's events pointing at a screen that has moved on.
                closeSession()
                val opened = clientFor(receiver).connect(receiver) { secret ->
                    val code = ask?.invoke()
                    if (code.isNullOrBlank()) {
                        // Plain exception on purpose: `AirkastException.PairingFailed`
                        // has an internal constructor, and an empty code is a local
                        // refusal, not a receiver verdict.
                        throw IllegalStateException(
                            "no ${secret.name.lowercase()} was entered for ${receiver.name}"
                        )
                    }
                    code
                }
                opened.load(Media(url))
                session = opened
                log.d { "airplay cast to ${receiver.name} (${receiver.model ?: "unknown"}) ok" }
            }
            trace("ok ${device.name}")
            CastPlayResult.Ok
        } catch (e: AirkastException) {
            log.w { "airplay cast to ${device.name} failed: ${e.message}" }
            trace("failed ${device.name}: ${e::class.simpleName}: ${e.message}")
            closeSession()
            CastPlayResult.failed(e.message ?: e.toString())
        } catch (e: Exception) {
            log.w { "airplay cast to ${device.name} failed: ${e.message}" }
            trace("failed ${device.name}: ${e::class.simpleName}: ${e.message}")
            closeSession()
            CastPlayResult.failed(e.message ?: e.toString())
        }
    }

    /**
     * Ends the cast. Best-effort, like the other transports: the caller resumes
     * local playback whatever this returns.
     */
    suspend fun stop(): CastPlayResult = withContext(Dispatchers.IO) {
        val current = sessionLock.withLock {
            val open = session
            session = null
            open
        } ?: return@withContext CastPlayResult.Ok
        try {
            current.stop()
            CastPlayResult.Ok
        } catch (e: Exception) {
            log.w { "airplay stop failed: ${e.message}" }
            CastPlayResult.failed(e.message ?: e.toString())
        } finally {
            runCatching { current.close() }
        }
    }

    private suspend fun closeSession() {
        val open = session
        session = null
        open?.let { runCatching { it.close() } }
    }
}
