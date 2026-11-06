package com.nuvio.app.core.device

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The desktop counterpart of mobile's `DeviceHardwareFingerprint`.
 *
 * ⚠️ **The value has to be stable across launches, and the same one on both doors.** bsm looks a
 * device's row up by fingerprint *first*, for enrolment and for the capability report alike, so a
 * desktop pairing that carried a different value (or none) inserts a second row for a machine that
 * already has one — the duplicate-row defect mobile closed on 2026-10-13.
 *
 * It is derived from the OS machine id, hashed the same way mobile hashes `ANDROID_ID` (SHA-256 of
 * salt + raw, first 32 hex chars), so both platforms produce the same *shape* of opaque string:
 *
 *   - Linux — `/etc/machine-id`, falling back to `/var/lib/dbus/machine-id`
 *   - macOS — `IOPlatformUUID` from `ioreg`
 *   - Windows — `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid` via `reg`
 *
 * Where none of those is readable the value falls back to a random id generated once and kept in
 * [DesktopStorage]. That is stable per install but does **not** survive an app-data wipe, which is
 * strictly worse than the machine id and the same trade-off Android makes for a legacy device.
 *
 * Never blank: unlike mobile (where a missing `ANDROID_ID` means "omit the field"), the fallback
 * always produces a value, so the pairing payload always carries one.
 */
internal object DeviceHardwareFingerprint {
    private const val FP_SALT = "boomio-desktop-fp-v1"
    private const val MACHINE_ID_TIMEOUT_SECONDS = 2L

    private val store = DesktopStorage.store("boomio_device_identity")

    private val cached: String by lazy { derive() }

    fun value(): String = cached

    private fun derive(): String {
        val raw = machineId() ?: persistentFallbackId()
        return MessageDigest.getInstance("SHA-256")
            .digest((FP_SALT + raw).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
            .take(32)
    }

    private fun machineId(): String? {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val raw = when {
            osName.contains("mac") -> commandOutput("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                ?.lineSequence()
                ?.firstOrNull { it.contains("IOPlatformUUID") }
                ?.substringAfterLast('=')
                ?.trim()
                ?.trim('"')
            osName.contains("win") -> commandOutput(
                "reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid",
            )
                ?.lineSequence()
                ?.firstOrNull { it.contains("MachineGuid") }
                ?.substringAfter("MachineGuid")
                ?.trim()
            else -> sequenceOf("/etc/machine-id", "/var/lib/dbus/machine-id")
                .mapNotNull { path ->
                    runCatching { Files.readString(Path.of(path)).trim() }.getOrNull()
                }
                .firstOrNull { it.isNotBlank() }
        }
        return raw?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun persistentFallbackId(): String {
        store.getString(KEY_FALLBACK_MACHINE_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        store.putString(KEY_FALLBACK_MACHINE_ID, generated)
        return generated
    }

    /** Best-effort; a missing binary, a hang, or a non-zero exit all read as "unavailable". */
    private fun commandOutput(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(MACHINE_ID_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private const val KEY_FALLBACK_MACHINE_ID = "fallback_machine_id"
}
