package app.pulse.android.data

import app.pulse.android.models.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.LruCache
import java.util.concurrent.TimeUnit

object RadioBrowserApi {
    private const val BASE_URL = "https://all.api.radio-browser.info/json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Bounded in-memory LRU cache to prevent memory leaks and redundant network roundtrips
    private val searchCache = LruCache<String, List<RadioStation>>(60)

    @Serializable
    private data class RadioBrowserStation(
        val stationuuid: String = "",
        val name: String = "",
        val url_resolved: String = "",
        val country: String = "",
        val countrycode: String = "",
        val state: String = "",
        val language: String = "",
        val votes: Int = 0,
        val codec: String = "",
        val bitrate: Int = 0,
        val favicon: String = "",
        val tags: String = "",
        val geo_lat: Double? = null,
        val geo_long: Double? = null
    )

    private fun RadioBrowserStation.toDomain(): RadioStation {
        val streamUrl = url_resolved.ifBlank { "" }
        val stationTags = tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return RadioStation(
            id = "rb_$stationuuid",
            name = name.trim(),
            streamUrl = streamUrl,
            frequency = if (bitrate > 0) "$bitrate kbps" else codec.ifBlank { "Live" },
            city = state.trim(),
            state = state.trim(),
            country = country.trim().ifBlank { "Global" },
            countryCode = countrycode.trim(),
            language = language.trim().replaceFirstChar { it.uppercase() }.ifBlank { "Various" },
            lat = geo_lat ?: 0.0,
            lon = geo_long ?: 0.0,
            logoUrl = favicon.ifBlank { null },
            bitrate = bitrate.takeIf { it > 0 } ?: 128,
            tags = stationTags
        )
    }

    suspend fun getIndianStations(limit: Int = 40): List<RadioStation> = withContext(Dispatchers.IO) {
        val cacheKey = "in_stations_$limit"
        searchCache[cacheKey]?.let { return@withContext it }

        val url = "$BASE_URL/stations/search?countrycode=IN&hidebroken=true&order=votes&reverse=true&limit=$limit"
        fetchFromUrl(url).also { searchCache.put(cacheKey, it) }
    }

    suspend fun getGlobalStations(limit: Int = 40): List<RadioStation> = withContext(Dispatchers.IO) {
        val cacheKey = "global_stations_$limit"
        searchCache[cacheKey]?.let { return@withContext it }

        val url = "$BASE_URL/stations/search?hidebroken=true&order=votes&reverse=true&limit=$limit"
        fetchFromUrl(url).also { searchCache.put(cacheKey, it) }
    }

    suspend fun getStationsByCountry(countryCode: String, limit: Int = 40): List<RadioStation> = withContext(Dispatchers.IO) {
        val code = countryCode.trim().uppercase()
        if (code.isBlank()) return@withContext emptyList()
        val cacheKey = "country_${code}_$limit"
        searchCache[cacheKey]?.let { return@withContext it }

        val url = "$BASE_URL/stations/search?countrycode=$code&hidebroken=true&order=votes&reverse=true&limit=$limit"
        fetchFromUrl(url).also { searchCache.put(cacheKey, it) }
    }

    suspend fun search(query: String, limit: Int = 30): List<RadioStation> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val cacheKey = "search_${trimmed.lowercase()}"
        searchCache[cacheKey]?.let { return@withContext it }

        val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
        val url = "$BASE_URL/stations/byname/$encoded?limit=$limit&hidebroken=true&order=votes&reverse=true"
        fetchFromUrl(url).also { searchCache.put(cacheKey, it) }
    }

    suspend fun getStationsByLanguage(language: String, limit: Int = 30): List<RadioStation> = withContext(Dispatchers.IO) {
        val trimmed = language.trim()
        if (trimmed.isBlank() || trimmed.equals("All", ignoreCase = true)) return@withContext getIndianStations(limit)

        val cacheKey = "lang_${trimmed.lowercase()}"
        searchCache[cacheKey]?.let { return@withContext it }

        val encoded = java.net.URLEncoder.encode(trimmed.lowercase(), "UTF-8")
        val url = "$BASE_URL/stations/bylanguage/$encoded?limit=$limit&hidebroken=true&order=votes&reverse=true"
        fetchFromUrl(url).also { searchCache.put(cacheKey, it) }
    }

    private fun fetchFromUrl(url: String): List<RadioStation> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "VMusic-Radio/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyString = response.body?.string() ?: return emptyList()
                val rawStations: List<RadioBrowserStation> = json.decodeFromString(bodyString)
                rawStations
                    .filter { it.url_resolved.isNotBlank() && it.name.isNotBlank() }
                    .map { it.toDomain() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
