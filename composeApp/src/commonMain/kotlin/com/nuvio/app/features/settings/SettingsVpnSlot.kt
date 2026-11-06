package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.compositionLocalOf

/**
 * The platform's VPN page body, supplied from above; `null` means this platform has no such page.
 *
 * ⚠️ **Why a local rather than a parameter, when the pairing gate next door used a parameter.**
 * `SettingsScreen` is six composables deep and two of them take ninety arguments; threading one more
 * through all of them would be a mechanical edit with no information in it, and the two places that
 * need the value — the row that opens the page and the `when (page)` that renders it — are already
 * inside this package. The pairing gate could take a parameter because it is reached through
 * `App` and `AppGate`, two functions whose signatures exist for exactly this kind of supply.
 *
 * ⚠️ **Null is the default and it is load-bearing.** `AppGateOverlay`, the Android and the iOS
 * entry points all provide nothing, so `SettingsPage.Vpn` is a page they cannot reach and their
 * settings tree is byte-identical to what it was. The desktop `main()` provides the body — the
 * overlay package it calls is `desktopMain`-only, which is why this cannot be a `commonMain` screen
 * the way mobile's is.
 */
internal val LocalVpnSettingsContent =
    compositionLocalOf<(LazyListScope.(isTablet: Boolean) -> Unit)?> { null }
