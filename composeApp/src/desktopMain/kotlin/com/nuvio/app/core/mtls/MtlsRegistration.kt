package com.nuvio.app.core.mtls

import com.nuvio.app.core.overlay.OverlayEnrollmentController
import com.nuvio.app.core.overlay.OverlayEnrollmentState
import com.nuvio.app.core.overlay.OverlayLog
import com.nuvio.app.core.overlay.OverlayProxy
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.companionRestBaseUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * The device's registration with the edge — `docs/mtls-plan.md` P2.3, with P2.6's re-registration
 * rule and P2's *"no re-registration on relaunch"* satisfied by [CertPlan.AlreadyRegistered].
 *
 * Unchanged in shape from mobile: read the server-assigned name the overlay learned, drive
 * [MtlsIdentity] to mint and bind a certificate, POST it on the session the app already holds.
 * Everything with a decision in it lives in [planCertificate] and [MtlsRegistrar], which is why
 * this file is mostly wiring.
 *
 * ── ⚠️ Which plane the POST takes on desktop, and how that differs ──────────
 * Mobile has two transports, and the *first* registration is the one that must not depend on the
 * public HTTPS route: a device with no certificate cannot complete a TLS handshake at a site that
 * enforces one, so registering over the edge is the bootstrap loop §13.2 warns about. Mobile's
 * answer is the **provision channel** — the connection it already has — with the route as the
 * fallback for a device that already holds a certificate.
 *
 * Desktop has no provision channel (that transport is not ported), so it uses the other
 * connection it already has: **the WireGuard tunnel**, reached through the loopback relay
 * ([OverlayProxy]). The client below therefore carries the relay's proxy selector and its
 * `Bearer <secret>` credential, which routes an allow-set host through the tunnel and fails open
 * to a direct connection when the tunnel is not up. That is the desktop equivalent of "the
 * transport that linked the device is the transport that registers its certificate", and it is
 * why this is not just the plain client enrollment uses.
 *
 * ⚠️ It still carries **no client certificate**, and that is not an oversight: registration is what
 * *creates* the certificate, so requiring one would be circular. The call is authenticated by the
 * `bs_ses_` session bearer alone, and the route is deliberately not one of §8's enforced sites.
 *
 * ── The store, and why it is not the enrollment one ────────────────────────
 * [com.nuvio.app.core.overlay.OverlayEnrollmentController] owns `boomio_overlay_enrollment` and
 * everything in it is the *server's* answer about where to reach it. This file is the *device's*
 * own identity, and the two have different lifetimes: a device can be re-enrolled (a new address, a
 * new endpoint) while keeping the same certificate, and a certificate can be re-minted without
 * touching the address. Sharing a file would invite exactly the confusion [CertPlan] exists to
 * prevent.
 *
 * Everything written here is public by construction — a certificate is sent in the clear on every
 * handshake (which is why the server writes it `0644`), and the rest is the server's own fingerprint
 * for it. The private key lives in [MtlsIdentity]'s PKCS#12 store and is never written here.
 */
internal object MtlsRegistration {

    private const val TAG = "BoomioMtls"
    private const val STORE = "boomio_mtls"

    private const val KEY_CERT_PEM = "cert_pem"
    private const val KEY_CERT_NAME = "cert_name"
    private const val KEY_REGISTERED = "cert_registered"
    private const val KEY_REGISTERED_FINGERPRINT = "cert_registered_fingerprint"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One registration at a time.
     *
     * ⚠️ Two triggers are expected to race — startup applies a cached assignment and calls for
     * registration while enrollment may refresh and call for it again — and without this both would
     * read the same "not yet registered" state and both POST the same PEM. That is not harmful to
     * the server (the write is idempotent), but it spends two of the device's ten-per-minute budget
     * and writes two ledger rows for one event.
     */
    private val lock = Mutex()

    private val _state = MutableStateFlow<MtlsRegistrationState>(MtlsRegistrationState.Idle)
    val state: StateFlow<MtlsRegistrationState> = _state.asStateFlow()

    @Volatile
    private var started = false

    @Volatile
    private var registrar: MtlsRegistrar? = null

    @Volatile
    private var http: OkHttpClient? = null

    /** Kept so [presentableCertificate] can answer after start-up, not only during it. */
    @Volatile
    private var store: DesktopStorage.Store? = null

