package com.nuvio.app.features.boomio

import com.nuvio.app.core.storage.DesktopStorage

/**
 * Desktop actual of [BoomioSessionStorage], backed by the same `.properties` store every other
 * desktop preference uses.
 *
 * ⚠️ **The token is a bearer credential and this store is plaintext on disk.** That matches the
 * other desktop stores (`AuthStorage`, the debrid and tracker key stores) and is a deliberate
 * first-pass trade-off rather than an oversight: the OS-keychain upgrade belongs with the mTLS
 * identity work, which has to touch a keychain/keystore abstraction anyway. Do not ship a
 * signed build to a shared machine before that lands.
 */
internal actual object BoomioSessionStorage {
    private val store = DesktopStorage.store("boomio_session")

    actual fun loadSessionToken(): String? =
        store.getString(KEY_SESSION_TOKEN)?.takeIf { it.isNotBlank() }

    actual fun saveSessionToken(token: String) {
        store.putString(KEY_SESSION_TOKEN, token)
    }

    actual fun clearSessionToken() {
        store.remove(KEY_SESSION_TOKEN)
    }

    actual fun loadPairedDeviceId(): String? =
        store.getString(KEY_PAIRED_DEVICE_ID)?.takeIf { it.isNotBlank() }

    actual fun savePairedDeviceId(deviceId: String) {
        store.putString(KEY_PAIRED_DEVICE_ID, deviceId)
    }

    actual fun clearPairedDeviceId() {
        store.remove(KEY_PAIRED_DEVICE_ID)
    }

    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_PAIRED_DEVICE_ID = "paired_device_id"
}
