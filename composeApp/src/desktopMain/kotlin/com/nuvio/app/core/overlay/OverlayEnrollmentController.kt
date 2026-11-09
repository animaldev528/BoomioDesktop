package com.nuvio.app.core.overlay

import com.nuvio.app.core.mtls.MtlsRegistration
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.BoomioSession
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.companionRestBaseUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
 * ⚠️ **Enrollment has to be asked for, and this object is the only thing that asks.** It did not
 * for the first seven commits of this port: `initialize()` applied a *cached* assignment and
 * stopped, so a machine that had paired and had never run before held a session, a device key and
 * no way to use either — the tunnel, the relay and the certificate were all waiting on a request
 * nothing sent. [watchCompanionSessions] closes that: the token becoming present *is* the trigger,
 * which is what makes pairing sufficient rather than just first.
 *
 * ⚠️ **The hooks, and why they are told rather than polled.** Mobile's `writeConfig` also
 * wakes `OverlaySession`, `OverlayRelay` and `MtlsRegistration`, and all three are called below.
 * None of them can watch for the assignment: the address is a plain `var` with no flow behind it,
 * and this function is its only writer — so "the assignment took effect" is announced from the
 * one place that makes it true, rather than inferred somewhere else.
 */
internal object OverlayEnrollmentController {

    private const val PREFS = "boomio_overlay_enrollment"

    private const val KEY_ADDRESS = "address"
    private const val KEY_SERVER_PUBKEY = "server_pubkey"
    private const val KEY_ENDPOINT = "endpoint"
    // ⚠️ Cached, not re-derived from `endpoint`: a cache restored without the LAN plane puts a
    // machine back on the WAN literal at every launch, which is the failure this pair exists to
    // avoid -- and the restore path is the *common* one, since enrollment on a healthy box is
    // served from here rather than from a fresh network round trip.
    private const val KEY_LAN_ENDPOINT = "lan_endpoint"
    private const val KEY_WAN_ENDPOINT = "wan_endpoint"
    private const val KEY_OVERLAY_CIDR = "overlay_cidr"
    private const val KEY_MTU = "mtu"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_ISSUED_FOR = "issued_for_public_key"

    private val FORGET_KEYS = listOf(
        KEY_ADDRESS,
        KEY_SERVER_PUBKEY,
        KEY_ENDPOINT,
        KEY_LAN_ENDPOINT,
        KEY_WAN_ENDPOINT,
        KEY_OVERLAY_CIDR,
        KEY_MTU,
        KEY_DEVICE_NAME,
        KEY_ISSUED_FOR,
    )

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

        // ⚠️ **This is the only thing that ever asks the server for an assignment.**
        // A held token enrolls once at startup (the cache is not trusted, so a rotated server key
        // is picked up); a token that appears later — this install pairing for the first time —
        // enrolls then. `refresh()` rather than `enroll(useCache = true)` for the reason
        // [refresh] gives: the cache here is a fallback for a failed request, not a way to skip a
        // working one.
        //
        // Safe before `OverlaySession.initialize()` runs, despite the order in `main()`: the
        // device key comes from `OverlayWgTunnelController.instance`, whose getter initialises the
        // controller itself, and every hook `applyAssignment` fires tolerates a null driver.
        watchCompanionSessions(scope, BoomioSessionRepository.session) { refresh() }
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

