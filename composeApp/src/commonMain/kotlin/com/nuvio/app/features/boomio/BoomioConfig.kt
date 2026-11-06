package com.nuvio.app.features.boomio

/**
 * Runtime configuration for the boomio media-plane integration seam.
 *
 * Both URLs default to blank, which keeps the seam **inert**: [BoomioStreamResolver]
 * and [BsmRatingGate] no-op when their respective base URL is blank, leaving the
 * existing addon/debrid resolvers as the primary stream sources.
 *
 * These are deliberately mutable vars rather than build-time constants so the
 * host application can inject them at startup from its own config source
 * (BuildConfig / gradle properties / env), matching the legacy
 * `BuildConfig.BOOMIO_BASE_URL` / `BuildConfig.BSM_BASE_URL` wiring. Nothing here
 * is read from a secret store or inlined — assign the values at app start.
 *
 * NOTE: this is a static, uncompiled port; the seam has not been exercised against
 * the boomio plane yet.
 */
object BoomioConfig {
    /** Base URL of the boomio media plane (bsf), e.g. `https://bsf.example.com`. */
    var boomioBaseUrl: String = ""

    /** Base URL of the BSM rating service, e.g. `https://bsm.example.com`. */
    var bsmBaseUrl: String = ""

    /**
     * Base URL of the bsc companion hub, e.g. `wss://bsc.example.com` — or, on the collapsed
     * edge, `wss://boomio.example.com/bsc`.
     *
     * ⚠️ **The `wss://` scheme and the path prefix are both load-bearing.** The companion
     * WebSocket appends `/ws` to this value and Ktor refuses an `https://` scheme there, while
     * [companionRestBaseUrl] converts the scheme back for REST calls and leaves the path alone,
     * so the edge's `/bsc` segment carries into both. Blank is inert, like the seams above:
     * [BoomioSessionRepository.startLink] refuses to run rather than dialing a bad URL.
     *
     * Sourced from `BOOMIO_COMPANION_URL`, or derived from [boomioBaseUrl]'s origin when that is
     * unset — see the build's runtime-config generation.
     */
    var companionBaseUrl: String = ""

    /**
     * The server's address on the WireGuard overlay, e.g. `10.77.0.1`. Blank disables the
     * overlay resolver.
     *
     * **Learned, not configured.** Enrollment derives it from the assigned CIDR, so a build
     * aimed at no deployment in particular still gets it right once the device has enrolled.
     * An operator-supplied value stands in only until then.
     */
    var overlayServerAddress: String = ""

    /**
     * **This device's own** address inside the overlay, CIDR form — `10.77.0.2/32`.
     * Written by enrollment; it must be the address the server enrolled this key with.
     */
    var overlayLocalCidr: String = ""

    /** Where the tunnel's UDP goes, in the clear — the server's public endpoint. */
    var overlayEndpoint: String = ""

    /** The server's WireGuard public key, base64, as the enrollment assigned it. */
    var overlayServerPubKey: String = ""

    /** The name the server gave this device; the `CN` its client certificate must carry. */
    var overlayDeviceName: String = ""

    /** True when the companion seam is configured ([companionBaseUrl] is set). */
    fun companionEnabled(): Boolean = companionBaseUrl.isNotBlank()
}

/**
 * REST (`https://`) variant of [BoomioConfig.companionBaseUrl] for the bsc companion API.
 *
 * The device-code exchange that mints the `bs_ses_*` session lives entirely on this base, so a
 * scheme mistake here fails pairing before anything else can be attempted.
 */
val BoomioConfig.companionRestBaseUrl: String
    get() = companionBaseUrl.trimEnd('/')
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")
