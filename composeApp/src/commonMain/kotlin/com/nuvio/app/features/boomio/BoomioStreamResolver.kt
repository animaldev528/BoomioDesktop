package com.nuvio.app.features.boomio

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.cast.CastTarget
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import kotlinx.coroutines.CancellationException

/**
 * Resolves playback streams through the boomio media plane (`bsf`).
 *
 * boomio exposes:
 *   GET /find/:stremioId                    — movie lookup
 *   GET /find/:imdbId/:season/:episode      — episode lookup
 *
 * and returns a ranked list of Stremio-shaped streams whose `url` is a signed
 * `bsc` byte-range proxy URL. Each entry is mapped onto the app's existing
 * [StreamItem] model via the same [StreamParser] mapper the addon layer uses, so
 * the stream picker, autoplay selector and built-in player consume boomio streams
 * exactly like any addon stream.
 *
 * The seam is inert unless [BoomioConfig.boomioBaseUrl] is configured; the existing
 * addon/debrid resolvers remain the primary sources and are left untouched as
 * fallback.
 *
 * NOTE: static, uncompiled port of the legacy `BoomioStreamResolver`.
 */
object BoomioStreamResolver {
    private val log = Logger.withTag("BoomioStreamResolver")

    /** Group name under which boomio streams appear in the picker/autoplay. */
    const val ADDON_NAME = "Boomio"

    /**
     * Synthetic addon id for boomio streams. Intentionally not prefixed with
     * `addon:` so boomio streams are not treated as installed-addon streams
     * (their URLs are already direct/signed and need no local debrid resolve).
     */
    const val ADDON_ID = "boomio"

    /** True when the boomio seam is configured (BOOMIO_BASE_URL is set). */
    fun isEnabled(): Boolean = BoomioConfig.boomioBaseUrl.isNotBlank()

    /**
     * Calls boomio `/find` for [videoId] (the Stremio-style id, e.g. `tt1234567`
     * for a movie) with optional [season]/[episode], and returns the resolved
     * streams in boomio's ranked order.
     *
     * Returns an empty list when the seam is disabled, the id is blank, the
     * request fails, or boomio returns no streams.
     */
    suspend fun resolve(videoId: String?, season: Int?, episode: Int?): List<StreamItem> {
        if (!isEnabled()) return emptyList()
        val id = videoId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            resolveUnsafe(id, season, episode)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            log.w(error) { "boomio /find failed for $id" }
            emptyList()
        }
    }

    /**
     * Resolves the stream list **for a cast target** rather than for this machine.
     *
     * ⚠️ A cast is a different `/find` call, not the same URL handed to a TV. The
     * streams this machine is playing are bound to this machine's address and
     * carry their credential in a header; a television sends no headers of its
     * own, and bsf builds the list for whoever the request names. So these three
     * `target*` parameters are the difference between "the renderer gets a 403"
     * and "the renderer plays": bsf binds the stream to the target's address,
     * drops the device binding and moves the stream token into the URL query.
     *
     * The list comes back in bsf's ranking *for that screen*, so a 1080p stick is
     * never offered a 4K stream it would open and then fail to decode.
     */
    suspend fun resolveForCast(
        videoId: String?,
        season: Int?,
        episode: Int?,
        target: CastTarget,
    ): List<StreamItem> {
        if (!isEnabled()) return emptyList()
        val id = videoId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val url = buildFindUrl(id, season, episode) ?: return emptyList()
            val castUrl = if (url.contains('?')) {
                "$url&${target.queryString()}"
            } else {
                "$url?${target.queryString()}"
            }
            val payload = httpGetText(castUrl)
            StreamParser.parse(
                payload = payload,
                addonName = ADDON_NAME,
                addonId = ADDON_ID,
                addonLogo = null,
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            log.w(error) { "boomio /find for cast target ${target.ip} failed for $id" }
            emptyList()
        }
    }

    private suspend fun resolveUnsafe(
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        val url = buildFindUrl(videoId, season, episode) ?: return emptyList()
        val payload = httpGetText(url)
        return StreamParser.parse(
            payload = payload,
            addonName = ADDON_NAME,
            addonId = ADDON_ID,
            addonLogo = null,
        )
    }

    /**
     * Builds the `/find` URL from [BoomioConfig.boomioBaseUrl]. Episodes use the
     * explicit `/find/:imdbId/:season/:episode` route (which requires an IMDB id);
     * everything else uses `/find/:stremioId`, which boomio normalizes (it accepts
     * `tt…` and `tmdb:…` ids and infers the type from the presence of a `:`).
     */
    private fun buildFindUrl(videoId: String, season: Int?, episode: Int?): String? {
        val base = BoomioConfig.boomioBaseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            log.w { "boomio: invalid BOOMIO_BASE_URL" }
            return null
        }
        val segments = mutableListOf("find")
        if (season != null && episode != null) {
            val imdbId = videoId.substringBefore(":")
            if (!imdbId.startsWith("tt")) return null
            segments += imdbId
            segments += season.toString()
            segments += episode.toString()
        } else {
            segments += videoId
        }
        return buildString {
            append(base)
            segments.forEach { segment -> append('/').append(segment) }
        }
    }
}
