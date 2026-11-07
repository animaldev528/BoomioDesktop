package com.nuvio.app.core.overlay

import com.nuvio.app.features.boomio.BoomioConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "OverlayEndpointDiscovery"

/**
 * Decides where the overlay server is, and publishes the answer as [OverlayEndpointState].
 *
 * ⚠️ **The desktop ladder has one rung where the mobile one has three, and that is a scope
 * decision rather than a simplification.** On Android the rungs are:
 *
 *  - **rung 1, mDNS** — a browse for `_boomio-overlay._udp` on the server's own network;
 *  - **rung 2, the discovery record** — a DNS `TXT` lookup, read through
 *    `android.net.DnsResolver`, which does not exist below API 29 and has no desktop
 *    equivalent;
 *  - **rung 3, a configured address** — whatever [BoomioConfig] holds.
 *
 * The first two need `NsdManager` and `DnsResolver`; porting them means adding JmDNS and a
 * hand-rolled TXT query, which is a project of its own. Rung 3 is the one the desktop client
 * actually runs on: enrollment writes [BoomioConfig.overlayEndpoint] and
 * [BoomioConfig.overlayServerPubKey], so the assignment *is* the endpoint. A desktop device
 * that has enrolled therefore has a complete, usable tuple with no discovery at all — which is
 * the case the other two rungs exist to rescue on a phone that has never enrolled.
 *
 * ⚠️ **The consequence is real and is not hidden in the UI.** A desktop install that has not
 * enrolled reports [OverlayEndpointStatus.NeedsManual] naming the address it is missing,
 * rather than silently sitting in `Searching` as though a browse were still running.
 *
 * ⚠️ **But rung 3 is not the single address this comment used to describe, and that was a bug.**
 * Enrollment assigns three: the server's own `endpoint`, plus the `lan_endpoint`/`wan_endpoint`
 * that mirror the `lan=`/`wan=` tuple its discovery record teaches. Only the first was read, and
 * on a deployed box that is the *public* literal — which a machine on the same LAN as the server
 * usually cannot reach, because consumer routers do not hairpin NAT. A desktop at home therefore
 * failed to reach a server inches away, with the working address sitting in the reply it had just
 * received. All three are now carried in order and the gate picks the plane; see
 * [rung3Candidates].
 *
 * ── What is preserved verbatim ───────────────────────────────────────────────
 * The gate, the preference-not-veto rule, the miss retry, and the write-through to
 * [BoomioConfig] are the mobile ladder's own decisions, and they are kept because they were
 * each learned from a specific field failure — see the comments at each. Only the rungs and
 * the two Android observers are absent.
 */
internal object OverlayEndpointDiscovery {

    /** The WireGuard port this deployment uses, when an address names none. */
    private const val DEFAULT_WG_PORT = 51820

    /** How long a resolved name gets before the ladder gives up on it. */
    private const val MANUAL_RESOLVE_BUDGET_MS = 1_500L

    /**
     * The port the reachability gate dials.
     *
     * ⚠️ Not the WireGuard port: this asks whether the **HTTP plane** answers, which is what
     * makes an endpoint usable for the companion and media traffic the relay carries. A server
     * whose UDP is up and whose edge is down cannot serve the client.
     */
    private const val EDGE_PORT = 443

    /**
     * How long a successful answer is trusted before a refresh re-walks.
     *
     * Mobile's cadence is driven by foreground events; desktop has no lifecycle, so this TTL is
     * only ever consulted by [shouldRefresh]'s callers. The number is kept from mobile so the
     * two clients age an answer at the same rate.
     */
    private const val RESULT_TTL_MS = 5 * 60 * 1000L

    /** How long to wait after a walk that found nothing before walking again. */
    private const val MISS_RETRY_MS = 2_000L

    /**
     * How many miss retries one miss may chain, total.
     *
     * Bounded on purpose: a machine on a network with no boomio server on it must not spin.
     * Mobile's three attempts cover the roam case; desktop's single rung is cheaper, and the
     * desktop assignment does not change underneath it, so the same bound is more than enough.
     */
    private const val MISS_RETRY_ATTEMPTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ladderMutex = Mutex()

    private var initialized = false

    /** When the last **successful** walk finished. See [RESULT_TTL_MS]. */
    @Volatile
    private var lastResolvedAtMs = 0L

