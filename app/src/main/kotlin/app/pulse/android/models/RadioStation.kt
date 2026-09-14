package app.pulse.android.models

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val frequency: String = "",
    val city: String = "",
    val state: String = "",
    val country: String = "India",
    val countryCode: String = "IN",
    val language: String = "Hindi",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val logoUrl: String? = null,
    val bitrate: Int? = 128,
    val tags: List<String> = emptyList()
)
