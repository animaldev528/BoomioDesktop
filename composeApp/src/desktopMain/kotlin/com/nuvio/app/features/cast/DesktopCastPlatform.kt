package com.nuvio.app.features.cast

/**
 * Desktop's half of casting: SSDP discovery on the LAN, plus the per-protocol
 * transports in `CastPlayback`.
 *
 * Installed from `Main.kt`. Nothing here holds state — [CastController] does that,
 * so a second player screen sees the same picker and the same active renderer.
 */
internal object DesktopCastPlatform : CastPlatform {

    /**
     * AirPlay receivers are dropped from the list rather than offered and then
     * refused. Reaching one needs a paired AirPlay 2 session (SRP + Curve25519 +
     * ChaCha20) that exists only as the phone's vendored `airkast` module, and the
     * AirPlay 1 `POST /play` this build could attempt was measured not to work
     * against current receivers. An entry that always fails is worse than no
     * entry; the phone remains the AirPlay sender.
     */
    override suspend fun discover(timeoutMillis: Long): List<CastDevice> =
        CastDiscovery.discover(timeoutMillis).filter { it.protocol != CastProtocol.AIRPLAY }

    override suspend fun play(device: CastDevice, url: String, title: String): CastPlayResult =
        playOnRenderer(device, url, title)

    override suspend fun stop(device: CastDevice): CastPlayResult = stopOnRenderer(device)
}
