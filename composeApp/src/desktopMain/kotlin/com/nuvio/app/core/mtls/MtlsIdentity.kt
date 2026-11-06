package com.nuvio.app.core.mtls

import com.nuvio.app.core.overlay.OverlayLog
import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The device's mTLS identity: one RSA-2048 key and — once registration succeeds — the certificate
 * that key minted, both held in a PKCS#12 store on disk.
 *
 * This is `docs/mtls-plan.md` P2.1, as it has to be spelled on a desktop. The contract is
 * [com.nuvio.app.core.mtls]'s mobile `MtlsIdentity` one-for-one — `hasKey`, `loadOrCreate`,
 * `existing`, `generate`, `installCertificate`, `certificate`, `delete` — because everything above
 * it (`MtlsRegistrar`, `MtlsRegistration`, `MtlsSsl`) is ported unchanged and would not know which
 * platform it was running on.
 *
 * ── ⚠️ The downgrade, stated plainly ────────────────────────────────────────
 * On Android the private half never leaves the keystore: `AndroidKeyStore` performs the signature
 * itself, so possession of the *hardware* is the credential and a certificate in the edge's
 * allow-list is not a bearer token. **A PKCS#12 file can be read by anyone who can read the file**,
 * so on desktop a registered certificate *is* a bearer token for whoever holds a copy of the file.
 *
 * That is not fixable at this layer. The OS keychains (`Keychain` on macOS, `DPAPI`/`NCrypt` on
 * Windows, `Secret Service` on Linux) would restore the property on the first two, and the plan is
 * to move there for a release build; the file store is what makes the plane testable and shippable
 * in the meantime, and it is honest about what it is:
 *
 *  - The store and its password live under [DesktopStorage.rootDir], which is the per-user
 *    application data directory (`~/.config/nuvio` on Linux, `Application Support/Nuvio` on macOS,
 *    `%APPDATA%\Nuvio` on Windows).
 *  - Both files are chmod `0600` where the filesystem supports POSIX permissions.
 *  - The password is random per install and exists so that a stray `keytool -list` on the file does
 *    not open it, **not** so that an attacker with read access to the directory is stopped. They are
 *    not. See the note on macOS/Windows above for what would.
 *
 * ── Why a keystore at all, rather than two PEM files ────────────────────────
 * Same reason as mobile: with the key and its certificate under one alias, P2.4's attach is
 * `KeyManagerFactory.getInstance("X509").init(keyStore, password)` and the platform picks the alias.
 * The alternative — hand-writing an `X509KeyManager` — is exactly where an mTLS client silently
 * sends *no* certificate, and it would be a second source of truth for a pairing the keystore
 * already maintains.
 *
 * ── The generated-but-not-yet-installed key ─────────────────────────────────
 * ⚠️ **A PKCS#12 store cannot hold a bare private key** — every entry carries a certificate chain.
 * `AndroidKeyStore` can (it invents a `CN=Unverified…` placeholder), so mobile's `generate()` writes
 * straight through. Here the freshly generated pair is held in memory until
 * [installCertificate] binds a certificate to it, which is the very next thing
 * `MtlsRegistrar.mintAndInstall` does. A process that dies in between loses a key that was never
 * registered and never sent anywhere, so the next launch simply generates another — there is nothing
 * to orphan.
 */
internal object MtlsIdentity {

    /**
     * One alias, versioned — unchanged from mobile.
     *
     * Versioned because the alias is the only thing tying a keystore entry to this feature: a future
     * change that needs a different key type leaves the old entry alone and revocable rather than
     * silently reinterpreting it.
     */
    const val KEY_ALIAS: String = "boomio.mtls.client.v1"

    private const val TAG = "BoomioMtls"
    private const val STORE_FILE = "boomio_mtls_identity.p12"
    private const val PASSWORD_FILE = "boomio_mtls_identity.pwd"
    private const val PASSWORD_BYTES = 32

    private val lock = Any()

    private var store: KeyStore? = null
    private var storePassword: CharArray? = null

    /**
     * A key that has been generated but has no certificate yet.
     *
     * See the object doc: the store cannot hold it, so it lives here until [installCertificate].
     */
    private var pendingKeyPair: KeyPair? = null

    /**
     * Where the identity lives. Overridable so tests do not write to the real user directory.
     */
    @Volatile
    internal var rootOverride: Path? = null

    /** Whether this device already holds a client key. */
    fun hasKey(): Boolean = synchronized(lock) {
        pendingKeyPair != null || keyStore().containsAlias(KEY_ALIAS)
    }

    /**
     * The identity to use: the existing key if there is one, otherwise a newly generated one.
     *
     * ⚠️ **Callers must prefer this over [generate].** Re-generating replaces the key, which
     * invalidates the certificate already registered in the edge's allow-list — the device would
     * then hold a certificate that signs with the wrong key and a registration that names the wrong
     * bytes. P2.6's rule ("re-register the existing certificate, do not mint a new key") exists to
     * stop exactly that, and it is why this is the only generating entry point the flow uses.
     */
    fun loadOrCreate(): KeyPair = existing() ?: generate()

    /** The existing keypair, or null. Never generates. */
    fun existing(): KeyPair? {
        synchronized(lock) {
            pendingKeyPair?.let { return it }
            val keyStore = keyStore()
            if (!keyStore.containsAlias(KEY_ALIAS)) return null

            val privateKey =
                runCatching { keyStore.getKey(KEY_ALIAS, password()) as? PrivateKey }.getOrNull()
            val publicKey = runCatching { keyStore.getCertificate(KEY_ALIAS)?.publicKey }.getOrNull()
            if (privateKey == null && publicKey == null) return null
            // Half an entry is not a state this object produces, so reaching here means something
            // outside the app touched the alias. Refusing beats returning a pair with a null half.
            check(privateKey != null && publicKey != null) {
                "keystore alias '$KEY_ALIAS' is in a partial state " +
                    "(key=${privateKey != null}, cert=${publicKey != null})"
            }
            return KeyPair(publicKey, privateKey)
        }
    }

