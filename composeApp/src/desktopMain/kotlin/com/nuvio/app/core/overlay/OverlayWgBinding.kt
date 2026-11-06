package com.nuvio.app.core.overlay

import com.nuvio.app.core.storage.DesktopCache
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The desktop view of `overlaywg` — the same eight-call seam mobile has over the AAR.
 *
 * ⚠️ **The interface is deliberately identical to Android's**, because everything above it
 * (relay, tunnel, DNS) is shared logic rather than platform code. What differs is only how the
 * library is reached: mobile loads a `gomobile bind` AAR, desktop loads a `-buildmode=c-shared`
 * `.so`/`.dylib`/`.dll` through JNA. The Go package is the same source in both cases.
 *
 * The behaviours the port must preserve, taken from the Android contract:
 *   - [read] returns **-1** at end of stream and never throws;
 *   - [write] may accept fewer bytes than asked and never throws;
 *   - [dial], [up], [down] and [close] report real failures by throwing;
 *   - [close] of an unknown handle is not an error.
 */
internal interface OverlayWgBinding {

    fun up(
        privateKeyHex: String,
        peerPublicKeyHex: String,
        endpoint: String,
        localCidr: String,
        allowedIps: String,
        dns: String,
        mtu: Int,
        keepalive: Int,
    )

    fun down()

    fun status(): String

    fun generateKeypair(): OverlayWgKeypair

    fun dial(host: String, port: Int): Long

    fun read(id: Long, buffer: ByteArray, offset: Int, length: Int): Int

    fun write(id: Long, buffer: ByteArray, offset: Int, length: Int): Int

    fun close(id: Long)

    fun lastError(): String
}

internal data class OverlayWgKeypair(
    val privateKeyBase64: String,
    val publicKeyBase64: String,
    val privateKeyHex: String,
    val publicKeyHex: String,
)

internal class OverlayWgException(message: String) : Exception(message)

/** The JNA declarations; names match the C symbols exported by `overlaywg/cshared`. */
private interface OverlayWgNative : Library {
    fun OverlayWg_GenerateKeypair(): Pointer?
    fun OverlayWg_Up(
        privateKeyHex: String,
        peerPublicKeyHex: String,
        endpoint: String,
        localCidr: String,
        allowedIps: String,
        dns: String,
        mtu: Int,
        keepalive: Int,
    ): Pointer?

    fun OverlayWg_Down(): Pointer?
    fun OverlayWg_Status(): Pointer?
    fun OverlayWg_Dial(host: String, port: Int, handle: LongByReference): Pointer?
    fun OverlayWg_Read(id: Long, buffer: Pointer, offset: Int, length: Int): Int
    fun OverlayWg_Write(id: Long, buffer: Pointer, offset: Int, length: Int): Int
    fun OverlayWg_Close(id: Long): Int
    fun OverlayWg_LastError(): Pointer?
    fun OverlayWg_Free(value: Pointer?)
}

internal object OverlayWgBindingProvider {
    /** Resolved lazily, so a build that never opens the tunnel never loads the library. */
    val binding: OverlayWgBinding by lazy { JnaOverlayWgBinding() }
}

