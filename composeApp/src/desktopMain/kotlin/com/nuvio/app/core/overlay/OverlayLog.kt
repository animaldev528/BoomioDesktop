package com.nuvio.app.core.overlay

import co.touchlab.kermit.Logger

/**
 * The desktop stand-in for `android.util.Log`.
 *
 * The overlay package is ported from Android, where every file logs through `Log.d(tag, …)`.
 * Rather than rewrite ~23 call sites (and drift from the mobile source that has to keep
 * compiling against them), the desktop port keeps the call shape and swaps the backend for the
 * app's existing Kermit logger. Nothing here makes a decision the logger does not already make —
 * it is a signature adapter, not a logging layer.
 */
internal object OverlayLog {
    fun v(tag: String, message: String) = Logger.withTag(tag).v { message }
    fun d(tag: String, message: String) = Logger.withTag(tag).d { message }
    fun i(tag: String, message: String) = Logger.withTag(tag).i { message }
    fun w(tag: String, message: String) = Logger.withTag(tag).w { message }
    fun w(tag: String, message: String, error: Throwable) =
        Logger.withTag(tag).w(error) { message }

    fun e(tag: String, message: String) = Logger.withTag(tag).e { message }
    fun e(tag: String, message: String, error: Throwable) =
        Logger.withTag(tag).e(error) { message }
}
