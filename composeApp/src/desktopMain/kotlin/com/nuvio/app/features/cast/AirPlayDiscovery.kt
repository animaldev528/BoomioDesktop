package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * Finds AirPlay receivers over mDNS.
 *
 * This is not an optimisation over the SSDP scan; it is the only path that works
 * for an Apple TV. An Apple TV advertises `_airplay._tcp` and answers no SSDP
 * M-SEARCH at all, so a scan built on SSDP alone reports "no screens" while the
 * television is sitting on the same switch. Measured against an Apple TV 4K
 * (`AppleTV6,2`, tvOS 26.6.1): SSDP silent, mDNS resolves name, address, port and
 * the whole TXT record.
 *
 * ⚠️ **One browser per interface, not one browser.** A single `JmDNS.create()`
 * binds to whichever interface it finds first, and on a real machine that is
 * usually not the LAN: the build box has eight docker bridges and the default pick
 * resolved nothing at all, while a browser bound to the LAN interface resolved the
 * Apple TV in under a second. A Windows desktop has the same hazard in Hyper-V,
 * WSL and VPN adapters. So every usable IPv4 interface gets its own browser and
 * the answers are merged.
 *
 * The TXT record is kept, not just the address: `features`, `flags`, `model` and
 * `deviceid` are what the sender uses to decide whether it can play at all,
 * whether it must ask for a PIN, and whether it needs tvOS's timing workaround —
 * `model` is how an Apple TV is told apart from a Mac, which need opposite settings.
 *
 * A resolve that carries no IPv4 address is dropped rather than recorded: the same
 * service is reported twice, once before its A record is known. Neither a socket
 * that cannot be opened nor an interface that answers nothing fails the scan.
 */
internal object AirPlayDiscovery {

    private const val SERVICE = "_airplay._tcp.local."

    /** Apple's AirPlay port. The TXT record carries no port; the SRV record does — this is the fallback. */
    private const val DEFAULT_PORT = 7000

    private val log = Logger.withTag("AirPlayDiscovery")

    /** One bounded scan, merged by address. Never throws. */
    suspend fun discover(timeoutMillis: Long): List<CastDevice> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, CastDevice>()
        val browsers = mutableListOf<JmDNS>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback || !nif.supportsMulticast()) continue
                val address = firstIpv4(nif.getInetAddresses().toList()) ?: continue
                val browser = try {
                    JmDNS.create(address, "${nif.name}-${address.hostAddress}")
                } catch (e: Exception) {
                    // A VPN or a virtual adapter can refuse the multicast bind. That
                    // interface has no receivers to offer; the rest still do.
                    log.d { "mDNS unavailable on ${nif.name}: ${e.message}" }
                    continue
                }
                browsers += browser
                browser.addServiceListener(SERVICE, listenerFor(browser, found))
            }
            if (browsers.isEmpty()) return@withContext emptyList()
            // Every interface is browsed at once, so this is the whole scan window.
            delay(timeoutMillis)
        } catch (e: Exception) {
            log.d { "mDNS browse failed: ${e.message}" }
        } finally {
            for (browser in browsers) runCatching { browser.close() }
        }
        found.values.sortedBy { it.name }
    }

    private fun listenerFor(browser: JmDNS, found: MutableMap<String, CastDevice>) =
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // A browse answer names a service; its address, port and TXT record
                // only come with a resolve.
                runCatching { browser.requestServiceInfo(event.type, event.name, true, 2000) }
            }

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                // IPv4 only: the sender's sockets are opened over IPv4 and the
                // address is what a stream is bound to.
                val ip = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                val properties = buildMap {
                    val names = info.propertyNames ?: return@buildMap
                    while (names.hasMoreElements()) {
                        val key = names.nextElement() ?: continue
                        put(key, runCatching { info.getPropertyString(key) }.getOrNull().orEmpty())
                    }
                }
                val name = info.name
                    ?.removeSuffix(".$SERVICE")
                    ?.takeIf { it.isNotBlank() }
                    ?: properties["model"]
                    ?: ip
                found[ip] = CastDevice(
                    protocol = CastProtocol.AIRPLAY,
                    ip = ip,
                    name = name,
                    port = info.port.takeIf { it > 0 } ?: DEFAULT_PORT,
                    model = properties["model"],
                    // The raw TXT record rides along: the sender reads
                    // `features`/`flags`/`deviceid` from it, and re-reading the
                    // receiver at cast time would cost a second connection.
                    raw = properties,
                )
            }
        }

    private fun firstIpv4(addresses: List<InetAddress>): InetAddress? =
        addresses.firstOrNull { it is Inet4Address }
}
