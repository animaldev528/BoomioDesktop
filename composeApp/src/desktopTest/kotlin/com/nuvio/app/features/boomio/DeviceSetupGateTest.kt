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
     * ⚠️ The field is on the first frame, while the ladder is still `Idle` — that is
     * `alwaysOfferManual`, and it is the difference between an offer and a dead end. Desktop's
     * ladder has one rung and no discovery, so a machine whose enrollment never landed never
     * reaches `NeedsManual` on its own; if the field were tied to that state the user would have
     * nothing to type into and no way to get an address into the app.
     */
    @Test
    fun `the manual endpoint field is offered before any walk has failed`() {
        compose.setContent {
            NuvioTheme {
                DeviceSetupGate(onSkip = {})
            }
        }

        compose.onNodeWithText("Use this address").assertIsDisplayed()
    }
}
