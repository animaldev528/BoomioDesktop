package com.nuvio.app.features.boomio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * The first-run pairing screen, rendered.
 *
 * ⚠️ **What is not tested here, and why.** The link itself is `BoomioSessionRepository`'s, and it
 * is covered by the suite around that object. This class only asserts the parts that are *this*
 * screen's: that the machine's entry point is reachable, that the skip exists, and that the manual
 * endpoint field is on the first frame rather than behind a failed walk.
 *
 * ⚠️ **Skip is the assertion that matters most.** Pairing is a property of one deployment, not of
 * Nuvio. A stock build has no `BOOMIO_COMPANION_URL`, so `startLink` refuses and every other path
 * on this screen is a dead end — without a working skip, the gate would be a lockout for anyone
 * running against an ordinary server.
 */
class DeviceSetupGateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `skipping reports itself`() {
        var skipped = false
        compose.setContent {
            NuvioTheme {
                DeviceSetupGate(onSkip = { skipped = true })
            }
        }

        compose.onNodeWithText("Skip for now").performClick()

        assertTrue(skipped, "the escape hatch must tell the gate to advance")
    }

    @Test
    fun `the idle link machine offers connect`() {
        compose.setContent {
            NuvioTheme {
                DeviceSetupGate(onSkip = {})
            }
        }

        compose.onNodeWithText("Connect").assertIsDisplayed()
    }

    /**
     * ⚠️ The server field is on the first frame, not behind the pairing flow. It is the only way
     * to say which box this install belongs to — the Windows build bakes its endpoints, so a user
     * handed a build aimed at the wrong server otherwise has no door at all. The field being
     * present (and enabled) is the whole point of the row.
     */
    @Test
    fun `the server field is offered before any pairing has happened`() {
        compose.setContent {
            NuvioTheme {
                DeviceSetupGate(onSkip = {})
            }
        }

        compose.onNodeWithText("Use this server").assertIsDisplayed()
    }
}
