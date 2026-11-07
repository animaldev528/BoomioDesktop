package com.nuvio.app.core.overlay

import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.companionRestBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "OverlayEnrollment"

/**
 * The address the **server assigned this device**, and everything needed to reach it.
 *
 * ⚠️ [address] is the client's *own* address inside the overlay, and it is the field that
 * makes enrollment worth having. [BoomioConfig.overlayLocalCidr]'s shipped default is
 * `10.77.0.2/32`, which is right for the first client on an overlay and **wrong for every
 * one after it** — the tunnel comes up, completes a handshake, and then silently drops every
 * return packet, because the server has no route to an address it never agreed to. That
 * failure looks exactly like a broken tunnel and is not one.
 */
internal data class OverlayAssignment(
    val address: String,
    val serverPublicKeyBase64: String,
    val endpoint: String,
    val overlayCidr: String,
    val mtu: Int,
    /**
     * The name the **server** gave this device, and the `CN` its client certificate must carry.
     *
     * It rides along with the address because they are assigned together and are wrong together:
     * re-enrolling is what renames a device, so a cached address and a cached name are either both
     * current or both stale. That is also why [OverlayEnrollment.writeConfig] applies it in the same
     * breath as the address rather than on a schedule of its own.
     *
     * ⚠️ Carried as one value with [address] on purpose, and that is a choice about failure. The
     * edge's mTLS allow-list is keyed on this string, so anything that applies one without the other
     * leaves a device holding a tunnel it may use and a certificate it may not.
     *
     * Defaulted to blank so this stays a source-compatible addition — but note the default is only
     * ever reached by a *constructed* assignment, never by one decoded from the server, which
     * always fills it (`decodeEnrollStatus`). A blank value here means "this server did not name the
     * device", which is the pre-mTLS case and gates certificate registration off.
     */
    val deviceName: String = "",
    /**
     * The same server, from its own network — `lan_endpoint` in the enroll reply, and the `lan=`
     * half of the tuple the discovery record teaches.
     *
     * ⚠️ **Not an alternative for [endpoint]; a plane.** [endpoint] is what the server thinks a
     * client should dial, and on a deployed box that is the public literal, which a machine on
     * the server's own LAN generally cannot reach because consumer routers do not hairpin NAT.
     * The two are both true at once and only the client can tell which one it can reach, so both
     * are carried and the reachability gate chooses.
     *
     * Blank when the server publishes no LAN plane (or an older server that never sent it),
     * which degrades to the pre-split behaviour rather than failing.
     */
    val lanEndpoint: String = "",
    /** The `wan=` half. Usually equal to [endpoint]; carried because a server may pin them apart. */
    val wanEndpoint: String = "",
) {
    /** The `Address =` line's form. */
    val localCidr: String get() = "$address/32"

    /**
     * The **server's** own address on the overlay — the third overlay address, and the one
     * nothing carried until enrollment did. See [overlayServerAddressOf].
     *
     * Derived rather than decoded, so it recomputes per read and stays out of the data class's
     * equality: the declared fields are what the server assigned, and this is a reading of them.
     */
    val serverAddress: String get() = overlayServerAddressOf(overlayCidr)
}

/**
 * The server's address inside [overlayCidr] — the network's first host.
 *
 * ⚠️ **A convention, and its other half is the allocator.** `next_peer_address()` in
 * `overlay/overlay-server-setup.sh` hands out `10.77.0.${n}` starting at **n = 2**, so the
 * first host of the overlay is never assigned to a peer — it is the server, which is why
 * `10.77.0.1` is where the tunnel's DNS responder and its 443 both live. Deriving it is what
 * lets one shipped build work against any deployment: `BOOMIO_OVERLAY_ADDR` was build-time
 * with no fallback, and an app that must be *compiled* knowing where the server sits on a
 * private network cannot be shipped to anyone.
 *
 * ⚠️ **Blank for anything unparseable, and blank leaves the overlay resolver off** — the
 * fail-closed direction. A wrong address here would point every boomio FQDN at a host that
 * never answers, which is indistinguishable from a broken tunnel and much harder to find.
 */
