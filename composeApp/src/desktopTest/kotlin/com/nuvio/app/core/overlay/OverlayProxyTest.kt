package com.nuvio.app.core.overlay

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The fail-open half of the proxy seam, which is the only half a host test can reach.
 *
 * ⚠️ **"Inert is not broken" is the invariant.** Every client in the app now starts with
 * [OverlayProxy.withOverlayProxy], including clients built before pairing has happened and clients
 * that only ever talk to the public internet. If a client's request failed because there was no
 * relay, attaching this seam would have broken a working app; instead the selector answers
 * `NO_PROXY` and the connection is the one the client would have made anyway.
 *
 * The other direction — a running relay becoming a proxy — needs a live [OverlayRelay] and is
 * covered by `OverlayRelayTest` and the relay's own suite.
 */
class OverlayProxyTest {

    @Test
    fun `with no relay the selector answers NO_PROXY`() {
        assertEquals(
            listOf(Proxy.NO_PROXY),
            OverlayProxy.selector.select(URI("https://tmdb.tracemonkey.org/addon/manifest.json")),
        )
    }

    @Test
    fun `with no relay the authenticator declines to answer a challenge`() {
        // Null is what tells OkHttp "give up on this route" — it then fails the way it would have
        // failed without a proxy at all, rather than retrying a challenge forever.
        assertNull(OverlayProxy.authenticator.authenticate(null, challenge()))
    }

    @Test
    fun `the builder carries the seam`() {
        val client = OverlayProxy.withOverlayProxy(OkHttpClient.Builder()).build()
        assertSame(OverlayProxy.selector, client.proxySelector)
        assertSame(OverlayProxy.authenticator, client.proxyAuthenticator)
    }

    private fun challenge(): Response = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(407)
        .message("Proxy Authentication Required")
        .build()
}