    /**
     * A miss retry is already scheduled, so further misses must not chain another.
     *
     * ⚠️ Not tidiness: without it, a refresh arriving during a retry chain starts a second one,
     * and a machine calling `refreshAsync` on a timer would walk continuously.
     */
    @Volatile
    private var missRetryPending = false

    /** The endpoint last accepted, so a re-run that finds nothing does not erase a good answer. */
    @Volatile
    private var current: OverlayEndpoint? = null

    fun initialize() {
        // ⚠️ Called **above** the once-per-process guard, and the ordering is deliberate — the
        // same reasoning as mobile's. The guard makes everything after it a no-op on a second
        // call, which is right for observers, but the UI's uplink is not an observer. Assigning
        // a captured lambda twice costs nothing; failing to assign it once leaves a ladder with
        // no way to feed it rung 3.
        wireManualEntry()
        if (initialized) return
        initialized = true
    }

    /**
     * Points the UI's "use this address" control at this ladder's rung 3.
     *
     * ⚠️ **The key is deliberately not taken from the UI.** [offerManual] falls back to the
     * server public key already held in [BoomioConfig], which enrollment wrote on any device
     * that has one; a person cannot read a base64 X25519 key off anything they own.
     */
    internal fun wireManualEntry() {
        OverlayEndpointState.manualSubmit = { raw -> offerManual(raw, null) }
    }

    /** Fire-and-forget [resolve] for callers that cannot suspend. */
    fun refreshAsync() {
        initialize()
        scope.launch { resolve() }
    }

    /**
     * Re-walks only when the held answer has aged past [RESULT_TTL_MS], or when there is none.
     *
     * The desktop equivalent of mobile's foreground cadence: a caller that has a natural
     * "something just happened" moment — a window raised, the enrollment applying a fresh
     * assignment — asks here, and gets the walk only when it would learn something.
     */
    fun refreshIfStale() {
        initialize()
        if (current != null && elapsedMs() - lastResolvedAtMs < RESULT_TTL_MS) return
        refreshAsync()
    }

    /** The endpoint currently in force, or null when the ladder has not produced one. */
    fun current(): OverlayEndpoint? = current

    /**
     * Walks the rungs and publishes the first endpoint that passes the gate.
     *
     * Safe to call repeatedly; concurrent calls serialise rather than racing each other.
     */
    suspend fun resolve(): OverlayEndpointStatus = ladderMutex.withLock {
        initialize()

        OverlayEndpointState.update(OverlayEndpointStatus.Searching)

        val candidates = rung3Candidates()

        // ⚠️ The gate is a blocking connect, so it is pinned to IO explicitly. `resolve()` is
        // reachable from a settings screen, and an un-dispatched `Socket.connect` there would
        // freeze the UI. Inheriting the caller's dispatcher would make that latent.
        val working = withContext(Dispatchers.IO) {
            candidates.firstOrNull { isReachable(it.host, EDGE_PORT) }
        }
        working?.let { return@withLock accept(it) }

        if (candidates.isEmpty()) {
            // A walk that found nothing is the one that must not be sticky: the assignment may
            // land a second after this ran (enrollment is asynchronous), and the retry below is
            // what turns that race into a delay rather than into a dead subsystem.
            scheduleMissRetry()
            return@withLock publish(
                OverlayEndpointStatus.NeedsManual(
                    "No boomio server is configured. Enrol this device, or enter the server's " +
                        "address to continue.",
                ),
            )
        }

        // ⚠️ **Nothing answered the gate, so a candidate is taken anyway** — and this is the one
        // place the ladder deliberately overrules its own probe. Mobile learned this the hard
        // way: a liveness probe once timed out on a LAN the app's own client was reaching in
        // 2.6 ms, and because the candidate was *discarded* rather than merely deprioritised,
        // discovery went silently dead everywhere. The gate's job is to choose between
        // candidates; it has no business throwing the last one away.
        //
        // ⚠️ **It is the *last*, not the first, and the candidates are ordered LAN-first.** With
        // one candidate the two are the same thing and this is the rule mobile wrote. With the
        // per-plane tuple it is not: the LAN candidate failing its probe is the single most
        // informative datum available — it usually means this machine is *not* on the server's
        // network — so falling back to it would aim a tunnel at an address the probe just
        // rejected on the one hypothesis that probe is best at testing. The tail of the list is
        // the server's own answer, which is where this ladder stood before the planes existed.
        val fallback = candidates.last()
        OverlayLog.w(TAG, "No candidate answered the gate on $EDGE_PORT; taking ${fallback.authority} anyway")
        accept(fallback)
    }

