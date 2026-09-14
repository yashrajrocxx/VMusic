package app.pulse.android.ui.screens.radio

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.android.LocalPlayerServiceBinder
import app.pulse.android.R
import app.pulse.android.data.CuratedIndianStations
import app.pulse.android.data.RadioBrowserApi
import app.pulse.android.models.RadioStation
import app.pulse.android.preferences.RadioPreferences
import app.pulse.android.ui.components.LocalMenuState
import app.pulse.android.ui.components.MusicBars
import app.pulse.android.ui.components.themed.CollapsingHeader
import app.pulse.android.ui.components.themed.CollapsingHeaderContentSpacer
import app.pulse.android.ui.components.themed.HeaderPillRow
import app.pulse.android.ui.components.themed.Menu
import app.pulse.android.ui.components.themed.SegmentedControl
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

private val countryStationsCache = mutableMapOf<String, List<RadioStation>>()

@Composable
fun RadioScreen() {
    val binder = LocalPlayerServiceBinder.current
    val (colorPalette, typography, _, thumbnailShape) = LocalAppearance.current
    val menuState = LocalMenuState.current

    // Active filter defaults to Maharashtra
    var selectedFilter by rememberSaveable { mutableStateOf(RadioPreferences.selectedFilter) }
    var favoriteStations by remember { mutableStateOf(RadioPreferences.favoriteStations) }

    // Country stations state
    var countryStations by remember { mutableStateOf<List<RadioStation>>(countryStationsCache[selectedFilter] ?: emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Player state
    val (currentMediaId, isPlaying) = playingSong(binder)

    // Which station the user just tapped, so its play/pause button can show a
    // loading indicator (matching the mini player) until the radio starts.
    var pendingStationId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(binder?.isLoadingRadio) {
        if (binder?.isLoadingRadio != true) pendingStationId = null
    }

    val isCountry = remember(selectedFilter) {
        CuratedIndianStations.countries.any { it.name.equals(selectedFilter, ignoreCase = true) }
    }

    LaunchedEffect(selectedFilter) {
        val country = CuratedIndianStations.countries.firstOrNull { it.name.equals(selectedFilter, ignoreCase = true) }
        if (country != null) {
            val cached = countryStationsCache[country.code]
            if (cached != null) {
                countryStations = cached
                isLoading = false
            } else {
                isLoading = true
                val fetched = runCatching {
                    RadioBrowserApi.getStationsByCountry(country.code, limit = 50)
                }.getOrDefault(emptyList())
                countryStationsCache[country.code] = fetched
                countryStations = fetched
                isLoading = false
            }
        } else {
            countryStations = emptyList()
            isLoading = false
        }
    }

    val displayedStations = remember(selectedFilter, countryStations, isCountry) {
        if (isCountry) {
            countryStations
        } else {
            CuratedIndianStations.getStationsForIndianPlace(selectedFilter)
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

    // Scroll to top on filter switch
    LaunchedEffect(selectedFilter) {
        lazyListState.scrollToItem(0)
    }

    CollapsingHeader(
        title = "Radio",
        lazyListState = lazyListState,
        expandedFontSize = 38.sp,
        collapsedFontSize = 28.sp,
        headerActions = {
            HeaderPillRow {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            menuState.display {
                                RadioFilterMenu(
                                    currentFilter = selectedFilter,
                                    onFilterSelected = { newFilter ->
                                        selectedFilter = newFilter
                                        RadioPreferences.selectedFilter = newFilter
                                        menuState.hide()
                                    }
                                )
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = selectedFilter,
                        style = typography.xs.semiBold.copy(color = colorPalette.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Image(
                        painter = painterResource(R.drawable.chevron_down),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .background(colorPalette.background0),
            contentPadding = app.pulse.android.LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                .asPaddingValues()
        ) {
            item(key = "spacer") {
                Spacer(modifier = Modifier.height(CollapsingHeaderContentSpacer))
            }

            // Compact Favorites Shelf
            if (favoriteStations.isNotEmpty()) {
                item(key = "favorites_section") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimensions.items.horizontalPadding, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(R.drawable.heart),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(colorPalette.accent),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            BasicText(
                                text = "Favorites",
                                style = typography.xs.semiBold.copy(color = colorPalette.text)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            BasicText(
                                text = "(${favoriteStations.size})",
                                style = typography.xs.secondary.copy(color = colorPalette.textSecondary)
                            )
                        }

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Dimensions.items.horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = favoriteStations,
                                key = { "fav_${it.id}" }
                            ) { station ->
                                val isThisStationPlaying = currentMediaId == "radio:${station.id}"
                                RadioFavoriteChip(
                                    station = station,
                                    isPlaying = isThisStationPlaying && isPlaying,
                                    isLoading = pendingStationId == station.id && binder?.isLoadingRadio == true,
                                    onClick = {
                                        pendingStationId = station.id
                                        binder?.playRadio(station)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Section Subheader: Clean "Stations" header with channel count on right
            item(key = "section_subheader") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimensions.items.horizontalPadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = "Stations",
                        style = typography.xs.semiBold.copy(color = colorPalette.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
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

            // Station List (Clean SongItem-style rows)
            if (displayedStations.isEmpty() && !isLoading) {
                item(key = "empty_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = "No stations available for $selectedFilter",
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

                    RadioStationRow(
                        station = station,
                        isPlaying = isThisStationPlaying && isPlaying,
                        isLoading = pendingStationId == station.id && binder?.isLoadingRadio == true,
                        isFavorite = fav,
                        onPlayClick = {
                            pendingStationId = station.id
                            binder?.playRadio(station)
                        },
                        onFavoriteClick = { toggleFavorite(station) }
                    )
                }
            }
        }
    }
}

/**
 * Filter Bottom Sheet Menu: Clean, grouped selection for Indian places and World countries.
 * Zero emojis, sleek minimal typography.
 */
@Composable
private fun RadioFilterMenu(
    currentFilter: String,
    onFilterSelected: (String) -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val isInitiallyCountry = remember {
        CuratedIndianStations.countries.any { it.name.equals(currentFilter, ignoreCase = true) }
    }
    var selectedCategoryIndex by rememberSaveable { mutableIntStateOf(if (isInitiallyCountry) 1 else 0) }

    Menu {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            BasicText(
                text = "Select Region",
                style = typography.m.semiBold.copy(color = colorPalette.text),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            SegmentedControl(
                segments = listOf("India", "World"),
                selectedSegment = selectedCategoryIndex,
                onSegmentSelected = { selectedCategoryIndex = it },
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (selectedCategoryIndex == 0) {
                // Indian Places
                CuratedIndianStations.indianPlaces.forEach { place ->
                    val isSelected = currentFilter.equals(place, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) colorPalette.background2 else Color.Transparent)
                            .clickable { onFilterSelected(place) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = if (place == "Maharashtra") "Maharashtra (Default)" else place,
                            style = (if (isSelected) typography.xs.semiBold else typography.xs.medium)
                                .copy(color = if (isSelected) colorPalette.accent else colorPalette.text)
                        )
                        if (isSelected) {
                            Image(
                                painter = painterResource(R.drawable.radio),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(colorPalette.accent),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else {
                // World Countries (Countries only)
                CuratedIndianStations.countries.forEach { country ->
                    val isSelected = currentFilter.equals(country.name, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) colorPalette.background2 else Color.Transparent)
                            .clickable { onFilterSelected(country.name) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = country.name,
                            style = (if (isSelected) typography.xs.semiBold else typography.xs.medium)
                                .copy(color = if (isSelected) colorPalette.accent else colorPalette.text)
                        )
                        if (isSelected) {
                            Image(
                                painter = painterResource(R.drawable.radio),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(colorPalette.accent),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact Favorite Chip for the top horizontal shelf with thumbnail & placeholder.
 */
@Composable
private fun RadioFavoriteChip(
    station: RadioStation,
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val (colorPalette, typography, _, thumbnailShape) = LocalAppearance.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colorPalette.background1)
            .clickable(onClick = onClick)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with fallback placeholder
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(thumbnailShape)
                .background(colorPalette.background0),
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
                        .crossfade(false)
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
                    modifier = Modifier.size(18.dp)
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
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column {
            BasicText(
                text = station.name,
                style = typography.xs.semiBold.copy(color = colorPalette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subText = if (station.frequency.isNotBlank()) "${station.city} • ${station.frequency}" else station.city
            BasicText(
                text = subText.ifBlank { station.country },
                style = typography.xxs.secondary.copy(color = colorPalette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Clean, lightweight station row matching the rest of ViMusic's song item layout.
 * Displays cached artwork or fallback placeholder.
 */
@Composable
private fun RadioStationRow(
    station: RadioStation,
    isPlaying: Boolean,
    isLoading: Boolean,
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
            .padding(
                horizontal = Dimensions.items.horizontalPadding,
                vertical = Dimensions.items.verticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Station Logo / Tinted Placeholder Radio Icon (48.dp clean rounded square)
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
                        .crossfade(false)
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

        // Station Details
        Column(
            modifier = Modifier.weight(1f)
        ) {
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

        // Action: Favorite Heart Button
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

        // Action: Play Indicator / Button
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
            if (isLoading) {
                CircularProgressIndicator(
                    color = colorPalette.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
            } else if (isPlaying) {
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
