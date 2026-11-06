package com.nuvio.app.core.auth

import com.nuvio.app.core.device.DeviceHardwareFingerprint
import java.net.InetAddress

internal actual fun currentDeviceClientMetadata(): DeviceClientMetadata {
    val osName = System.getProperty("os.name").orEmpty().trim().ifBlank { "Desktop" }
    val osVersion = System.getProperty("os.version").orEmpty().trim()
    val deviceName = sequenceOf(
        System.getenv("COMPUTERNAME"),
        System.getenv("HOSTNAME"),
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
    )
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: "$osName device"
    val platform = listOf(osName, osVersion)
        .filter(String::isNotBlank)
        .joinToString(" ")

    return DeviceClientMetadata(
        clientName = "Nuvio Desktop",
        deviceName = deviceName,
        platform = platform,
        // The same value an enrolment must carry, since bsm resolves the row by fingerprint
        // before it falls back to the install id.
        hwFingerprint = DeviceHardwareFingerprint.value(),
    )
}
