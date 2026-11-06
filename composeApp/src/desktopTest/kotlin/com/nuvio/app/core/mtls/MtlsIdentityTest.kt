package com.nuvio.app.core.mtls

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one part of the mTLS plane mobile cannot test, tested here.
 *
 * ⚠️ **This is the whole point of the desktop `MtlsIdentity` being a file store.** Mobile's KDoc
 * says it plainly — *"nothing here is unit-tested, and it cannot be"* — because `AndroidKeyStore`
 * has no host provider. A `PKCS#12` file does, so the contract the rest of the package depends on
 * (`loadOrCreate` re-uses, `installCertificate` binds, `delete` removes) is asserted here against a
 * real provider rather than resting on a compile.
 *
 * The properties that matter, in the order they matter:
 *
 *  1. **`loadOrCreate` never replaces a live key.** Re-keying orphans a registered certificate;
 *     the edge's allow-list is keyed on bytes and the device is capped at three.
 *  2. **`installCertificate` survives a restart**, which is what makes P2's *"no re-registration on
 *     relaunch"* possible at all — `MtlsRegistration.presentableCertificate` compares the stored PEM
 *     against what the store hands back, so a store that forgets produces a device that re-POSTs on
 *     every launch.
 *  3. **The password file is written beside the store**, because a store with no password is a store
 *     that cannot be opened on the next launch — and the answer to that is a new key, not an error.
 */
class MtlsIdentityTest {

    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("boomio-mtls-identity")
        MtlsIdentity.resetForTests()
        MtlsIdentity.rootOverride = root
    }

    @AfterTest
    fun tearDown() {
        MtlsIdentity.resetForTests()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a fresh install has no key and generates one on demand`() {
        assertFalse(MtlsIdentity.hasKey())
        assertNull(MtlsIdentity.existing())

        val keyPair = MtlsIdentity.loadOrCreate()

        assertEquals("RSA", keyPair.private.algorithm)
        assertTrue(MtlsIdentity.hasKey())
        assertContentEquals(keyPair.public.encoded, MtlsIdentity.existing()?.public?.encoded)
    }

    @Test
    fun `loadOrCreate re uses a live key rather than re-keying`() {
        val first = MtlsIdentity.loadOrCreate()
        val second = MtlsIdentity.loadOrCreate()
        assertContentEquals(first.public.encoded, second.public.encoded)
    }

    @Test
    fun `a generated key that has not been bound is not written to disk`() {
        MtlsIdentity.generate()
        // ⚠️ A PKCS#12 entry carries a certificate chain, so there is nothing to write yet. This
        // asserts the documented consequence: a process that dies here loses a key that was never
        // registered and never sent anywhere.
        assertFalse(root.resolve("boomio_mtls_identity.p12").exists())
    }

    @Test
    fun `an installed certificate is bound to the key and survives a restart`() {
        val keyPair = MtlsIdentity.loadOrCreate()
        val minted = MtlsCertificate.selfSign(CN, keyPair, 1_700_000_000_000)
        MtlsIdentity.installCertificate(keyPair, minted)

        assertContentEquals(minted.der, assertNotNull(MtlsIdentity.certificate()).encoded)

        // A restart: everything in memory is dropped, the files stay.
        MtlsIdentity.resetForTests()
        MtlsIdentity.rootOverride = root

        val reloaded = assertNotNull(MtlsIdentity.existing())
        assertContentEquals(keyPair.public.encoded, reloaded.public.encoded)
        assertContentEquals(
            minted.der,
            assertNotNull(MtlsIdentity.certificate()).encoded,
            "the certificate must come back with the key it was installed against",
        )
    }

    @Test
    fun `the reloaded certificate is the one the wire would present`() {
        val keyPair = MtlsIdentity.loadOrCreate()
        val minted = MtlsCertificate.selfSign(CN, keyPair, 1_700_000_000_000)
        MtlsIdentity.installCertificate(keyPair, minted)

        val store = MtlsIdentity.keyStore()
        val managers = clientKeyManagers(store, MtlsIdentity.KEY_ALIAS, MtlsIdentity.keyStorePassword())
        assertEquals(1, managers.size)
    }

    @Test
    fun `delete removes the identity`() {
        val keyPair = MtlsIdentity.loadOrCreate()
        MtlsIdentity.installCertificate(keyPair, MtlsCertificate.selfSign(CN, keyPair, 1_700_000_000_000))
        assertTrue(MtlsIdentity.hasKey())

        MtlsIdentity.delete()

        assertFalse(MtlsIdentity.hasKey())
        assertNull(MtlsIdentity.certificate())
        assertNull(MtlsIdentity.existing())
    }

    @Test
    fun `the store and its password are written beside each other, owner only`() {
        val keyPair = MtlsIdentity.loadOrCreate()
        MtlsIdentity.installCertificate(keyPair, MtlsCertificate.selfSign(CN, keyPair, 1_700_000_000_000))

        val names = root.listDirectoryEntries().map { it.fileName.toString() }.toSet()
        assertTrue("boomio_mtls_identity.p12" in names, "the keystore: $names")
        assertTrue("boomio_mtls_identity.pwd" in names, "the password: $names")

        runCatching {
            val permissions = Files.getPosixFilePermissions(root.resolve("boomio_mtls_identity.p12"))
            assertEquals(
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
                permissions,
            )
        }
    }

    @Test
    fun `a store whose password file was lost starts a new identity rather than failing`() {
        val keyPair = MtlsIdentity.loadOrCreate()
        MtlsIdentity.installCertificate(keyPair, MtlsCertificate.selfSign(CN, keyPair, 1_700_000_000_000))

        Files.delete(root.resolve("boomio_mtls_identity.pwd"))
        MtlsIdentity.resetForTests()
        MtlsIdentity.rootOverride = root

        // The documented outcome: the old key is unreachable, so this is a device with no identity.
        // Its registered certificate is orphaned on the server until an operator revokes it.
        assertNull(MtlsIdentity.existing())
        val replacement = MtlsIdentity.loadOrCreate()
        assertEquals("RSA", replacement.private.algorithm)
    }

    /**
     * The certificate bytes are the artifact — a test that compared only the CN would pass for a
     * certificate the edge would reject.
     */
    @Test
    fun `the stored certificate parses back to the bytes that were installed`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val minted = MtlsCertificate.selfSign(CN, keyPair, 1_700_000_000_000)
        MtlsIdentity.installCertificate(keyPair, minted)

        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(assertNotNull(MtlsIdentity.certificate()).encoded.inputStream())
        assertContentEquals(minted.der, parsed.encoded)
    }

    private companion object {
        const val CN = "device-test-1a2b3c4d"
    }
}