    /**
     * Drops the assignment cached for a server this install has just left.
     *
     * ⚠️ **The cache is keyed to the device, not to the server.** Restoring it needs no network,
     * which is what makes a cold start off-LAN work — and exactly why a server switch has to
     * erase it: otherwise the next launch brings the tunnel back up against the old box while
     * the rest of the app talks to the new one. Enrollment re-runs by itself as soon as the new
     * server issues a session, because that is what [initialize]'s session watcher is for.
     */
    fun forget() {
        store?.removeAll(FORGET_KEYS)
        _state.value = OverlayEnrollmentState.Idle
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
        // The planes are written *beside* the endpoint, not instead of it: which one the tunnel
        // actually dials is the ladder's decision, and it is made by probing them. Overwriting
        // `overlayEndpoint` with the LAN literal here would throw away the server's own answer and
        // leave nothing to fall back to when the machine leaves the house.
        BoomioConfig.overlayLanEndpoint = assignment.lanEndpoint
        BoomioConfig.overlayWanEndpoint = assignment.wanEndpoint
        BoomioConfig.overlayServerPubKey = assignment.serverPublicKeyBase64
        BoomioConfig.overlayDeviceName = assignment.deviceName

        // Blank means the CIDR did not parse, and a blank must NOT clobber an operator-supplied
        // value: the DNS seam stays off only when there is genuinely nothing to aim it at.
        assignment.serverAddress.takeIf { it.isNotBlank() }?.let {
            BoomioConfig.overlayServerAddress = it
        }
        // ⚠️ **Order matters, and it is mobile's.** The session goes first: it is what rebuilds
        // the device onto the assigned /32, and the relay's bound status is only meaningful once
        // there is a tunnel to carry. Both are told rather than polled — the address is a plain
        // `var` with no flow behind it, and the writer is one call away.
        OverlaySession.onServerAddressLearned()
        OverlayRelay.onServerAddressLearned()
        // The assignment usually *is* the endpoint (these are what rung 3 reads), so this is
        // also the first moment the ladder can find anything on a freshly enrolled install.
        OverlayEndpointDiscovery.refreshAsync()
        // ⚠️ **The certificate rides on the assignment, not on a schedule of its own.** The CN it
        // must carry *is* the device name written two lines up, and the edge's allow-list is keyed
        // on that string — so anything that applies one without the other leaves a device holding
        // a tunnel it may use and a certificate it may not. Cheap to call repeatedly: the
        // registrar re-reads its plan and answers `AlreadyRegistered` without network I/O.
        MtlsRegistration.requestRegistration()
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
                lanEndpoint = store.getString(KEY_LAN_ENDPOINT).orEmpty(),
                wanEndpoint = store.getString(KEY_WAN_ENDPOINT).orEmpty(),
            ),
            issuedForPublicKeyBase64 = issuedFor,
        )
    }

    private fun write(store: DesktopStorage.Store, cached: CachedAssignment) {
        store.putString(KEY_ADDRESS, cached.assignment.address)
        store.putString(KEY_SERVER_PUBKEY, cached.assignment.serverPublicKeyBase64)
        store.putString(KEY_ENDPOINT, cached.assignment.endpoint)
        store.putString(KEY_LAN_ENDPOINT, cached.assignment.lanEndpoint)
        store.putString(KEY_WAN_ENDPOINT, cached.assignment.wanEndpoint)
        store.putString(KEY_OVERLAY_CIDR, cached.assignment.overlayCidr)
        store.putInt(KEY_MTU, cached.assignment.mtu)
        store.putString(KEY_ISSUED_FOR, cached.issuedForPublicKeyBase64)
        store.putString(KEY_DEVICE_NAME, cached.assignment.deviceName)
    }

}

/**
 * Fires [onToken] once for every **distinct** companion session token.
 *
 * ⚠️ **Distinct, because a session is re-published for reasons that are not a new session.**
 * `adoptSession` writes a fresh [BoomioSession] to record the user and display name the pairing
 * returned; the token is the same one. Enrolling again for that would be a needless POST — and,
 * worse, a needless *write* to the cache and to the certificate registrar every time anything
 * about the session object changes. Keyed on the token, a re-link (a genuinely new token) still
 * enrolls.
 *
 * ⚠️ **Null never fires.** No session means there is nothing to enroll *as*; [apiOrNull] would
 * answer null and the enroller would report `Unavailable`, which is a state, not a failure to
 * retry. Extracted from [OverlayEnrollmentController] so this rule is testable without a server.
 */
internal fun watchCompanionSessions(
    scope: CoroutineScope,
    sessions: Flow<BoomioSession?>,
    onToken: suspend (String) -> Unit,
): Job = scope.launch {
    sessions
        .map { it?.token }
        .distinctUntilChanged()
        .filterNotNull()
        .collect(onToken)
}