    // ---------------------------------------------------------------------------------------
    // Rung 3
    // ---------------------------------------------------------------------------------------

    /**
     * The addresses this device has been told about, in dial order: **LAN plane first**, then the
     * WAN plane, then the server's own `endpoint`.
     *
     * ⚠️ **These are candidate *planes*, not alternative endpoints, and the order is the whole
     * fix.** `endpoint` in a real box's record is the public literal, and a machine on the
     * server's own network generally cannot reach it — consumer routers do not hairpin NAT — so
     * dialling it produced a tunnel that failed a handshake against a server four milliseconds
     * away. The server publishes the LAN plane beside it (`lan_endpoint`, the same value its
     * discovery record carries as `lan=`), and this used to drop it on the floor.
     *
     * The ordering is mobile's, arrived at without a TXT query: rung 2 on Android reads `lan=`
     * and `wan=` off the record and prefers `lan` on the server's network. The enroll reply
     * carries the same two literals, so the same decision is available here for free — the
     * record's *policy* without porting a DNS client to get the record's *transport*.
     *
     * ⚠️ A *literal* none the less goes through no resolver beyond this call's own: rung 3 is the
     * no-LAN-DNS case, and each candidate is resolved here, once, to the literal the tunnel is
     * handed verbatim. Duplicates are dropped **after** resolution, so a v1 record whose
     * `endpoint` is `boomio.duckdns.org:51820` (whose `A` record is the LAN literal) collapses
     * onto the LAN candidate rather than being dialled twice.
     *
     * Deliberately **does not gate** — the gate is [resolve]'s, applied across candidates so it
     * can choose between them.
     */
    private suspend fun rung3Candidates(): List<OverlayEndpoint> {
        val key = validServerKeyOrNull(BoomioConfig.overlayServerPubKey)
        if (key == null) {
            if (BoomioConfig.overlayEndpoint.trim().isNotEmpty()) {
                OverlayLog.d(TAG, "Rung 3: an endpoint is configured but no usable server key is")
            }
            return emptyList()
        }

        val seen = mutableSetOf<String>()
        val out = ArrayList<OverlayEndpoint>(3)
        // Order is the policy: LAN, WAN, then whatever the server called "the endpoint".
        for (raw in listOf(
            BoomioConfig.overlayLanEndpoint,
            BoomioConfig.overlayWanEndpoint,
            BoomioConfig.overlayEndpoint,
        )) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val (namedHost, port) = parseEndpointAuthority(text, DEFAULT_WG_PORT) ?: continue
            val literal = resolveHost(namedHost, MANUAL_RESOLVE_BUDGET_MS)?.hostAddress ?: continue
            if (!seen.add("$literal:$port")) continue
            out += OverlayEndpoint(
                host = literal,
                port = port,
                serverPublicKeyBase64 = key,
                source = OverlayEndpointSource.ASSIGNMENT,
            )
        }
        return out
    }

    private fun accept(endpoint: OverlayEndpoint): OverlayEndpointStatus {
        current = endpoint
        // ⚠️ **Written through to `BoomioConfig`, which is the documented end state** — "in the
        // end state this is *learned*, not configured". The relay and the bring-up both read
        // these fields, so a ladder that only published a flow would leave them on the build-time
        // value and the tunnel would dial the wrong address while the status said otherwise.
        BoomioConfig.overlayEndpoint = endpoint.authority
        endpoint.serverPublicKeyBase64?.let { BoomioConfig.overlayServerPubKey = it }

        OverlayLog.i(TAG, "Endpoint accepted from ${endpoint.source}: ${endpoint.authority}")

        // ⚠️ **The TTL is armed here, on success only.** Stamping it on entry would make the one
        // result that needs a retry — nothing found — the one that bought five minutes of silence.
        lastResolvedAtMs = elapsedMs()
        return publish(OverlayEndpointStatus.Found(endpoint))
    }

    /**
     * Walks the ladder again after a bounded delay, because a walk that found nothing is the one
     * outcome that must not be sticky.
     *
     * ⚠️ **[missRetryPending] is what keeps this from forking.** `resolve()` calls this function
     * again on a miss, so the latch makes that call a no-op and the *loop* owns the attempt count.
     */
    private fun scheduleMissRetry() {
        if (missRetryPending) return
        missRetryPending = true
        scope.launch {
            try {
                var left = MISS_RETRY_ATTEMPTS
                while (left > 0) {
                    delay(MISS_RETRY_MS)
                    // A later walk may have succeeded while this one waited, in which case there
                    // is nothing left to chase.
                    if (current != null) return@launch
                    val status = resolve()
                    if (status is OverlayEndpointStatus.Found) return@launch
                    left--
                }
            } finally {
                missRetryPending = false
                // Put the staleness clock back rather than leaving it at zero: otherwise every
                // [refreshIfStale] would pay a full walk on a serverless machine, forever.
                if (current == null) lastResolvedAtMs = elapsedMs()
            }
        }
    }

    private fun publish(status: OverlayEndpointStatus): OverlayEndpointStatus {
        OverlayEndpointState.update(status)
        return status
    }

    // ---------------------------------------------------------------------------------------
    // Rung 3's input
    // ---------------------------------------------------------------------------------------

    /**
     * Records a user-supplied endpoint and re-runs the ladder.
     *
     * Returns the resulting status so the caller can render it directly: a typed address that
     * the **gate** cannot reach still becomes `Found`, because preference-not-veto applies to a
     * human's answer too — they may know something the probe cannot see. Only a value that is
     * not an endpoint at all is rejected, and it returns `NeedsManual` with the reason rather
     * than throwing.
     */
    suspend fun offerManual(rawAuthority: String, serverPublicKeyBase64: String?): OverlayEndpointStatus {
        val authority = parseEndpointAuthority(rawAuthority, DEFAULT_WG_PORT)
        if (authority == null) {
            return publish(
                OverlayEndpointStatus.NeedsManual(
                    "\"${rawAuthority.trim()}\" is not an address — expected a host, or host:port.",
                ),
            )
        }
        val key = validServerKeyOrNull(serverPublicKeyBase64)
            ?: validServerKeyOrNull(BoomioConfig.overlayServerPubKey)
        if (key == null) {
            // The server's key is public and is published by both channels; it is the one field
            // a user cannot invent, so its absence is named rather than guessed at.
            return publish(
                OverlayEndpointStatus.NeedsManual(
                    "The server's public key is needed as well — it is the value enrollment " +
                        "assigns, and the one a person cannot derive.",
                ),
            )
        }
        BoomioConfig.overlayEndpoint = rawAuthority.trim()
        // ⚠️ **A human's answer replaces the whole candidate set, planes included.** Leaving them
        // set would put the LAN plane *ahead* of the address they just typed and let the gate
        // hand them back the one they were trying to get away from — the one thing a manual
        // entry exists to override.
        BoomioConfig.overlayLanEndpoint = ""
        BoomioConfig.overlayWanEndpoint = ""
        BoomioConfig.overlayServerPubKey = key
        return resolve()
    }

    /** Monotonic milliseconds. `SystemClock.elapsedRealtime()` on Android; wall-clock never. */
    private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L
}