internal fun overlayServerAddressOf(overlayCidr: String): String {
    val prefix = overlayCidr.substringAfter('/', "").trim().toIntOrNull() ?: return ""
    // A /31 and a /32 have no first *host* to speak of, and a /0 has no network boundary.
    if (prefix !in 1..30) return ""

    val octets = overlayCidr.substringBefore('/').trim().split('.')
    if (octets.size != 4) return ""
    val values = octets.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return "" }
    val network = values.fold(0L) { acc, value -> (acc shl 8) or value.toLong() }

    // Masked, so a caller handed a host address rather than the network still gets the same
    // answer: `10.77.0.7/24` and `10.77.0.0/24` both name 10.77.0.1.
    val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val firstHost = ((network and mask) + 1) and 0xFFFFFFFFL
    return listOf(24, 16, 8, 0).joinToString(".") { shift ->
        ((firstHost shr shift) and 0xFF).toString()
    }
}

/** An assignment together with the device key it was issued for. */
internal data class CachedAssignment(
    val assignment: OverlayAssignment,
    val issuedForPublicKeyBase64: String,
)

internal sealed interface OverlayEnrollmentState {
    /** No companion session, so there is nothing to enroll *as*. Not an error. */
    data object Unavailable : OverlayEnrollmentState
    data object Idle : OverlayEnrollmentState
    data object Enrolling : OverlayEnrollmentState

    /**
     * [fromCache] means the server was never contacted — the value is last run's, applied so
     * the tunnel can come up before the network is proven.
     */
    data class Ready(val assignment: OverlayAssignment, val fromCache: Boolean) : OverlayEnrollmentState
    data class Failed(val reason: String) : OverlayEnrollmentState
}

// ── the wire ────────────────────────────────────────────────────────────────────────────────

internal sealed interface EnrollAck {
    data class Pending(val pollAfterMs: Long) : EnrollAck

    /** The server refused the request itself — a bad key, a dead session. Not retryable. */
    data class Rejected(val reason: String) : EnrollAck
}

internal sealed interface EnrollPoll {
    data object Pending : EnrollPoll
    data class Ready(val assignment: OverlayAssignment) : EnrollPoll
    data class Failed(val reason: String) : EnrollPoll
}

/**
 * The two calls `bsc` answers. An interface rather than a concrete client so the policy below
 * can be tested without a server — the same split [OverlayWgTunnel] uses for its binding.
 */
internal interface OverlayEnrollmentApi {
    suspend fun enroll(publicKeyBase64: String): EnrollAck
    suspend fun poll(): EnrollPoll
}

// ── the policy ──────────────────────────────────────────────────────────────────────────────

/**
 * Decides what an enrollment attempt does, with no Android and no network of its own.
 *
 * Kept separate from [OverlayEnrollment] for the reason the rest of this package is split that
 * way: the interesting behaviour is *when* it talks to the server and *whether* it trusts what
 * it already has, and neither question needs a device to answer.
 */
internal class OverlayEnroller(
    private val api: () -> OverlayEnrollmentApi?,
    private val devicePublicKey: () -> String?,
    private val cached: () -> CachedAssignment?,
    private val remember: (CachedAssignment) -> Unit,
    private val apply: suspend (OverlayAssignment) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxPolls: Int = MAX_POLLS,
) {

    /**
     * [useCache] is the difference between "come up now" and "find out whether anything
     * changed". A cached assignment issued for *this* device key is still valid — the address
     * is allocated per public key, and the key has not changed — so returning it costs no
     * network and works with no connectivity at all. It can, however, be stale: the server's
     * endpoint or public key may have rotated since, which is why startup applies the cache
     * and then refreshes rather than choosing one.
     *
     * ⚠️ **The cache is keyed on the device's public key, not on time.** A regenerated keypair
     * means the old assignment is not merely stale but *wrong* — the server is holding a peer
     * for a key this device no longer has — so it must be discarded rather than applied.
     */
    suspend fun enroll(useCache: Boolean = true): OverlayEnrollmentState {
        val client = api() ?: return OverlayEnrollmentState.Unavailable
        val publicKey = devicePublicKey()
            ?: return OverlayEnrollmentState.Failed("This device has no overlay key pair yet.")

        if (useCache) {
            val held = cached()
            if (held != null && held.issuedForPublicKeyBase64 == publicKey) {
                apply(held.assignment)
                return OverlayEnrollmentState.Ready(held.assignment, fromCache = true)
            }
        }

        return when (val ack = client.enroll(publicKey)) {
            is EnrollAck.Rejected -> OverlayEnrollmentState.Failed(ack.reason)
            is EnrollAck.Pending -> awaitAssignment(client, publicKey, ack.pollAfterMs)
        }
    }

    private suspend fun awaitAssignment(
        client: OverlayEnrollmentApi,
        publicKey: String,
        firstDelayMs: Long,
    ): OverlayEnrollmentState {
        // The server allocates on a host timer it does not control the latency of, so this is
        // a poll rather than a long-held request: the request that records the enrollment
        // returns immediately, and the address exists only once the adapter has run.
        repeat(maxPolls) { attempt ->
            sleep(if (attempt == 0) firstDelayMs else POLL_INTERVAL_MS)
            when (val poll = client.poll()) {
                is EnrollPoll.Ready -> {
                    remember(CachedAssignment(poll.assignment, publicKey))
                    apply(poll.assignment)
                    return OverlayEnrollmentState.Ready(poll.assignment, fromCache = false)
                }
                is EnrollPoll.Failed -> return OverlayEnrollmentState.Failed(poll.reason)
                EnrollPoll.Pending -> Unit
            }
        }
        // Deliberately not a `Failed` that stops retrying: nothing is wrong, the adapter is
        // simply slower than this window. The next launch asks again, and the recorded request
        // is still queued on the host.
        return OverlayEnrollmentState.Failed("The server has not assigned an address yet.")
    }

    private companion object {
        /** ~36s at the server's 3s hint. The adapter ticks every 5s. */
        const val MAX_POLLS = 12
        const val POLL_INTERVAL_MS = 3_000L
    }
}

