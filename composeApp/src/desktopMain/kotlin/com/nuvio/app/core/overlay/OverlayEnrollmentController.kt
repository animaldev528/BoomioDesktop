package com.nuvio.app.core.overlay

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
import kotlinx.coroutines.launch

/**
 * Desktop wiring for the enrollment policy in [OverlayEnroller].
 *
 * The policy itself is shared code — it only ever sees the API, the device key, a cache and an
 * apply callback. This object supplies the desktop versions of those four things:
 *
 *   - the API carries the `bs_ses_*` companion session, so enrollment is only possible for a
 *     device that has paired ([BoomioSessionRepository]);
 *   - the device key comes from the tunnel controller, which mints and persists it;
 *   - the cache is a [DesktopStorage] store;
 *   - applying an assignment writes [BoomioConfig], which is what the tunnel ladder and the
 *     DNS seam read live.
 *
 * ⚠️ **What is deliberately missing here, and why.** Mobile's `writeConfig` also wakes
 * `OverlaySession`, `OverlayRelay` and `MtlsRegistration`. Those three have not been ported yet,
 * and calling a no-op stub in their place would make "the assignment took effect" true for the
 * address but silently false for the certificate and the bridge. The hooks are therefore
 * omitted, not faked; each lands with the class it wakes.
 */
internal object OverlayEnrollmentController {

    private const val PREFS = "boomio_overlay_enrollment"

    private const val KEY_ADDRESS = "address"
    private const val KEY_SERVER_PUBKEY = "server_pubkey"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_OVERLAY_CIDR = "overlay_cidr"
    private const val KEY_MTU = "mtu"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_ISSUED_FOR = "issued_for_public_key"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<OverlayEnrollmentState>(OverlayEnrollmentState.Idle)
    val state: StateFlow<OverlayEnrollmentState> = _state.asStateFlow()

    private var store: DesktopStorage.Store? = null
    private var enroller: OverlayEnroller? = null
    private var started = false

    fun initialize() {
        if (started) return
        started = true

        val preferences = DesktopStorage.store(PREFS)
        store = preferences

        // Applied synchronously and before any network call, so a cold start off-network still
        // has a working tunnel from last run rather than waiting on a request that cannot land.
        read(preferences)?.let { cached ->
            val key = devicePublicKey()
            if (key != null && cached.issuedForPublicKeyBase64 == key) {
                applyAssignment(cached.assignment)
                _state.value = OverlayEnrollmentState.Ready(cached.assignment, fromCache = true)
            }
        }

        enroller = OverlayEnroller(
            api = ::apiOrNull,
            devicePublicKey = ::devicePublicKey,
            cached = { store?.let(::read) },
            remember = { cached -> store?.let { write(it, cached) } },
            apply = ::applyAssignment,
        )
    }

    /** Applies the cache first when it matches this device key, then talks to the server. */
    suspend fun enroll(useCache: Boolean = true): OverlayEnrollmentState {
        val runner = enroller ?: return OverlayEnrollmentState.Unavailable
        _state.value = OverlayEnrollmentState.Enrolling
        val result = runner.enroll(useCache)
        _state.value = result
        return result
    }

    /** The startup path: never trusts the cache, so a rotated server key is picked up. */
    suspend fun refresh(): OverlayEnrollmentState {
        val result = enroll(useCache = false)
        if (result is OverlayEnrollmentState.Failed) {
            // The cache, if there is one, is already applied and still working — a failed
            // refresh must not be reported as a device that has no address.
            val held = store?.let(::read)
            if (held != null && held.issuedForPublicKeyBase64 == devicePublicKey()) {
                _state.value = OverlayEnrollmentState.Ready(held.assignment, fromCache = true)
                return _state.value
            }
        }
        return result
    }

    private fun apiOrNull(): OverlayEnrollmentApi? {
        val token = BoomioSessionRepository.bearerToken()?.takeIf { it.isNotBlank() } ?: return null
        val base = BoomioConfig.companionRestBaseUrl.takeIf { it.isNotBlank() } ?: return null
        return BscOverlayEnrollmentApi(baseUrl = base, token = token)
    }

    private fun devicePublicKey(): String? =
        runCatching { OverlayWgTunnelController.instance?.publicKeyBase64() }.getOrNull()

    /**
     * Points the app at the assigned server. Synchronous on purpose: these are read live by the
     * tunnel ladder and the DNS seam, so writing them is what "the assignment took effect" means.
     */
    private fun applyAssignment(assignment: OverlayAssignment) {
        BoomioConfig.overlayLocalCidr = assignment.localCidr
        BoomioConfig.overlayEndpoint = assignment.endpoint
        BoomioConfig.overlayServerPubKey = assignment.serverPublicKeyBase64
        BoomioConfig.overlayDeviceName = assignment.deviceName

        // Blank means the CIDR did not parse, and a blank must NOT clobber an operator-supplied
        // value: the DNS seam stays off only when there is genuinely nothing to aim it at.
        assignment.serverAddress.takeIf { it.isNotBlank() }?.let {
            BoomioConfig.overlayServerAddress = it
        }
        // The mTLS registration and the companion bridge hooks that mobile fires here belong to
        // the slices that port `MtlsRegistrar` and `OverlaySession`/`OverlayRelay`.
    }

    private fun read(store: DesktopStorage.Store): CachedAssignment? {
        val address = store.getString(KEY_ADDRESS) ?: return null
        val serverKey = store.getString(KEY_SERVER_PUBKEY) ?: return null
        val endpoint = store.getString(KEY_ENDPOINT) ?: return null
        val issuedFor = store.getString(KEY_ISSUED_FOR) ?: return null
        return CachedAssignment(
            OverlayAssignment(
                address = address,
                serverPublicKeyBase64 = serverKey,
                endpoint = endpoint,
                overlayCidr = store.getString(KEY_OVERLAY_CIDR) ?: OVERLAY_ENROLL_DEFAULT_CIDR,
                mtu = store.getInt(KEY_MTU) ?: OVERLAY_ENROLL_DEFAULT_MTU,
                deviceName = store.getString(KEY_DEVICE_NAME).orEmpty(),
            ),
            issuedForPublicKeyBase64 = issuedFor,
        )
    }

    private fun write(store: DesktopStorage.Store, cached: CachedAssignment) {
        store.putString(KEY_ADDRESS, cached.assignment.address)
        store.putString(KEY_SERVER_PUBKEY, cached.assignment.serverPublicKeyBase64)
        store.putString(KEY_ENDPOINT, cached.assignment.endpoint)
        store.putString(KEY_OVERLAY_CIDR, cached.assignment.overlayCidr)
        store.putInt(KEY_MTU, cached.assignment.mtu)
        store.putString(KEY_ISSUED_FOR, cached.issuedForPublicKeyBase64)
        store.putString(KEY_DEVICE_NAME, cached.assignment.deviceName)
    }

    /** Unused today; kept wired so a UI can trigger the startup refresh without its own scope. */
    @Suppress("unused")
    fun refreshInBackground() {
        scope.launch { refresh() }
    }
}