// -------------------------------------------------------------------------------------------
// Pure helpers, ported verbatim from mobile
// -------------------------------------------------------------------------------------------

/**
 * `host:port` → its parts, or null if [raw] is not an address at all.
 *
 * Deliberately forgiving about the shapes a person actually pastes — a scheme, a trailing slash,
 * a path, brackets round an IPv6 literal — and unforgiving about anything that is not an
 * address. A default is supplied for the port because "the server's address" is how the question
 * will be asked, and the WireGuard port is a constant of this deployment.
 *
 * ⚠️ **Two or more colons and no brackets is read as an address, not as `host:port`.** `::1:51820`
 * is a valid IPv6 address *and* a plausible IPv4-style address-plus-port, and splitting on that
 * colon would send the tunnel to a host the user never named. Taking it whole and leaving the
 * port at the default is the reading that cannot be wrong about *which host*.
 *
 * **A saved value the ladder itself wrote reads back as rung 3.** [OverlayEndpointDiscovery.accept]
 * publishes a discovered endpoint into `BoomioConfig`, which is what rung 3 reads, so the next run
 * presents last run's answer as a manual one. That is accurate in the only sense that matters — it
 * *is* the configured address now.
 */
internal fun parseEndpointAuthority(raw: String, defaultPort: Int): Pair<String, Int>? {
    var text = raw.trim()
    if (text.isEmpty()) return null

    // A pasted URL is the common case, and the scheme is noise here.
    val scheme = text.indexOf("://")
    if (scheme > 0) text = text.substring(scheme + 3)
    text = text.substringBefore('/').trim()
    if (text.isEmpty()) return null

    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close <= 1) return null
        val host = text.substring(1, close)
        val rest = text.substring(close + 1)
        val port = if (rest.startsWith(":")) rest.substring(1).toPortOrNull() ?: return null else defaultPort
        return host to port
    }

    val colons = text.count { it == ':' }
    return when (colons) {
        0 -> text to defaultPort
        1 -> {
            val host = text.substringBefore(':')
            val port = text.substringAfter(':').toPortOrNull() ?: return null
            if (host.isEmpty()) null else host to port
        }
        // Two or more colons and no brackets: an unbracketed IPv6 literal. Port stays default.
        else -> text to defaultPort
    }
}