internal class JnaOverlayWgBinding(
    private val library: File = OverlayWgLibrary.resolve(),
) : OverlayWgBinding {

    private val native: OverlayWgNative =
        Native.load(library.absolutePath, OverlayWgNative::class.java)

    /**
     * One scratch buffer per thread.
     *
     * ⚠️ **Thread-local, not shared.** The relay reads on one thread and writes on another for
     * every open connection, and several connections overlap; a single shared buffer would let
     * one stream's bytes land in another's read. A thread-local costs one allocation per relay
     * worker and none per call after the first.
     */
    private val scratch = ThreadLocal.withInitial { Memory(INITIAL_SCRATCH_BYTES) }

    override fun up(
        privateKeyHex: String,
        peerPublicKeyHex: String,
        endpoint: String,
        localCidr: String,
        allowedIps: String,
        dns: String,
        mtu: Int,
        keepalive: Int,
    ) {
        val error = takeString(
            native.OverlayWg_Up(
                privateKeyHex, peerPublicKeyHex, endpoint, localCidr, allowedIps, dns, mtu, keepalive,
            ),
        )
        if (error != null) throw OverlayWgException(error)
    }

    override fun down() {
        val error = takeString(native.OverlayWg_Down())
        if (error != null) throw OverlayWgException(error)
    }

    override fun status(): String = takeString(native.OverlayWg_Status()).orEmpty()

    override fun generateKeypair(): OverlayWgKeypair {
        val raw = takeString(native.OverlayWg_GenerateKeypair())
            ?: throw OverlayWgException("overlaywg could not mint a keypair")
        val parts = raw.split('|')
        if (parts.size != 4) {
            throw OverlayWgException("overlaywg keypair reply was malformed")
        }
        return OverlayWgKeypair(
            privateKeyBase64 = parts[0],
            publicKeyBase64 = parts[1],
            privateKeyHex = parts[2],
            publicKeyHex = parts[3],
        )
    }

    override fun dial(host: String, port: Int): Long {
        // The handle comes back through an out-parameter rather than as the return value, so a
        // failure can carry the Go error verbatim. A -1 sentinel plus `lastError()` would lose
        // the pre-up "tunnel is not up" message, which the Go package does not record.
        val handle = LongByReference(-1L)
        val error = takeString(native.OverlayWg_Dial(host, port, handle))
        if (error != null) {
            throw OverlayWgException("dial $host:$port through the overlay failed: $error")
        }
        return handle.value
    }

    override fun read(id: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "read window $offset+$length exceeds buffer ${buffer.size}"
        }
        val memory = scratchFor(offset + length)
        val read = native.OverlayWg_Read(id, memory, offset, length)
        if (read > 0) {
            memory.read(offset.toLong(), buffer, offset, read)
        }
        return read
    }

    override fun write(id: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "write window $offset+$length exceeds buffer ${buffer.size}"
        }
        val memory = scratchFor(offset + length)
        memory.write(offset.toLong(), buffer, offset, length)
        return native.OverlayWg_Write(id, memory, offset, length)
    }

    override fun close(id: Long) {
        // Closing a handle that is already gone is explicitly not an error, so the -1 the shim
        // returns on a real failure is only useful for diagnostics and never for control flow.
        native.OverlayWg_Close(id)
    }

    override fun lastError(): String = takeString(native.OverlayWg_LastError()).orEmpty()

    private fun scratchFor(size: Int): Memory {
        val current = scratch.get()
        if (current.size() >= size.toLong()) return current
        var capacity = current.size()
        while (capacity < size) capacity *= 2
        val grown = Memory(capacity)
        scratch.set(grown)
        return grown
    }

    /** Copies a C string out and releases it; NULL means "no message", not an empty one. */
    private fun takeString(pointer: Pointer?): String? {
        if (pointer == null) return null
        return try {
            pointer.getString(0)
        } finally {
            native.OverlayWg_Free(pointer)
        }
    }

    private companion object {
        const val INITIAL_SCRATCH_BYTES = 32L * 1024L
    }
}

/**
 * Finds the bundled library, in the same order [com.nuvio.app.features.p2p.DesktopEngineLibrary]
 * does: a packaged install, then the source tree (development), then a classpath resource
 * extracted into the cache (a jar without packaging).
 *
 * A `boomio.overlaywg.library` system property wins over all of them, so a developer can point
 * the app at a freshly built `.so` without touching the tree — which is exactly what the
 * `overlaywg/build-desktop.sh` build does on a machine without the packaging pipeline.
 */
internal object OverlayWgLibrary {
    fun resolve(): File {
        val platform = platformKey()
        val name = libraryName(platform)
        System.getProperty(LIBRARY_OVERRIDE)
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let { return it }
        packagedLibrary(platform, name)?.let { return it }
        sourceLibrary(platform, name)?.let { return it }
        extractedLibrary(platform, name)?.let { return it }
        throw OverlayWgException(
            "The overlay WireGuard library is not bundled for $platform. " +
                "Build it with overlaywg/build-desktop.sh, or set -D$LIBRARY_OVERRIDE=/path/to/$name.",
        )
    }

    internal fun libraryName(platform: String): String = when (platform) {
        "macos" -> "liboverlaywg.dylib"
        "windows" -> "overlaywg.dll"
        else -> "liboverlaywg.so"
    }

    private fun platformKey(): String {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        return when {
            osName.contains("mac") -> "macos"
            osName.contains("win") -> "windows"
            osName.contains("linux") -> "linux"
            else -> throw OverlayWgException("The overlay is not supported on '$osName'")
        }
    }

    private fun architectureKey(): String {
        val architecture = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
        return when (architecture) {
            "amd64", "x86_64", "x64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> architecture
        }
    }

    private fun packagedLibrary(platform: String, name: String): File? =
        System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let { File(it, "native/$platform/$name") }
            ?.takeIf { it.isFile }

    private fun sourceLibrary(platform: String, name: String): File? =
        listOf(
            "src/desktopMain/native/$platform/overlaywg/$name",
            "composeApp/src/desktopMain/native/$platform/overlaywg/$name",
            "overlaywg/build/$platform-${architectureKey()}/$name",
            // The module-relative variants above cover a run from the repository root. Gradle
            // runs `desktopTest` with the *module* as the working directory, so the source tree
            // only resolves through this one — the same pair `OverlayWgBindingSmokeTest` carries.
            "../overlaywg/build/$platform-${architectureKey()}/$name",
        ).map(::File).firstOrNull { it.isFile }

    private fun extractedLibrary(platform: String, name: String): File? {
        val resource = OverlayWgLibrary::class.java.getResource("/native/$platform/$name")
            ?: return null
        return try {
            DesktopCache.installVersionedResources("overlaywg/$platform", mapOf(name to resource))
                .resolve(name)
                .toFile()
                .takeIf { it.isFile }
        } catch (error: IOException) {
            throw OverlayWgException("Could not extract $name into the cache: ${error.message}")
        }
    }

    private const val LIBRARY_OVERRIDE = "boomio.overlaywg.library"
}