// ── the real transport ──────────────────────────────────────────────────────────────────────

/**
 * ⚠️ **Deliberately NOT [com.nuvio.app.features.boomio.createBoomioHttpClient].** That client
 * carries `withOverlayProxy()` and the overlay DNS hook, which is right for every other call
 * this app makes and fatal for this one: **enrollment is what creates the tunnel**, so a client
 * that routes through the tunnel, or that resolves names to an overlay address which does not
 * exist yet, can never complete the call that would bring it up. This one dials the public
 * edge directly, on the platform's own resolver — the same plane the companion pairing call
 * uses before anything is linked.
 */
private fun enrollmentHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
    }
    expectSuccess = false
}

internal class BscOverlayEnrollmentApi(
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient = enrollmentHttpClient(),
) : OverlayEnrollmentApi {

    override suspend fun enroll(publicKeyBase64: String): EnrollAck {
        val response = runCatching {
            http.post("$baseUrl/api/overlay/enroll") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(overlayEnrollJson.encodeToString(EnrollRequestDto(publicKeyBase64)))
            }
        }.getOrElse { return EnrollAck.Rejected("Could not reach the server: ${it.message}") }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return EnrollAck.Rejected(describeEnrollFailure(body, response.status.value))
        }
        return decodeEnrollAck(body)
    }

    override suspend fun poll(): EnrollPoll {
        val response = runCatching {
            http.get("$baseUrl/api/overlay/enroll/status") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }.getOrElse { return EnrollPoll.Failed("Could not reach the server: ${it.message}") }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return EnrollPoll.Failed(describeEnrollFailure(body, response.status.value))
        }
        return decodeEnrollStatus(body)
    }
}

// ── the wire, decoded ───────────────────────────────────────────────────────────────────────
//
// Split out of the client above for one reason: every field name here is a contract with
// `bsc/routes/overlay.js`, and a rename on that side would otherwise show up only as a device
// that silently never enrols — the worst possible symptom, because it is indistinguishable
// from a server that is merely slow. As free functions they can be tested against literal
// JSON, which is what pins the contract.

internal val overlayEnrollJson = Json { ignoreUnknownKeys = true }

internal const val OVERLAY_ENROLL_DEFAULT_POLL_MS = 3_000L
internal const val OVERLAY_ENROLL_DEFAULT_CIDR = "10.77.0.0/24"
internal const val OVERLAY_ENROLL_DEFAULT_MTU = 1420

@Serializable
internal data class EnrollRequestDto(val pubkey: String)

@Serializable
internal data class EnrollAckDto(
    val status: String? = null,
    val name: String? = null,
    @SerialName("poll_after_ms") val pollAfterMs: Long? = null,
)

