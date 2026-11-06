package com.nuvio.app.core.overlay

import com.nuvio.app.features.boomio.BoomioSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trigger that makes pairing *sufficient*: a session appearing is what asks the server for an
 * assignment.
 *
 * ⚠️ **This is the regression test for the port's biggest hole.** For seven commits the desktop
 * could pair and could enroll, and nothing connected the two: `initialize()` applied a cached
 * assignment and stopped, so a first-run machine held a token, a device key and no request. Every
 * later layer — tunnel, relay, certificate — was correct and unreachable. The assertions below are
 * exactly the three cases that hole consisted of.
 *
 * The policy (`OverlayEnroller`) is covered by `OverlayEnrollmentTest`; this covers only the
 * decision to call it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayEnrollmentTriggerTest {

    @Test
    fun `a token appearing asks for enrollment once`() = runTest {
        val sessions = MutableSharedFlow<BoomioSession?>(replay = 1, extraBufferCapacity = 8)
        val seen = mutableListOf<String>()
        val job = watchCompanionSessions(this, sessions) { seen += it }
        runCurrent()

        sessions.emit(null)
        runCurrent()
        sessions.emit(session("bs_ses_first"))
        runCurrent()

        assertEquals(listOf("bs_ses_first"), seen)

        job.cancel()
    }

    /**
     * ⚠️ `adoptSession` re-publishes the session to record the user and display name pairing
     * returned — a new object every time, the same token. Enrolling again on that is a needless
     * POST, a needless cache write, and a needless trip through the certificate registrar.
     */
    @Test
    fun `re-publishing the same token does not enroll again`() = runTest {
        val sessions = MutableSharedFlow<BoomioSession?>(replay = 1, extraBufferCapacity = 8)
        val seen = mutableListOf<String>()
        val job = watchCompanionSessions(this, sessions) { seen += it }
        runCurrent()

        sessions.emit(session("bs_ses_first"))
        runCurrent()
        sessions.emit(session("bs_ses_first", userId = "user-1", displayName = "kyle"))
        runCurrent()

        assertEquals(listOf("bs_ses_first"), seen)

        job.cancel()
    }

    /** A re-link mints a genuinely new token, and that one must enroll. */
    @Test
    fun `a new token enrolls again`() = runTest {
        val sessions = MutableSharedFlow<BoomioSession?>(replay = 1, extraBufferCapacity = 8)
        val seen = mutableListOf<String>()
        val job = watchCompanionSessions(this, sessions) { seen += it }
        runCurrent()

        sessions.emit(session("bs_ses_first"))
        runCurrent()
        sessions.emit(session("bs_ses_second"))
        runCurrent()

        assertEquals(listOf("bs_ses_first", "bs_ses_second"), seen)

        job.cancel()
    }

    /**
     * ⚠️ No session means there is nothing to enroll *as*. Firing anyway would turn a normal
     * state — an install that has not paired — into a failed request on every launch.
     */
    @Test
    fun `no session never fires`() = runTest {
        val sessions = MutableSharedFlow<BoomioSession?>(replay = 1, extraBufferCapacity = 8)
        val seen = mutableListOf<String>()
        val job = watchCompanionSessions(this, sessions) { seen += it }
        runCurrent()

        sessions.emit(null)
        runCurrent()
        sessions.emit(null)
        runCurrent()

        assertEquals(emptyList(), seen)

        job.cancel()
    }

    private fun session(
        token: String,
        userId: String? = null,
        displayName: String? = null,
    ) = BoomioSession(
        token = token,
        deviceId = "device-under-test",
        userId = userId,
        displayName = displayName,
    )
}
