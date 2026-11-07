package com.nuvio.app.core.device

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.bsmTelemetryBaseUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.GraphicsEnvironment
import java.time.Instant
import java.util.Locale

/**
 * Posts this machine's hardware capabilities to bsm, and keeps doing so.
 *
 * Two dashboard facts hang off this one call:
 *
 *  - **"Awaiting Hardware Telemetry…"** is what bsm renders for a device whose
 *    `capabilities` column is `{}`. Nothing else fills it — pairing only creates
 *    the row — so without a report the desktop is a permanent blank card.
 *  - **Online** is `last_seen` within two hours *for a mobile-shaped report*, and
 *    the capability route is one of the writers of `last_seen`. So the heartbeat
 *    is not housekeeping: at the mobile cadence (12 h) the device would be shown
 *    Offline for the other ten hours even while it is playing. [HEARTBEAT_MS] is
 *    30 min, well inside the [ONLINE_GRACE].
 *
 * ⚠️ **[applicationId] must name the desktop, and bsm must know the name.** bsm
 * asks `app.applicationId` whether a row is a self-reporting client, falling back
 * to the `nuvio-mobile-` install-id prefix. The desktop keeps that prefix (it is
 * the identity bsc and the music routes already know it by), so a blank app id
 * made this row a phone: online, but labelled "Phone / Tablet". Sending
 * [DESKTOP_APP_ID] fixes the label and stays online — but only against a bsm that
 * has `isDesktopReport` (models of this app built before that fix read Offline).
 */
internal object DeviceCapabilityReporter {
    private val log = Logger.withTag("DeviceCapability")

    /** 30 min, well inside the two-hour `last_seen` window bsm counts as online. */
    private const val HEARTBEAT_MS = 30 * 60 * 1000L

    /** Long enough that startup work (enrollment, tunnel, first paint) is not competing. */
    private const val INITIAL_DELAY_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { explicitNulls = false; encodeDefaults = true }

    @Volatile
    private var started = false

    /**
     * Starts the loop. Safe to call once per process; a blank bsm base leaves it
     * inert rather than guessing a host.
     */
    fun initialize() {
        if (started) return
        started = true
        if (BoomioConfig.bsmTelemetryBaseUrl.isBlank()) {
            log.i { "no bsm base URL; capability reporting disabled" }
            return
        }
        scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                reportOnce()
                delay(HEARTBEAT_MS)
            }
        }
    }

    /** One report. Best-effort: a failure is logged and simply retried next heartbeat. */
    suspend fun reportOnce(): Boolean {
        val base = BoomioConfig.bsmTelemetryBaseUrl
        if (base.isBlank()) return false
        val body = runCatching { json.encodeToString(DeviceCapabilityProbe.build()) }
            .onFailure { log.w(it) { "capability report could not be built" } }
            .getOrNull() ?: return false
        return runCatching {
            httpPostJsonWithHeaders(
                url = "$base/api/prov/devices/capability-report",
                body = body,
                headers = emptyMap(),
            )
            log.d { "capability report posted" }
            true
        }.getOrElse { error ->
            // Off-LAN, tunnel down, box still booting — all ordinary. The next
            // heartbeat is 30 min away, and nothing in the app depends on this.
            log.d { "capability report failed: ${error.message}" }
            false
        }
    }

}

/**
 * The app id bsm reads twice: `isMobileReport` is false for it (so the phone
 * fallback stops applying), and `isDesktopReport` is true — which is what both
 * keeps this row online and labels it Desktop.
 */
private const val DESKTOP_APP_ID = "com.boomio.desktop"

/**
 * Assembles `device-caps/1` from what a JVM can actually see.
 *
 * The honest limit here is decode and HDR: there is no `MediaCodecList` on the
 * desktop. What *is* known is that this player asks libmpv for hardware decoding
 * on every platform it ships (`d3d11va` on Windows, `hwdec=auto` elsewhere) and
 * bundles ffmpeg's software decoders as the fallback, so both flags are true —
 * but no HDR profile and no Dolby Vision profile is claimed, because neither can
 * be verified from here and a false HDR claim is worse than an SDR row.
 */
internal object DeviceCapabilityProbe {
    /** What a sink of unknown size is assumed to be — never claimed as capability of its own. */
    private const val DEFAULT_WIDTH = 1920
    private const val DEFAULT_HEIGHT = 1080