    fun initialize() {
        if (started) return
        started = true

        val backing = DesktopStorage.store(STORE)
        store = backing

        registrar = MtlsRegistrar(
            api = ::apiOrNull,
            assignedName = { BoomioConfig.overlayDeviceName },
            held = { read(backing) },
            mintAndInstall = ::mintAndInstall,
            remember = { write(backing, it) },
        )

        // ── the trigger for the FIRST registration, which nothing else provides ──────────────
        //
        // ⚠️ **Without this the whole mTLS half is unreachable, and it fails silently.**
        // [MtlsHandshakeWatch] is a *re-registration* trigger and is gated on `holdsCertificate()`,
        // so it cannot fire before a certificate exists — it can only repair one that does. Minting
        // happens here, and the only thing that ever said "now" is the assignment: the certificate's
        // CN *is* the name the assignment carries, so until one has landed there is nothing to mint
        // for and `planCertificate` correctly answers `Unavailable`.
        //
        // Bound to the state rather than run once, for the same reason enrollment binds to the
        // session: on a fresh install this runs before pairing completes, and a one-shot call here
        // would land on `Unavailable` and never run again. Startup with a cached assignment emits
        // `Ready` immediately, which is the ordinary relaunch path — and it costs no network I/O,
        // because `planCertificate` answers `AlreadyRegistered` and `register` returns before it
        // ever reaches the api.
        scope.launch {
            OverlayEnrollmentController.state
                .filterIsInstance<OverlayEnrollmentState.Ready>()
                .collect { ensureRegistered() }
        }
    }

    /**
     * Bring the registration up to date, and report what happened.
     *
     * Ordinary callers should use [requestRegistration]: this suspends for as long as the retry
     * window lasts, which is up to ~16s when the tunnel is still coming up.
     *
     * @param force re-send even though these bytes were already acknowledged — P2.6's "the server
     *   does not know this device" path, and the only thing that causes a second POST.
     */
    suspend fun ensureRegistered(force: Boolean = false): MtlsRegistrationState = lock.withLock {
        // ⚠️ **Sticky.** `Revoked` is terminal — re-admitting the device restores the *same*
        // allow-list entry, so the certificate already held starts working again with no
        // re-registration at all — and [MtlsHandshakeWatch] reads this state to decide whether to
        // keep asking. An assignment refresh after a revocation would otherwise run a registration
        // that answers `Skipped` or `Deferred`, overwrite `Revoked`, and quietly re-arm the watch.
        // One extra attempt is not the cost; the cost is that the device starts re-asking a
        // question an operator answered.
        val revocation = _state.value
        if (!force && revocation is MtlsRegistrationState.Revoked) {
            revocation
        } else {
            val runner = registrar ?: return MtlsRegistrationState.Skipped("mTLS is not initialized")
            val result = runner.register(force)
            _state.value = result
            OverlayLog.i(TAG, "mTLS registration: $result")
            result
        }
    }

    /**
     * Ask for a registration without waiting for it.
     *
     * Safe to call from anywhere and cheap to call repeatedly: the plan is re-read under the lock
     * each time, so a device that is already registered does no network I/O at all.
     */
    fun requestRegistration(force: Boolean = false) {
        if (!started) return
        scope.launch { ensureRegistered(force) }
    }

    /**
     * The certificate to present in a handshake, or `null` when there is nothing legitimate to
     * present. This is what [MtlsSsl] hands to an HTTP client.
     *
     * ⚠️ **The comparison is on the full DER, not on the public key.** Kept from mobile even
     * though desktop's store has no placeholder-certificate behaviour: the gate is cheap, and it is
     * the same invariant on both platforms — what is presented is exactly what was registered, not a
     * certificate that merely shares a key.
     */
    internal fun presentableCertificate(): X509Certificate? {
        val bound = runCatching { MtlsIdentity.certificate() }.getOrNull() ?: return null
        val pem = store?.getString(KEY_CERT_PEM)?.takeIf { it.isNotBlank() } ?: return null
        val registered = parseCertificate(pem) ?: return null
        return if (bound.encoded.contentEquals(registered.encoded)) bound else null
    }

    /**
     * The client's certificate, PEM. `null` until one has been minted and bound.
     */
    fun certificatePem(): String? =
        presentableCertificate()?.let { MtlsCertificate.toPem(it.encoded) }

    // ── the wire ─────────────────────────────────────────────────────────────

    private fun apiOrNull(): MtlsRegistrationApi? {
        val token = BoomioSessionRepository.bearerToken()?.takeIf { it.isNotBlank() } ?: return null
        val base = BoomioConfig.companionRestBaseUrl.takeIf { it.isNotBlank() } ?: return null
        return BscMtlsRegistrationApi(baseUrl = base, token = token, http = client())
    }

