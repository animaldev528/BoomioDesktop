package com.nuvio.app.features.music

import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.companionRestBaseUrl
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Name that song", the client half.
 *
 * The work is done by bsc, not here: `POST /api/music/identify` fetches the
 * media the viewer is receiving, cuts a 12 s window around [MusicIdentifyRequest.positionMs]
 * with ffmpeg and runs it through Shazam (AudD as fallback). Nothing is
 * captured from the machine's sound card, so this works identically whether the
 * audio is going out of the speakers or into a headset — and it costs one small
 * JSON round trip instead of a native audio-capture path per platform.
 *
 * What the client *must* get right is the context, because a wrong one is
 * answered confidently rather than refused:
 *
 *  - [MusicIdentifyRequest.streamUrl] has to be a URL **bsc can fetch**. A
 *    `127.0.0.1` URL minted for a P2P/torrent stream points at this machine and
 *    is unreachable from the box, which answers `no_stream` rather than
 *    guessing. That is the honest outcome and it is surfaced as such.
 *  - [MusicIdentifyRequest.imdbId] must be the show/movie id, not an episode id,
 *    or the cue index and the saved provenance are keyed to nothing.
 *  - [MusicIdentifyRequest.audioTrack] is the **0-based ordinal among audio
 *    tracks**, which is what the player's own picker shows — not mpv's global
 *    track id and not ffprobe's stream index. Sending a global id makes the
 *    server warn `unresolved` and identify the wrong language.
 *
 * The response is deliberately a closed set of states instead of an error
 * string, because each one has a different sentence in the UI and two of them
 * ("no match", "rate limited") are not failures at all.
 */
sealed interface MusicIdentifyOutcome {
    /** A recognised track. [alreadySaved] is filled in by the caller's save step. */
    data class Found(val match: MusicTrackMatch) : MusicIdentifyOutcome

    /** The providers heard the window and did not recognise it. Try later in the film. */
    data object NoMatch : MusicIdentifyOutcome

    /** The server could not fetch the stream (local/P2P URL, or the stream is gone). */
    data object NoStream : MusicIdentifyOutcome

    /** No title context — a stream with no id, so there is nothing to key a cue to. */
    data object NoContext : MusicIdentifyOutcome

    /** Per-device daily cap reached. Not an error; the UI says "try again tomorrow". */
    data object RateLimited : MusicIdentifyOutcome

    /** Transport/auth/failure with the box. */
    data class Failed(val detail: String? = null) : MusicIdentifyOutcome
}

/** Everything the server needs to answer; the player supplies each field. */
data class MusicIdentifyRequest(
    val streamUrl: String?,
    val positionMs: Long?,
    val audioTrack: Int?,
    val imdbId: String?,
    val season: Int?,
    val episode: Int?,
)

/** The card's own state machine, so the composable holds no logic. */
sealed interface MusicIdentifyUiState {
    data object Idle : MusicIdentifyUiState
    data object Identifying : MusicIdentifyUiState
    data class Found(val match: MusicTrackMatch, val saving: Boolean = false, val saved: Boolean = false) :
        MusicIdentifyUiState

    data class Message(val kind: Kind) : MusicIdentifyUiState {
        enum class Kind { NO_MATCH, NO_STREAM, NO_CONTEXT, RATE_LIMITED, FAILED, UNAVAILABLE }
    }
}

object MusicIdentifyClient {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * True when the feature can work at all: a paired companion session (the
     * `bs_ses_*` bearer) and a box address. Without either the button is not
     * shown — an identify that 401s reads as a broken app, not a signed-out one.
     */
    fun isAvailable(): Boolean =
        BoomioConfig.companionRestBaseUrl.isNotBlank() &&
            !BoomioSessionRepository.bearerToken().isNullOrBlank()

