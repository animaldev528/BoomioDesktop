package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * That the VPN row exists exactly when a platform has a VPN page to open.
 *
 * ⚠️ **What is not tested here, and why.** The page body is `desktopMain` and reads
 * `OverlayTunnelControl`, which starts the tunnel controller — a unit test that rendered it would
 * be starting a WireGuard binding, not asserting a layout. What *is* this port's risk is the
 * gate: a row that opens an empty page, or a page nobody can reach, is the failure mode a
 * composition-local supply invites, and both are visible from here.
 *
 * The row is the whole of the contract `settingsRootContent` has with
 * [LocalVpnSettingsContent]'s callers — the null callback means "this platform has no such page",
 * which is what every Android, iOS and stock-desktop build passes.
 */
class VpnSettingsRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a supplied page gets a row that opens it`() {
        var opened = false
        compose.setContent {
            NuvioTheme {
                LazyColumn {
                    settingsRootContent(
                        isTablet = false,
                        onPlaybackClick = {},
                        onAppearanceClick = {},
                        onAdvancedClick = {},
                        onNotificationsClick = {},
                        onContentDiscoveryClick = {},
                        onIntegrationsClick = {},
                        onTrackingClick = {},
                        onSupportersContributorsClick = {},
                        onLicensesAttributionsClick = {},
                        onAccountClick = {},
                        onVpnClick = { opened = true },
                        showAccountSection = false,
                        showAboutSection = false,
                        showAdvancedSection = false,
                        showAttribution = false,
                    )
                }
            }
        }

        compose.onNodeWithText("VPN").assertIsDisplayed().performClick()

        assertTrue(opened, "the row must open the page it advertises")
    }

    /**
     * ⚠️ The default is the assertion that matters for everyone else: no callback means no row, so
     * every platform without a tunnel keeps the settings tree it had.
     */
    @Test
    fun `no page means no row`() {
        compose.setContent {
            NuvioTheme {
                LazyColumn {
                    settingsRootContent(
                        isTablet = false,
                        onPlaybackClick = {},
                        onAppearanceClick = {},
                        onAdvancedClick = {},
                        onNotificationsClick = {},
                        onContentDiscoveryClick = {},
                        onIntegrationsClick = {},
                        onTrackingClick = {},
                        onSupportersContributorsClick = {},
                        onLicensesAttributionsClick = {},
                        onAccountClick = {},
                        showAccountSection = false,
                        showAboutSection = false,
                        showAdvancedSection = false,
                        showAttribution = false,
                    )
                }
            }
        }

        compose.onNodeWithText("VPN").assertDoesNotExist()
    }
}