    /**
     * One client for the process.
     *
     * ⚠️ Caching this is safe because [OverlayProxy.selector] is consulted per *route*, not once at
     * construction, so a relay that comes up after this client was built is picked up by the next
     * new connection. Building a client per attempt would buy nothing and leak an engine each time.
     */
    private fun client(): OkHttpClient {
        http?.let { return it }
        return synchronized(this) {
            http ?: OverlayProxy.withOverlayProxy(OkHttpClient.Builder())
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
                .also { http = it }
        }
    }

    // ── the device ───────────────────────────────────────────────────────────

    /**
     * Mint for [name] and bind it to the keystore key.
     *
     * ⚠️ [MtlsIdentity.loadOrCreate] — never `generate`. This is the whole of P2.6's *"(do **not**
     * silently generate a new key)"*: the key is the one thing in this flow that cannot be replaced
     * without leaving a live allow-list entry behind, so the only path to a new key here is a device
     * that has no key at all.
     */
    private fun mintAndInstall(name: String): ClientCertificate {
        val keyPair = MtlsIdentity.loadOrCreate()
        val certificate = MtlsCertificate.selfSign(name, keyPair, System.currentTimeMillis())
        MtlsIdentity.installCertificate(keyPair, certificate)
        OverlayLog.i(TAG, "Minted a client certificate for $name (${certificate.fingerprintSha256.take(16)}…)")
        return certificate
    }

    private fun read(store: DesktopStorage.Store): HeldCertificate? {
        val pem = store.getString(KEY_CERT_PEM)?.takeIf { it.isNotBlank() } ?: return null
        val name = store.getString(KEY_CERT_NAME)?.takeIf { it.isNotBlank() } ?: return null
        return HeldCertificate(
            assignedName = name,
            pem = pem,
            matchesCurrentKey = isBoundToCurrentKey(pem),
            registered = store.getBoolean(KEY_REGISTERED) ?: false,
            registeredFingerprintSha256 = store.getString(KEY_REGISTERED_FINGERPRINT),
        )
    }

    private fun write(store: DesktopStorage.Store, held: HeldCertificate) {
        store.putString(KEY_CERT_PEM, held.pem)
        store.putString(KEY_CERT_NAME, held.assignedName)
        store.putBoolean(KEY_REGISTERED, held.registered)
        store.putString(KEY_REGISTERED_FINGERPRINT, held.registeredFingerprintSha256)
    }

    /**
     * Whether the stored certificate belongs to the key the store holds now.
     *
     * ⚠️ This is the check that catches a **lost key behind a kept certificate**, and the reason it
     * exists is that the server cannot catch it: `parseClientCertificate` validates the certificate
     * alone and never asks the device to prove it holds the private half. So a mismatched pair
     * registers with a `200` and then fails the TLS handshake on the *edge*.
     *
     * A stored PEM that will not parse answers `false` — treated as unusable, which is what it is.
     */
    private fun isBoundToCurrentKey(pem: String): Boolean = runCatching {
        val parsed = parseCertificate(pem) ?: return@runCatching false
        MtlsIdentity.certificate()?.publicKey == parsed.publicKey
    }.getOrDefault(false)
}

/** A stored PEM as a certificate, or `null` if it will not parse. An unparseable PEM is unusable. */
private fun parseCertificate(pem: String): X509Certificate? = runCatching {
    CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate
}.getOrNull()

/**
 * `POST /api/overlay/cert` on the companion session.
 *
 * ⚠️ Every status is handed to [decodeCertRegistration] rather than thrown — the route's refusals
 * are the interesting replies, and an exception would flatten all of them into one.
 */
internal class BscMtlsRegistrationApi(
    private val baseUrl: String,
    private val token: String,
    private val http: OkHttpClient,
) : MtlsRegistrationApi {

    override suspend fun register(pem: String): CertRegistrationAck {
        val request = Request.Builder()
            .url("$baseUrl/api/overlay/cert")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(
                mtlsRegistrationJson.encodeToString(CertRegistrationRequestDto(pem))
                    .toRequestBody(JSON),
            )
            .build()

        // A connect timeout while the tunnel comes up is the expected first failure, so a transport
        // error is `Deferred` and not an error: the registrar's retry window exists for this.
        val response = runCatching { http.newCall(request).execute() }.getOrElse {
            return CertRegistrationAck.Deferred("Could not reach the server: ${it.message}")
        }
        return response.use {
            decodeCertRegistration(it.code, it.body?.string().orEmpty())
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
