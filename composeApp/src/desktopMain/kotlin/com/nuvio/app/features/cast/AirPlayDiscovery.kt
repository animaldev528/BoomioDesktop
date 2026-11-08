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

    /**
     * At most this many interfaces are browsed, most-likely-real first. A host can
     * present a dozen virtual adapters and every one of them costs a socket, a
     * thread and a bind that may be slow; the LAN is never the twelfth.
     */
    private const val MAX_INTERFACES = 8

    /** Names that are, in practice, virtual. Only used to order, never to exclude. */
    private val VIRTUAL = Regex(
        "^(br-|docker|veth|vnic|vEthernet|utun|tun|tap|lo\\b|VMware|VirtualBox|Hyper-V|Bluetooth|Local Area Connection\\* )",
        RegexOption.IGNORE_CASE,
    )

    /** One bounded scan, merged by address. Never throws. */
    suspend fun discover(timeoutMillis: Long): List<CastDevice> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, CastDevice>()
        val browsers = mutableListOf<JmDNS>()
        try {
            val candidates = runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            }.getOrElse { emptyList() }
                .filter { nif ->
                    // Any of these can throw per adapter, not just return false.
                    runCatching { nif.isUp && !nif.isLoopback && nif.supportsMulticast() }
                        .getOrDefault(false)
                }
                .sortedBy { nif -> if (VIRTUAL.containsMatchIn(nif.name.orEmpty())) 1 else 0 }
                .take(MAX_INTERFACES)
            for (nif in candidates) {
                val address = runCatching { firstIpv4(nif.getInetAddresses().toList()) }
                    .getOrNull()
                    // 169.254.x.x is what an adapter says when it has no lease:
                    // there is nothing on the other end to find.
                    ?.takeUnless { it.isLinkLocalAddress }
                    ?: continue
                val browser = runCatching { JmDNS.create(address, "${nif.name}-${address.hostAddress}") }
                    .getOrElse { t ->
                        // A VPN or a filter driver can refuse the multicast bind.
                        // That interface has no receivers to offer; the rest still do.
                        // ⚠️ Throwable, not Exception: JmDNS fails at class-load time
                        // with a LinkageError when a dependency is missing, and that
                        // must cost one interface, never the panel.
                        log.d { "mDNS unavailable on ${nif.name}: ${t.message}" }
                        continue
                    }
                browsers += browser
                runCatching { browser.addServiceListener(SERVICE, listenerFor(browser, found)) }
            }
            if (browsers.isEmpty()) return@withContext emptyList()
            // Every interface is browsed at once, so this is the whole scan window.
            delay(timeoutMillis)
        } catch (t: Throwable) {
            log.w { "mDNS browse failed: ${t.message}" }
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
