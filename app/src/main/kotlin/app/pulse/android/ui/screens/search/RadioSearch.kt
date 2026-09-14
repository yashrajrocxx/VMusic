package app.pulse.android.ui.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pulse.android.LocalPlayerAwareWindowInsets
import app.pulse.android.LocalPlayerServiceBinder
import app.pulse.android.R
import app.pulse.android.data.CuratedIndianStations
import app.pulse.android.data.RadioBrowserApi
import app.pulse.android.models.RadioStation
import app.pulse.android.preferences.RadioPreferences
import app.pulse.android.ui.components.MusicBars
import app.pulse.android.utils.medium
import app.pulse.android.utils.playRadio
import app.pulse.android.utils.playingSong
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
import app.pulse.core.ui.Dimensions
import app.pulse.core.ui.LocalAppearance
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

@Composable
fun RadioSearch(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography, _, thumbnailShape) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current

    var favoriteStations by remember { mutableStateOf(RadioPreferences.favoriteStations) }
    val (currentMediaId, isPlaying) = playingSong(binder)

    val query = textFieldValue.text.trim()

    // Remote search state with debouncing
    var remoteResults by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var isSearchingRemote by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.length < 2) {
            remoteResults = emptyList()
            isSearchingRemote = false
            return@LaunchedEffect
        }
        delay(350)
        isSearchingRemote = true
        val results = runCatching { RadioBrowserApi.search(query, limit = 30) }.getOrDefault(emptyList())
        remoteResults = results
        isSearchingRemote = false
    }

    // Instant local matches (0ms latency)
    val localFiltered = remember(query) {
        if (query.isBlank()) {
            CuratedIndianStations.stations.take(20)
        } else {
            CuratedIndianStations.stations.filter { station ->
                station.name.contains(query, ignoreCase = true) ||
                station.city.contains(query, ignoreCase = true) ||
                station.state.contains(query, ignoreCase = true) ||
                station.tags.any { it.contains(query, ignoreCase = true) }
            }
        }
    }

    // Combined station list
    val displayedStations = remember(query, localFiltered, remoteResults) {
        if (query.isBlank()) {
            localFiltered
        } else {
            val localUrls = localFiltered.map { it.streamUrl.lowercase() }.toSet()
            val localNames = localFiltered.map { it.name.lowercase() }.toSet()
            val distinctRemote = remoteResults.filter {
                it.streamUrl.lowercase() !in localUrls && it.name.lowercase() !in localNames
            }
            localFiltered + distinctRemote
        }
    }

    val isFavorite: (RadioStation) -> Boolean = remember(favoriteStations) {
        val favIds = favoriteStations.map { it.id }.toSet()
        val favNames = favoriteStations.map { it.name.lowercase() }.toSet()
        val check: (RadioStation) -> Boolean = { station ->
            station.id in favIds || station.name.lowercase() in favNames
        }
        check
    }

    fun toggleFavorite(station: RadioStation) {
        val updated = if (isFavorite(station)) {
            favoriteStations.filter { it.id != station.id && !it.name.equals(station.name, ignoreCase = true) }
        } else {
            favoriteStations + station
        }
        favoriteStations = updated
        RadioPreferences.favoriteStations = updated
    }

    val lazyListState = rememberLazyListState()

    val suggestions = remember {
        listOf("Nanded", "Akashvani", "Vividh Bharati", "Pune", "Mirchi", "Marathi", "Hindi", "BBC", "Classical")
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom).asPaddingValues(),
        modifier = modifier.fillMaxSize()
    ) {
        // Quick suggestion chips when query is blank
        if (query.isEmpty()) {
            item(key = "suggestions_row") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    BasicText(
                        text = "Suggested searches",
                        style = typography.xs.secondary.copy(color = colorPalette.textSecondary),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(suggestions, key = { it }) { suggestion ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(colorPalette.background1)
                                    .clickable {
                                        onTextFieldValueChange(
                                            textFieldValue.copy(
                                                text = suggestion,
                                                selection = androidx.compose.ui.text.TextRange(suggestion.length)
                                            )
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                BasicText(
                                    text = suggestion,
                                    style = typography.xs.medium.copy(color = colorPalette.text)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Header showing results count or loading spinner
        item(key = "results_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = if (query.isBlank()) "Popular Stations" else "Results for \"$query\"",
                    style = typography.m.semiBold.copy(color = colorPalette.text)
                )
                if (isSearchingRemote) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = colorPalette.accent,
                        strokeWidth = 2.dp
                    )
                } else {
                    BasicText(
                        text = "${displayedStations.size} stations",
                        style = typography.xxs.secondary.copy(color = colorPalette.textSecondary)
                    )
                }
            }
        }

        if (displayedStations.isEmpty() && !isSearchingRemote) {
            item(key = "empty_state") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "No radio stations found",
                        style = typography.xs.secondary.copy(color = colorPalette.textSecondary)
                    )
                }
            }
        } else {
            items(
                items = displayedStations,
                key = { it.id },
                contentType = { "radio_station" }
            ) { station ->
                val isThisStationPlaying = currentMediaId == "radio:${station.id}"
                val fav = isFavorite(station)

                RadioSearchItem(
                    station = station,
                    isPlaying = isThisStationPlaying && isPlaying,
                    isFavorite = fav,
                    onPlayClick = { binder?.playRadio(station) },
                    onFavoriteClick = { toggleFavorite(station) }
                )
            }
        }
    }
}

@Composable
private fun RadioSearchItem(
    station: RadioStation,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val (colorPalette, typography, _, thumbnailShape) = LocalAppearance.current

    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlayClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with fallback placeholder
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(thumbnailShape)
                .background(colorPalette.background1),
            contentAlignment = Alignment.Center
        ) {
            if (!station.logoUrl.isNullOrBlank()) {
                val imageRequest = remember<ImageRequest>(station.logoUrl) {
                    ImageRequest.Builder(context)
                        .data(station.logoUrl)
                        .memoryCacheKey("radio_logo_${station.id}")
                        .diskCacheKey("radio_logo_${station.id}")
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = station.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.radio),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.accent),
                    modifier = Modifier.size(22.dp)
                )
            }

            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    MusicBars(
                        color = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = station.name,
                style = typography.xs.semiBold.copy(color = colorPalette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(3.dp))

            val details = buildString {
                if (station.city.isNotBlank()) append(station.city)
                if (station.frequency.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(station.frequency)
                } else if ((station.bitrate ?: 0) > 0) {
                    if (isNotEmpty()) append(" • ")
                    append("${station.bitrate} kbps")
                }
                if (station.language.isNotBlank() && station.language != "Various") {
                    if (isNotEmpty()) append(" • ")
                    append(station.language)
                }
            }

            BasicText(
                text = details.ifBlank { station.country },
                style = typography.xxs.secondary.copy(color = colorPalette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Heart favorite button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFavoriteClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(if (isFavorite) R.drawable.heart else R.drawable.heart_outline),
                contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                colorFilter = ColorFilter.tint(if (isFavorite) colorPalette.accent else colorPalette.textSecondary),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Play indicator
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isPlaying) {
                MusicBars(
                    color = colorPalette.accent,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.play),
                    contentDescription = "Play",
                    colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
