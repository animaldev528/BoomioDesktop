package com.nuvio.app.features.cast

/**
 * What a cast attempt did.
 *
 * [reason] names the step that failed ("no AVTransport service", "HTTP 500 from
 * Play") rather than saying "cast failed", because the only person who can act
 * on it is the one holding the remote at the television.
 */
data class CastPlayResult(val ok: Boolean, val reason: String? = null) {
    companion object {
        val Ok = CastPlayResult(ok = true)
        fun failed(reason: String) = CastPlayResult(ok = false, reason = reason)
    }
}

// A renderer sends no headers, so a cast URL must carry its own credential. That
// is a property of how the URL is minted — `/find` with the `target*` parameters
// — rather than something to check here: `CastTarget.queryString()` is what makes
// bsf put the stream token in the query, and `BoomioStreamResolver.resolveForCast`
// is the only path that produces a cast URL.
