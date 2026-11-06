package com.nuvio.app.core.overlay

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Exercises the real `c-shared` library through the JNA seam, not a fake.
 *
 * ⚠️ **The Android rule this test inherits is that the seam is where every testable decision
 * lives — but the binding itself is the one thing a fake cannot check.** The C ABI is a second
 * calling convention over the same Go package, and a wrong signature there is invisible to the
 * Kotlin compiler: it fails at native call time. This runs the three calls that need no network
 * (keygen, status, and dial-before-up) so a broken symbol, a mismatched `int` width, or a string
 * that is never freed shows up here instead of on a user's first enrolment.
 *
 * Skipped when the library has not been built, so a machine without Go still runs the suite.
 */
class OverlayWgBindingSmokeTest {

    private fun builtLibrary(): File? {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val platform = when {
            osName.contains("mac") -> "macos"
            osName.contains("win") -> "windows"
            else -> "linux"
        }
        val architecture = System.getProperty("os.arch").orEmpty().lowercase()
            .let { if (it == "x86_64" || it == "x64") "amd64" else it }
        val name = when (platform) {
            "macos" -> "liboverlaywg.dylib"
            "windows" -> "overlaywg.dll"
            else -> "liboverlaywg.so"
        }
        return listOf(
            File("../overlaywg/build/$platform-$architecture/$name"),
            File("overlaywg/build/$platform-$architecture/$name"),
        ).firstOrNull { it.isFile }
    }

    private fun bindingOrNull(): OverlayWgBinding? =
        builtLibrary()?.let { JnaOverlayWgBinding(it) }

    @Test
    fun keypairIsWellFormedInBothEncodings() {
        val binding = bindingOrNull() ?: return
        val keypair = binding.generateKeypair()

        assertEquals(32, Base64.getDecoder().decode(keypair.privateKeyBase64).size)
        assertEquals(32, Base64.getDecoder().decode(keypair.publicKeyBase64).size)
        assertEquals(64, keypair.privateKeyHex.length)
        assertEquals(64, keypair.publicKeyHex.length)
        assertEquals(keypair.privateKeyBase64, Base64.getEncoder().encodeToString(hexToBytes(keypair.privateKeyHex)))
        assertEquals(keypair.publicKeyBase64, Base64.getEncoder().encodeToString(hexToBytes(keypair.publicKeyHex)))
        assertTrue(keypair.publicKeyBase64 != keypair.privateKeyBase64)
    }

    @Test
    fun statusReadsBackThroughAStringReturn() {
        val binding = bindingOrNull() ?: return
        binding.down()
        assertTrue(binding.status().contains("not up"), "expected the down state, got '${binding.status()}'")
    }

    @Test
    fun dialBeforeUpFailsWithTheGoReason() {
        val binding = bindingOrNull() ?: return
        binding.down()
        val error = assertFailsWith<OverlayWgException> { binding.dial("10.77.0.1", 443) }
        assertTrue(error.message.orEmpty().contains("not up"), "unexpected reason: ${error.message}")
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