@Serializable
internal data class EnrollStatusDto(
    val status: String,
    val name: String? = null,
    val address: String? = null,
    @SerialName("server_pubkey") val serverPubkey: String? = null,
    val endpoint: String? = null,
    // The other two planes, which the route sends beside `endpoint` and which used to be dropped
    // on the floor here. `endpoint` is the server's own dial target and on a deployed box it is
    // the WAN literal, so a client at home needs `lan_endpoint` or it dials a NAT hole.
    @SerialName("lan_endpoint") val lanEndpoint: String? = null,
    @SerialName("wan_endpoint") val wanEndpoint: String? = null,
    @SerialName("overlay_cidr") val overlayCidr: String? = null,
    val mtu: Int? = null,
    val reason: String? = null,
)

@Serializable
internal data class EnrollErrorDto(val error: String? = null)

/**
 * The `202` from `POST /api/overlay/enroll`. A 202 is only ever `pending` — the route's
 * rejections all arrive as non-2xx and are handled by [describeEnrollFailure] before this is
 * reached — so the only thing to read here is the server's poll hint.
 */
internal fun decodeEnrollAck(body: String): EnrollAck {
    val dto = runCatching { overlayEnrollJson.decodeFromString<EnrollAckDto>(body) }.getOrNull()
        ?: return EnrollAck.Rejected("The server's enrollment reply was not understood.")
    return EnrollAck.Pending(pollAfterMs = dto.pollAfterMs ?: OVERLAY_ENROLL_DEFAULT_POLL_MS)
}

/** The `200` from `GET /api/overlay/enroll/status`. */
internal fun decodeEnrollStatus(body: String): EnrollPoll {
    val dto = runCatching { overlayEnrollJson.decodeFromString<EnrollStatusDto>(body) }.getOrNull()
        ?: return EnrollPoll.Failed("The server's status reply was not understood.")

    return when (dto.status) {
        "ready" -> {
            val address = dto.address
            val serverKey = dto.serverPubkey
            val endpoint = dto.endpoint
            if (address.isNullOrBlank() || serverKey.isNullOrBlank() || endpoint.isNullOrBlank()) {
                // ⚠️ A `ready` with a hole in it is NOT usable, and this is the one case worth
                // being strict about: applying it would point the tunnel at an endpoint with
                // no server key to authenticate, or hand the tunnel an address the server
                // never allocated — and the second failure mode is the silent-drops one.
                EnrollPoll.Failed("The server's assignment is incomplete.")
            } else {
                EnrollPoll.Ready(
                    OverlayAssignment(
                        address = address,
                        serverPublicKeyBase64 = serverKey,
                        endpoint = endpoint,
                        overlayCidr = dto.overlayCidr ?: OVERLAY_ENROLL_DEFAULT_CIDR,
                        mtu = dto.mtu ?: OVERLAY_ENROLL_DEFAULT_MTU,
                        // Carried through rather than dropped. The route has always sent this —
                        // `EnrollAckDto` and `EnrollStatusDto` both declared it — but nothing
                        // downstream read it, so it was parsed and discarded here. It is the CN
                        // the certificate route will demand, so it has to survive the boundary.
                        // Blank (an older server, or one that does not derive names) is tolerated
                        // and registers nothing: see `planCertificate`.
                        deviceName = dto.name.orEmpty(),
                        lanEndpoint = dto.lanEndpoint.orEmpty(),
                        wanEndpoint = dto.wanEndpoint.orEmpty(),
                    ),
                )
            }
        }
        "failed" -> EnrollPoll.Failed(dto.reason ?: "The server could not enroll this device.")
        else -> EnrollPoll.Pending
    }
}

/** Pulls the human-readable half out of an error body, falling back to the status code. */
internal fun describeEnrollFailure(body: String, status: Int): String {
    val detail = runCatching { overlayEnrollJson.decodeFromString<EnrollErrorDto>(body).error }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
    return when {
        detail != null -> detail
        status == 401 -> "This device is not paired with the server."
        else -> "The server refused the request ($status)."
    }
}

// ── the singleton ───────────────────────────────────────────────────────────────────────────

/**
 * The app's self-enrollment with the overlay server.
 *
 * Replaces the last hand-held step in the overlay: until this exists, a client's overlay
 * address and its enrolment with the server are both **build-time constants**
 * (`BOOMIO_OVERLAY_LOCAL_CIDR`, `_ENDPOINT`, `_PUBKEY`), which is not a property any shipped
 * app can have — the second client on an overlay needs a *different* address from the first,
 * and no build can know which one it is.
 *
 * On startup this applies the last assignment from disk (so the tunnel works before, or
 * without, a network) and then refreshes it in the background.
 */
