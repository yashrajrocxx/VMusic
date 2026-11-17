package app.pulse.android.betterlyrics

import app.pulse.android.betterlyrics.models.TTMLResponse
import app.pulse.android.utils.LyricsRomanizer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Better Lyrics (https://docs.betterlyrics.org) remains the preferred
 * synchronized-lyrics provider: it returns word-level TTML and, when the
 * provider has it, a romanized line (`x-roman`). The legacy host is kept as a
 * fallback so a single domain problem cannot take lyrics down.
 */
object BetterLyrics {
    private const val TAG = "BetterLyrics"

    private val bases = listOf(
        "https://api.betterlyrics.org",
        "https://lyrics-api.boidu.dev"
    )

    data class ParsedLyrics(
        val plain: String,
        val synced: String,
        val romanizedPlain: String?,
        val romanizedSynced: String?
    )

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }

            expectSuccess = false
        }
    }

    private suspend fun fetchTTML(
        artist: String,
        title: String,
        duration: Int = -1,
        album: String? = null,
    ): String? {
        for (base in bases) {
            val ttml = runCatching {
                val response = client.get("$base/getLyrics") {
                    headers.append(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    headers.append("Accept", "application/json")
                    parameter("s", title)
                    parameter("a", artist)
                    if (duration > 0) {
                        parameter("d", duration)
                    }
                    if (!album.isNullOrBlank()) {
                        parameter("al", album)
                    }
                }
                if (response.status != HttpStatusCode.OK) {
                    Timber.tag(TAG).w("$base returned ${response.status} for $title")
                    return@runCatching null
                }
                response.body<TTMLResponse>().ttml?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).w(error, "Lyrics request failed on $base for $title")
                null
            }

            if (ttml != null) return ttml
        }
        return null
    }

    /**
     * One provider call gives us synchronized, plain and (when the provider
     * supplies `x-roman`) romanized versions, so the whole fallback chain never
     * has to request the same song twice.
     */
    suspend fun getParsedLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): ParsedLyrics? = runCatching {
        val ttml = fetchTTML(artist, title, duration, album) ?: return@runCatching null
        val lines = TTMLParser.parseTTML(ttml)
        if (lines.isEmpty()) return@runCatching null

        val synced = TTMLParser.toLRC(lines).takeIf { LyricsRomanizer.isSynced(it) }
            ?: return@runCatching null
        val plain = lines
            .joinToString("\n") { it.text }
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return@runCatching null

        val romanizedLines = lines.map { line ->
            val romanized = line.romanizedText?.takeIf { it.isNotBlank() }
                ?: LyricsRomanizer.romanize(line.text)
            if (romanized == null) line else line.copy(
                text = romanized,
                words = emptyList(),
                backgroundLines = line.backgroundLines.map { background ->
                    val backgroundRomanized =
                        background.romanizedText?.takeIf { it.isNotBlank() }
                            ?: LyricsRomanizer.romanize(background.text)
                    if (backgroundRomanized == null) background
                    else background.copy(text = backgroundRomanized, words = emptyList())
                }
            )
        }

        val hasRomanization = romanizedLines.zip(lines).any { (romanized, original) ->
            romanized.text != original.text
        }
        if (!hasRomanization) {
            return@runCatching ParsedLyrics(
                plain = plain,
                synced = synced,
                romanizedPlain = null,
                romanizedSynced = null
            )
        }

        val romanizedPlain = romanizedLines
            .joinToString("\n") { it.text }
            .trim()
            .takeIf { it.isNotBlank() && it != plain }

        ParsedLyrics(
            plain = plain,
            synced = synced,
            romanizedPlain = romanizedPlain,
            romanizedSynced = TTMLParser.toLRC(romanizedLines)
        )
    }.onFailure {
        if (it is CancellationException) throw it
        Timber.tag(TAG).e(it, "Failed to parse TTML for $title by $artist")
    }.getOrNull()
}
