package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.boomio.BoomioConfig

/**
 * The collapsed-edge endpoints implied by one server origin.
 *
 * The deployment this app targets puts every service on a single host told apart by path —
 * `<origin>/bsf`, `<origin>/bsm`, `<origin>/bsc` — so a server address is enough to derive all
 * three. The companion keeps the `ws`/`wss` scheme because the WebSocket appends `/ws` to it and
 * Ktor refuses `https://` there; [companionRestBaseUrl] converts it back for REST.
 */
internal data class CollapsedEndpoints(
    val boomioBaseUrl: String,
    val bsmBaseUrl: String,
    val companionBaseUrl: String,
)

/**
 * Turns a typed server origin into the three boomio endpoints, or null when it is not an origin.
 *
 * ⚠️ **The port and the scheme are carried, the path is not.** A box reached on a LAN port or
 * over plain HTTP must keep both, or the app dials the wrong place; a path on the input is a
 * route *into* the server, not part of its origin, and re-appending it would double it.
 */
internal fun collapsedEndpointsFor(originRaw: String): CollapsedEndpoints? {
    val trimmed = originRaw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val scheme = withScheme.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val authority = withScheme.substringAfter("://").substringBefore('/').trim()
    if (authority.isBlank() || '@' in authority) return null
    val wsScheme = if (scheme == "https") "wss" else "ws"
    return CollapsedEndpoints(
        boomioBaseUrl = "$scheme://$authority/bsf",
        bsmBaseUrl = "$scheme://$authority/bsm",
        companionBaseUrl = "$wsScheme://$authority/bsc",
    )
}

/**
 * A server chosen at runtime from the first-run screen, rather than baked at build time.
 *
 * The Windows build takes `BOOMIO_BASE_URL` as a build input, which means pointing a fresh
 * install at a different box used to need a rebuild. A first-run address removes that: it is
 * persisted next to the app's other settings and re-applied here at startup, *over* whatever the
 * build baked in.
 *
 * ⚠️ **This overrides the build, it does not merge with it.** A half-applied server (bsf from
 * one box, bsc from another) fails in ways that look like unrelated bugs — casting resolves
 * against one deployment while pairing dials another — so the three values are always written
 * together from one origin.
 */
internal object BoomioServerOverride {
    private const val STORE = "boomio_server_override"
    private const val ORIGIN_KEY = "origin"

    private val store by lazy { DesktopStorage.store(STORE) }

    /** Applies a previously chosen server over the build's endpoints. Call once at startup. */
    fun load() {
        val origin = store.getString(ORIGIN_KEY)?.trim().orEmpty()
        if (origin.isBlank()) return
        applyToConfig(origin)
    }

    /** Points every boomio plane at [origin] and remembers the choice for the next run. */
    fun apply(origin: String) {
        val endpoints = collapsedEndpointsFor(origin) ?: return
        val normalizedOrigin = endpoints.boomioBaseUrl.removeSuffix("/bsf")
        store.putString(ORIGIN_KEY, normalizedOrigin)
        applyToConfig(normalizedOrigin)
    }

    fun clear() {
        store.remove(ORIGIN_KEY)
    }

    /** True when a runtime choice is in force rather than the build-time endpoints. */
    fun isActive(): Boolean = store.getString(ORIGIN_KEY)?.isNotBlank() == true

    private fun applyToConfig(origin: String) {
        val endpoints = collapsedEndpointsFor(origin) ?: return
        BoomioConfig.boomioBaseUrl = endpoints.boomioBaseUrl
        BoomioConfig.bsmBaseUrl = endpoints.bsmBaseUrl
        BoomioConfig.companionBaseUrl = endpoints.companionBaseUrl
    }
}
