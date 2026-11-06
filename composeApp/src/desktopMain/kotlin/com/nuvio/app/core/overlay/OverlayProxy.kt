package com.nuvio.app.core.overlay

import okhttp3.Authenticator
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Points an OkHttp client at the loopback relay — the desktop half of mobile's `withOverlayProxy()`.
 *
 * The relay ([OverlayRelay]) listens on loopback and speaks HTTP `CONNECT`, carrying an allow-set
 * host through the WireGuard tunnel and dialling everything else directly. Nothing here decides
 * *whether* a host is carried: that is `relayRouteFor`'s job inside the relay, and it fails open.
 * This is only the routing table OkHttp consults before it opens a connection.
 *
 * ── Why a proxy and not a DNS override ──────────────────────────────────────
 * The same reason the relay exists at all: the media plane is libmpv, which resolves inside
 * libcurl's own `getaddrinfo`, and nothing on this side can intercept it. A CONNECT proxy is the
 * only seam that reaches *every* client, including the ones with their own resolver. See
 * [OverlayRelay]'s doc for the full argument.
 *
 * ── ⚠️ Two rules that are load-bearing, both copied from the mobile seam ─────
 *  - **[OverlayRelay.proxy] is read on every `select`, never captured.** The relay comes up
 *    asynchronously and clients are built before it does. OkHttp asks the selector once per
 *    *route*, not once per client and not once at construction, so a client built before the
 *    relay is up is picked up by its next new connection.
 *  - **When the relay is not up, the answer is `NO_PROXY`, not a failure.** That is the
 *    fail-open invariant: a client that cannot reach the relay connects the way it would have
 *    connected before the overlay existed, and the ladder converging silently changes the answer.
 */
internal object OverlayProxy {

    private const val LOOPBACK = "127.0.0.1"

    /** The relay as OkHttp's proxy selector sees it. */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val endpoint = OverlayRelay.proxy ?: return listOf(Proxy.NO_PROXY)
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, endpoint.port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    /**
     * The relay's credential, in the form its protocol accepts.
     *
     * `Bearer <secret>` is the in-app form (the relay also accepts the `Basic` form libmpv
     * produces from a proxy URL); see [isProxyAuthorized]. Read per challenge rather than captured,
     * for the same reason the port is.
     */
    val authenticator: Authenticator = Authenticator { _, response ->
        val endpoint = OverlayRelay.proxy ?: return@Authenticator null
        // One retry only: if the relay refused a correctly-credentialed request, re-sending the
        // same header is a loop, and OkHttp would follow it forever.
        if (response.request.header(PROXY_AUTHORIZATION) != null) return@Authenticator null
        response.request.newBuilder()
            .header(PROXY_AUTHORIZATION, "Bearer ${endpoint.secret}")
            .build()
    }

    /** A builder wired to the relay. Every Boomio-facing OkHttp client should start here. */
    fun withOverlayProxy(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .proxySelector(selector)
        .proxyAuthenticator(authenticator)

    fun withOverlayProxy(): OkHttpClient.Builder = withOverlayProxy(OkHttpClient.Builder())

    /** The canonical spelling. The relay lowercases before comparing. */
    private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
}