private fun String.toPortOrNull(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }

/**
 * A literal address for [host], or null.
 *
 * Blocks; callers run it off the main thread and inside a timeout. A value that is already a
 * literal returns without a resolver round trip.
 */
internal suspend fun resolveHost(host: String, budgetMs: Long): InetAddress? =
    withTimeoutOrNull(budgetMs) {
        withContext(Dispatchers.IO) {
            runCatching { InetAddress.getByName(host) }
                .onFailure { OverlayLog.d("OverlayEndpointDiscovery", "'$host' did not resolve: ${it.message}") }
                .getOrNull()
        }
    }

/**
 * True when a TCP connect to [host]:[port] completes inside [GATE_TIMEOUT_MS].
 *
 * The gate answers "is this address usable for the HTTP plane", which is why it dials 443 and
 * not the WireGuard port: a server whose UDP is up and whose edge is down cannot serve a client.
 */
internal fun isReachable(host: String, port: Int): Boolean {
    val startedAt = System.nanoTime()
    return runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), GATE_TIMEOUT_MS)
            true
        }
    }.onFailure {
        OverlayLog.w(
            "OverlayEndpointDiscovery",
            "Gate probe to $host:$port failed after ${(System.nanoTime() - startedAt) / 1_000_000}ms",
            it,
        )
    }.getOrDefault(false)
}

/** Bound on the gate's TCP connect. Short: it is a probe, not a request. File scope, because [isReachable] is top-level. */
private const val GATE_TIMEOUT_MS = 900

/** An IPv4 literal: four dotted decimal octets. Deliberately not a range check. */
private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

/** An IPv6 literal is anything hex-and-colons that actually contains a colon. */
private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:]+$")

/**
 * A literal address, or null — **and never a resolution.**
 *
 * ⚠️ **This is the difference between a parser and a resolver, and it is a security boundary.**
 * [InetAddress.getByName] happily accepts a *hostname* and will consult DNS for it, so feeding it
 * an unvalidated body from a third-party service would let that service aim the resolver at a
 * name of its choosing. The regexes above admit only literals, and the colon requirement keeps a
 * bare hex string from being read as a hostname.
 */
internal fun literalOrNull(text: String): InetAddress? {
    val looksLikeV4 = IPV4_LITERAL.matches(text)
    val looksLikeV6 = IPV6_LITERAL.matches(text) && text.contains(':')
    if (!looksLikeV4 && !looksLikeV6) return null
    return runCatching { InetAddress.getByName(text) }.getOrNull()
}

/**
 * A WireGuard public key the tunnel could actually use, or null.
 *
 * ⚠️ **Validated here, at the edge, rather than at `IpcSet`.** A key that is the wrong length is
 * the classic silent failure of this whole subsystem: a truncated paste decodes cleanly to the
 * wrong bytes, the handshake never completes, and the only symptom is a tunnel that does not come
 * up. Rejecting it here means the ladder keeps walking instead of stopping on a candidate that
 * cannot work — [decodeWireGuardKey] owns the 32-byte rule for the whole package.
 */
internal fun validServerKeyOrNull(value: String?): String? {
    val candidate = value?.trim().orEmpty()
    if (candidate.isEmpty()) return null
    return candidate.takeIf { decodeWireGuardKey(it) != null }
}