    suspend fun identify(request: MusicIdentifyRequest): MusicIdentifyOutcome {
        val token = BoomioSessionRepository.bearerToken() ?: return MusicIdentifyOutcome.Failed(null)
        val base = BoomioConfig.companionRestBaseUrl
        if (base.isBlank()) return MusicIdentifyOutcome.Failed(null)

        val payload = buildJsonObject {
            put("deviceId", JsonPrimitive(SyncClientIdentity.currentClientId()))
            request.streamUrl?.takeIf { it.isNotBlank() }?.let { put("streamUrl", JsonPrimitive(it)) }
            request.positionMs?.takeIf { it >= 0 }?.let { put("positionMs", JsonPrimitive(it)) }
            request.audioTrack?.takeIf { it >= 0 }?.let { put("audioTrack", JsonPrimitive(it)) }
            request.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdbId", JsonPrimitive(it)) }
            request.season?.let { put("season", JsonPrimitive(it)) }
            request.episode?.let { put("episode", JsonPrimitive(it)) }
        }

        val response = try {
            httpRequestRaw(
                method = "POST",
                url = "$base/api/music/identify",
                headers = mapOf(
                    HttpHeaders.Authorization to "Bearer $token",
                    HttpHeaders.ContentType to "application/json",
                    HttpHeaders.Accept to "application/json",
                ),
                body = payload.toString(),
            )
        } catch (t: Throwable) {
            return MusicIdentifyOutcome.Failed(t.message)
        }

        when (response.status) {
            200, 429 -> Unit
            else -> return MusicIdentifyOutcome.Failed("HTTP ${response.status}")
        }

        val body = try {
            json.decodeFromString<IdentifyResponse>(response.body)
        } catch (t: Throwable) {
            return MusicIdentifyOutcome.Failed(t.message)
        }

        return when (body.status) {
            "ok" -> body.match?.let { MusicIdentifyOutcome.Found(it) } ?: MusicIdentifyOutcome.NoMatch
            "no_match" -> MusicIdentifyOutcome.NoMatch
            "no_stream" -> MusicIdentifyOutcome.NoStream
            "no_context" -> MusicIdentifyOutcome.NoContext
            "rate_limited" -> MusicIdentifyOutcome.RateLimited
            else -> MusicIdentifyOutcome.Failed(body.status)
        }
    }

    /**
     * Saves a recognised track into the signed-in user's library.
     *
     * Ownership is the *server's* to decide: bsc takes the user from the session
     * and refuses a session with no identity (`409 no_user_context`) rather than
     * guessing. So the request deliberately carries no user id. Provenance
     * (`imdbId`/season/episode/position) is decoration on the save — malformed
     * values are dropped server-side, never rejected.
     */
    suspend fun saveToLibrary(match: MusicTrackMatch, request: MusicIdentifyRequest): Result<Unit> {
        val token = BoomioSessionRepository.bearerToken()
            ?: return Result.failure(IllegalStateException("not signed in"))
        val base = BoomioConfig.companionRestBaseUrl
        if (base.isBlank()) return Result.failure(IllegalStateException("no server"))

        val payload = buildJsonObject {
            put("title", JsonPrimitive(match.title))
            match.artist?.let { put("artist", JsonPrimitive(it)) }
            match.album?.let { put("album", JsonPrimitive(it)) }
            match.isrc?.let { put("isrc", JsonPrimitive(it)) }
            match.artworkUrl?.let { put("artworkUrl", JsonPrimitive(it)) }
            match.provider?.let { put("provider", JsonPrimitive(it)) }
            match.providerTrackId?.let { put("providerTrackId", JsonPrimitive(it)) }
            request.imdbId?.let { put("imdbId", JsonPrimitive(it)) }
            request.season?.let { put("season", JsonPrimitive(it)) }
            request.episode?.let { put("episode", JsonPrimitive(it)) }
            request.positionMs?.let { put("positionMs", JsonPrimitive(it)) }
        }

        val response = try {
            httpRequestRaw(
                method = "POST",
                url = "$base/api/music/library",
                headers = mapOf(
                    HttpHeaders.Authorization to "Bearer $token",
                    HttpHeaders.ContentType to "application/json",
                    HttpHeaders.Accept to "application/json",
                ),
                body = payload.toString(),
            )
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        return if (response.status in 200..299) Result.success(Unit)
        else Result.failure(IllegalStateException("HTTP ${response.status}"))
    }

    /**
     * Pulls a Nuvio/IMDB id out of whatever the client calls an id.
     *
     * The player's ids are not uniform (`tt123`, `tt123:1:2`, `nuvio:movie:tt123`),
     * and the route accepts only `[A-Za-z0-9_.-]` — a colon is a 400 before the
     * request is even looked at. So the id is extracted here rather than sent
     * raw, and null is a valid answer: the server then says `no_context`.
     */
    fun imdbIdOf(vararg candidates: String?): String? {
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            IMDB_HINT.find(candidate)?.let { return it.value }
            if (candidate.matches(PLAIN_ID)) return candidate
        }
        return null
    }

    private val IMDB_HINT = Regex("""tt\d{5,}""")
    private val PLAIN_ID = Regex("""[A-Za-z0-9_.-]{1,64}""")
}

@Serializable
data class MusicTrackMatch(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val isrc: String? = null,
    val artworkUrl: String? = null,
    val previewUrl: String? = null,
    val provider: String? = null,
    val providerTrackId: String? = null,
    val confidence: Double? = null,
    val positionMs: Long? = null,
)

@Serializable
private data class IdentifyResponse(
    val status: String = "",
    val match: MusicTrackMatch? = null,
    val source: String? = null,
    @SerialName("cues_in_episode") val cuesInEpisode: Int = 0,
    val error: String? = null,
)
