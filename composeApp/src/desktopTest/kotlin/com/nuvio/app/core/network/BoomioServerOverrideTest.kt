package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The three collapsed-edge endpoints a typed server address implies.
 *
 * These are the values the first-run screen writes into `BoomioConfig`, so getting the scheme or
 * the port wrong here sends streaming, pairing and telemetry to different places at once.
 */
class BoomioServerOverrideTest {

    @Test
    fun `a bare host becomes the collapsed https edge`() {
        assertEquals(
            CollapsedEndpoints(
                boomioBaseUrl = "https://boomiopi.duckdns.org/bsf",
                bsmBaseUrl = "https://boomiopi.duckdns.org/bsm",
                companionBaseUrl = "wss://boomiopi.duckdns.org/bsc",
            ),
            collapsedEndpointsFor("boomiopi.duckdns.org"),
        )
    }

    @Test
    fun `an explicit http origin keeps its scheme and port`() {
        assertEquals(
            CollapsedEndpoints(
                boomioBaseUrl = "http://192.168.1.65:8099/bsf",
                bsmBaseUrl = "http://192.168.1.65:8099/bsm",
                companionBaseUrl = "ws://192.168.1.65:8099/bsc",
            ),
            collapsedEndpointsFor("http://192.168.1.65:8099"),
        )
    }

    @Test
    fun `a path on the input is not carried into the endpoints`() {
        assertEquals(
            collapsedEndpointsFor("https://boomiopi.duckdns.org"),
            collapsedEndpointsFor("https://boomiopi.duckdns.org/bsf/"),
        )
    }

    @Test
    fun `a value that is not an origin is refused`() {
        assertNull(collapsedEndpointsFor(""))
        assertNull(collapsedEndpointsFor("   "))
        assertNull(collapsedEndpointsFor("ftp://host"))
        assertNull(collapsedEndpointsFor("https://user@host"))
    }
}