    fun build(): DeviceCapabilityReport {
        var sinkWidth = 0
        var sinkHeight = 0
        var refreshHz = 0
        runCatching {
            val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            val mode = device.displayMode
            sinkWidth = mode.width
            sinkHeight = mode.height
            refreshHz = mode.refreshRate
        }

        val width = sinkWidth.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val height = sinkHeight.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        val osName = System.getProperty("os.name").orEmpty()
        val osLower = osName.lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").orEmpty()

        return DeviceCapabilityReport(
            reportedAt = Instant.now().toString(),
            device = DeviceSection(
                installId = SyncClientIdentity.currentClientId(),
                hwFingerprint = DeviceHardwareFingerprint.value(),
                manufacturer = when {
                    osLower.contains("win") -> "Microsoft"
                    osLower.contains("mac") -> "Apple"
                    else -> "PC"
                },
                model = when {
                    osLower.contains("win") -> "Windows Desktop"
                    osLower.contains("mac") -> "Mac"
                    else -> "Linux Desktop"
                },
                abis = listOf(arch),
            ),
            app = AppSection(
                // ⚠️ What keeps this device online, and what makes the dashboard
                // call it a Desktop. bsm decides both from this string: a report
                // that names no app id falls back to the `nuvio-mobile-` install
                // prefix — that is the phone, which is why blanking this field
                // made the row read "Phone / Tablet". `isDesktopReport` (bsm
                // dashboard/modules/devices.js) is the other half; a build of this
                // app that runs against an older bsm reads Offline, so the server
                // must be deployed first.
                applicationId = DESKTOP_APP_ID,
                versionName = AppVersionConfig.DESKTOP_VERSION_NAME,
                versionCode = AppVersionConfig.DESKTOP_VERSION_CODE,
            ),
            display = DisplaySection(
                sinkWidth = sinkWidth,
                sinkHeight = sinkHeight,
                maxRefreshHz = refreshHz,
                maxSupportedRefreshHz = refreshHz,
            ),
            decode = DecodeSection(
                avc = CodecSupport(maxWidth = width, maxHeight = height),
                hevc = CodecSupport(maxWidth = width, maxHeight = height),
                av1 = CodecSupport(maxWidth = width, maxHeight = height),
                vp9 = CodecSupport(maxWidth = width, maxHeight = height),
            ),
            effective = EffectiveSection(maxResolution = "${width}x$height"),
        )
    }
}

@Serializable
internal data class DeviceCapabilityReport(
    val schema: String = "device-caps/1",
    val reportedAt: String,
    val device: DeviceSection,
    val app: AppSection,
    val display: DisplaySection,
    val decode: DecodeSection,
    val effective: EffectiveSection,
)

@Serializable
internal data class DeviceSection(
    val installId: String,
    val hwFingerprint: String,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int = 0,
    val androidRelease: String = "",
    val abis: List<String> = emptyList(),
)

@Serializable
internal data class AppSection(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val flavor: String = "desktop",
    val debug: Boolean = false,
)

@Serializable
internal data class DisplaySection(
    val sinkWidth: Int,
    val sinkHeight: Int,
    val maxRefreshHz: Int,
    val maxSupportedRefreshHz: Int,
    val sinkHdrTypes: List<String> = emptyList(),
    val hdrCapsKnown: Boolean = false,
)

@Serializable
internal data class DecodeSection(
    val avc: CodecSupport,
    val hevc: CodecSupport,
    val av1: CodecSupport,
    val vp9: CodecSupport,
    val dolbyVision: DolbyVisionSupport = DolbyVisionSupport(),
)

/**
 * `hw` and `sw` are both true because the player ships both paths, not because a
 * probe enumerated this GPU's decoders — no such enumeration exists from the JVM.
 * The ceiling is the sink, which is the only resolution this app ever renders at.
 */
@Serializable
internal data class CodecSupport(
    val hw: Boolean = true,
    val sw: Boolean = true,
    val maxWidth: Int,
    val maxHeight: Int,
)

@Serializable
internal data class DolbyVisionSupport(
    val profiles: List<String> = emptyList(),
    val decoderPresent: Boolean = false,
)

@Serializable
internal data class EffectiveSection(
    val maxResolution: String,
    /** Empty is SDR: this player has no way to prove an HDR-capable sink. */
    val hdrUsable: List<String> = emptyList(),
)
