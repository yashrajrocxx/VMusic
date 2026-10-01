package app.pulse.android.data

import app.pulse.android.models.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Radio Garden directory (radio.garden): real local stations per city,
 * resolved to playable streams. Unofficial API — no SLA — so every call
 * is best-effort and the radio tab always falls back to the curated list
 * and radio-browser. Only secure (https) channels are surfaced: the app
 * blocks cleartext traffic, so http streams could never play on-device.
 */
object RadioGardenApi {
    private const val BASE_URL = "https://radio.garden/api/ara/content"
    private const val PLACES_TTL_MS = 30L * 24 * 60 * 60 * 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class PlacesResponse(val data: PlacesData = PlacesData())

    @Serializable
    private data class PlacesData(val list: List<GardenPlace> = emptyList())

    @Serializable
    data class GardenPlace(
        val id: String = "",
        val title: String = "",
        val country: String = "",
        val size: Int = 0
    )

    @Serializable
    private data class PageResponse(val data: PageData? = null)

    @Serializable
    private data class PageData(val content: List<ContentGroup> = emptyList())

    @Serializable
    private data class ContentGroup(val items: List<ChannelItem> = emptyList())

    @Serializable
    private data class ChannelItem(val page: ChannelPage? = null)

    @Serializable
    private data class ChannelPage(
        val url: String = "",
        val title: String = "",
        val place: PlaceRef? = null,
        val secure: Boolean = false
    )

    @Serializable
    private data class PlaceRef(
        val id: String = "",
        val title: String = ""
    )

    fun listenUrl(channelId: String) = "$BASE_URL/listen/$channelId/channel.mp3"

    @Volatile
    private var placesMemory: List<GardenPlace>? = null

    private fun placesFile(cacheDir: File) = File(cacheDir, "radio_garden_places.json")

    suspend fun getPlaces(cacheDir: File): List<GardenPlace> = withContext(Dispatchers.IO) {
        placesMemory?.let { return@withContext it }

        val file = placesFile(cacheDir)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < PLACES_TTL_MS) {
            runCatching {
                val cached: PlacesResponse = json.decodeFromString(file.readText())
                cached.data.list.takeIf { it.isNotEmpty() }
            }.getOrNull()?.let {
                placesMemory = it
                return@withContext it
            }
        }

        val fresh = runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/places")
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val parsed: PlacesResponse = json.decodeFromString(response.body.string())
                parsed.data.list.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()

        if (fresh != null) {
            placesMemory = fresh
            runCatching {
                file.writeText(json.encodeToString(PlacesResponse.serializer(), PlacesResponse(PlacesData(fresh))))
            }
            fresh
        } else {
            // Stale disk beats nothing: an expired list still resolves cities.
            runCatching {
                val stale: PlacesResponse = json.decodeFromString(file.readText())
                stale.data.list
            }.getOrDefault(emptyList())
        }
    }

    /** Exact city match, preferring Indian places and bigger channel counts. */
    suspend fun findPlace(cacheDir: File, city: String): GardenPlace? {
        val places = getPlaces(cacheDir)
        if (places.isEmpty()) return null
        return places
            .filter { it.id.isNotBlank() && it.title.equals(city, ignoreCase = true) }
            .sortedWith(compareBy({ !it.country.equals("India", ignoreCase = true) }, { -it.size }))
            .firstOrNull()
    }

    suspend fun getPlaceChannels(placeId: String): List<RadioStation> = withContext(Dispatchers.IO) {
        if (placeId.isBlank()) return@withContext emptyList()
        runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/page/$placeId/channels")
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList()
                val parsed: PageResponse = json.decodeFromString(response.body.string())
                parsed.data?.content.orEmpty()
                    .flatMap { it.items }
                    .mapNotNull { it.page }
                    .filter { it.secure && it.title.isNotBlank() && it.url.isNotBlank() }
                    .map { page ->
                        val channelId = page.url.substringAfterLast("/")
                        RadioStation(
                            id = "rg_$channelId",
                            name = page.title.trim(),
                            streamUrl = listenUrl(channelId),
                            frequency = "Online",
                            city = page.place?.title.orEmpty(),
                            country = "India",
                            countryCode = "IN",
                            language = "Various",
                            logoUrl = null,
                            bitrate = 128,
                            tags = listOf("Radio Garden", page.place?.title.orEmpty())
                        )
                    }
                    .distinctBy { it.id }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * One-shot city lookup for the radio tab: place match, then its secure
     * channels. Empty on any failure — callers fall back to curated and
     * radio-browser lists.
     */
    suspend fun getCityStations(cacheDir: File, city: String): List<RadioStation> {
        val place = runCatching { findPlace(cacheDir, city) }.getOrNull() ?: return emptyList()
        return getPlaceChannels(place.id)
    }
}