    /**
     * Generate a fresh RSA-2048 key.
     *
     * ⚠️ **Destructive to the current identity** — see [loadOrCreate]. Only call this when there is
     * no key, or when the caller has deliberately decided to re-key (which also means re-registering
     * and revoking the old certificate).
     *
     * There is no StrongBox/TEE attempt order here because desktops have neither: the JCA
     * `KeyPairGenerator` is the platform's own, and `MtlsStrongBox` is not ported. RSA-2048 is the
     * floor the server enforces (`bsc/lib/overlay-cert.js`), so it is not a choice worth exposing.
     */
    fun generate(): KeyPair = synchronized(lock) {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        pendingKeyPair = keyPair
        OverlayLog.i(TAG, "Generated an RSA-2048 client key (not yet bound to a certificate)")
        keyPair
    }

    /**
     * Bind a minted certificate to the key so the platform's own `X509KeyManager` can find it.
     *
     * The certificate is parsed back from DER rather than constructed here: `setKeyEntry` wants a
     * `java.security.cert.Certificate`, and [MtlsCertificate] deliberately produces bytes (the edge
     * compares raw DER, so the bytes are the artifact). Re-parsing is how the bytes that get
     * registered and the bytes that get stored are guaranteed to be the same ones.
     */
    fun installCertificate(keyPair: KeyPair, certificate: ClientCertificate) = synchronized(lock) {
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificate.der.inputStream())
        val keyStore = keyStore()
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, password(), arrayOf(parsed))
        pendingKeyPair = null
        persist(keyStore)
    }

    /**
     * The certificate currently bound to the alias.
     *
     * Unlike mobile's, this is never a placeholder: a PKCS#12 entry only exists once
     * [installCertificate] has written one, so a certificate here *is* the registered one. Callers
     * still go through `MtlsRegistration.presentableCertificate`, which re-checks the stored PEM
     * against these bytes — the gate is worth keeping even where the platform makes it cheaper.
     */
    fun certificate(): X509Certificate? = synchronized(lock) {
        runCatching { keyStore().getCertificate(KEY_ALIAS) as? X509Certificate }.getOrNull()
    }

    /** Remove the identity. The registered certificate must be revoked server-side separately. */
    fun delete() = synchronized(lock) {
        pendingKeyPair = null
        val keyStore = keyStore()
        keyStore.deleteEntry(KEY_ALIAS)
        persist(keyStore)
        OverlayLog.i(TAG, "Deleted the mTLS client key")
    }

    /**
     * The store, loaded from disk on first use.
     *
     * ⚠️ **A store whose password file is missing is treated as absent, not as an error.**
     * Practically that means the next [loadOrCreate] generates a new key — and the certificate
     * registered against the lost one is orphaned on the server until an operator revokes it. That
     * is the same outcome as a wiped Android keystore, and nothing on this side can recover from it;
     * saying so in the log is all this layer can do.
     */
    fun keyStore(): KeyStore = synchronized(lock) { storeLocked() }

    /** The store's password. Handed to `KeyManagerFactory.init` by [MtlsSsl] and nothing else. */
    fun keyStorePassword(): CharArray = synchronized(lock) { password().copyOf() }

    /** Tests only: forget everything held in memory and stop overriding the directory. */
    internal fun resetForTests() = synchronized(lock) {
        store = null
        storePassword = null
        pendingKeyPair = null
        rootOverride = null
    }

    // ── the file store ───────────────────────────────────────────────────────

    private fun storeLocked(): KeyStore {
        store?.let { return it }

        val file = storeFile()
        val loaded = if (Files.exists(file)) {
            runCatching {
                KeyStore.getInstance("PKCS12").apply {
                    Files.newInputStream(file).use { load(it, password()) }
                }
            }.onFailure {
                OverlayLog.w(TAG, "Could not open the mTLS keystore at $file; starting a new identity", it)
            }.getOrNull()
        } else {
            null
        }

        val resolved = loaded ?: KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store = resolved
        return resolved
    }

    private fun password(): CharArray {
        storePassword?.let { return it }
        val resolved = readPassword() ?: generatePassword()
        storePassword = resolved
        return resolved
    }

    private fun persist(keyStore: KeyStore) {
        val file = storeFile()
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { keyStore.store(it, password()) }
        restrict(file)

        val passwordFile = passwordFile()
        Files.writeString(passwordFile, String(password()))
        restrict(passwordFile)
    }

    private fun readPassword(): CharArray? {
        val file = passwordFile()
        if (!Files.exists(file)) return null
        val value = runCatching { Files.readString(file).trim() }.getOrNull()
        return value?.takeIf { it.isNotEmpty() }?.toCharArray()
    }

    private fun generatePassword(): CharArray {
        val bytes = ByteArray(PASSWORD_BYTES).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }.toCharArray()
    }

    /**
     * Owner-only where the filesystem has permissions at all.
     *
     * Best-effort by design: a non-POSIX filesystem (the common Windows case) throws
     * `UnsupportedOperationException`, and a failure to tighten permissions must not cost the user
     * their identity. The file is still inside the per-user application directory.
     */
    private fun restrict(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private fun root(): Path = rootOverride ?: DesktopStorage.rootDir

    private fun storeFile(): Path = root().resolve(STORE_FILE)

    private fun passwordFile(): Path = root().resolve(PASSWORD_FILE)
}
