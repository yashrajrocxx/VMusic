package app.pulse.android.ui.screens.radio

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.android.LocalPlayerServiceBinder
import app.pulse.android.R
import app.pulse.android.data.CuratedIndianStations
import app.pulse.android.data.RadioBrowserApi
import app.pulse.android.data.RadioGardenApi
import app.pulse.android.models.RadioStation
import app.pulse.android.preferences.RadioPreferences
import app.pulse.android.ui.components.LocalMenuState
import app.pulse.android.ui.components.MusicBars
import app.pulse.android.ui.components.ShimmerHost
import app.pulse.android.ui.components.themed.CollapsingHeader
import app.pulse.android.ui.components.themed.CollapsingHeaderContentSpacer
import app.pulse.android.ui.components.themed.HeaderPillRow
import app.pulse.android.ui.components.themed.Menu
import app.pulse.android.ui.components.themed.SegmentedControl
import app.pulse.android.ui.items.ItemContainer
import app.pulse.android.ui.items.ItemInfoContainer
import app.pulse.android.ui.items.SongItemPlaceholder
import app.pulse.android.utils.center
import app.pulse.android.utils.medium
import app.pulse.android.utils.playRadio
import app.pulse.android.utils.playingSong
import app.pulse.android.utils.rememberIsBuffering
import app.pulse.android.utils.secondary
import app.pulse.android.utils.semiBold
import app.pulse.android.utils.shouldBePlaying
import app.pulse.core.ui.Dimensions
import app.pulse.core.ui.LocalAppearance
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade

private val countryStationsCache = mutableMapOf<String, List<RadioStation>>()
private val gardenStationsCache = mutableMapOf<String, List<RadioStation>>()

/** Radio Garden lookup city per filter; null where curated alone applies. */
private fun gardenCityFor(filter: String) = when (filter) {
    "Maharashtra", "All India" -> null
    "Delhi NCR" -> "New Delhi"
    else -> filter
}

@Composable
fun RadioScreen() {
    val binder = LocalPlayerServiceBinder.current
    val (colorPalette, typography) = LocalAppearance.current
    val menuState = LocalMenuState.current

    // Active filter defaults to Maharashtra
    var selectedFilter by rememberSaveable { mutableStateOf(RadioPreferences.selectedFilter) }
    var favoriteStations by remember { mutableStateOf(RadioPreferences.favoriteStations) }

    // Country stations state
    var countryStations by remember { mutableStateOf<List<RadioStation>>(countryStationsCache[selectedFilter] ?: emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Radio Garden stations for Indian cities, merged under the curated
    // list. radio-browser stays the source for countries and the fallback
    // when a city has neither curated nor Garden stations.
    val context = LocalContext.current.applicationContext
    var gardenStations by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    val gardenCity = remember(selectedFilter) { gardenCityFor(selectedFilter) }

    // Player state
    val (currentMediaId, isPlaying) = playingSong(binder)
    // Loading truth for station taps: the artwork spinner follows the
    // player's real buffering state. (The old pendingStationId flag waited
    // on isLoadingRadio, which only YouTube-radio ever sets, so the spinner
    // could never appear for station taps.)
    val isPlayerBuffering = binder?.player.rememberIsBuffering()

    val isCountry = remember(selectedFilter) {
        CuratedIndianStations.countries.any { it.name.equals(selectedFilter, ignoreCase = true) }
    }

    LaunchedEffect(selectedFilter) {
        val country = CuratedIndianStations.countries.firstOrNull { it.name.equals(selectedFilter, ignoreCase = true) }
        if (country != null) {
            gardenStations = emptyList()
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
            val city = gardenCity
            if (city != null) {
                val cached = gardenStationsCache[city]
                if (cached != null) {
                    gardenStations = cached
                    isLoading = false
                } else {
                    isLoading = true
                    val fetched = runCatching {
                        val garden = RadioGardenApi.getCityStations(context.cacheDir, city)
                        if (garden.isNotEmpty()) garden
                        else RadioBrowserApi.search(city, limit = 30)
                            .ifEmpty { RadioBrowserApi.getIndianStations(limit = 50) }
                    }.getOrDefault(emptyList())
                    gardenStationsCache[city] = fetched
                    gardenStations = fetched
                    isLoading = false
                }
            } else {
                gardenStations = emptyList()
                isLoading = false
            }
        }
    }

    val displayedStations = remember(selectedFilter, countryStations, gardenStations, isCountry) {
        if (isCountry) {
            countryStations
        } else {
            // Curated first (direct streams, real logos), Garden channels
            // appended and de-duplicated by name so the same station never
            // appears twice.
            (CuratedIndianStations.getStationsForIndianPlace(selectedFilter) + gardenStations)
                .distinctBy { it.name.lowercase() }
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

    fun togglePlay(station: RadioStation, isThisStationPlaying: Boolean) {
        if (isThisStationPlaying) {
            val player = binder?.player ?: return
            if (player.shouldBePlaying) player.pause() else player.play()
        } else {
            binder?.playRadio(station)
        }
    }

    val lazyListState = rememberLazyListState()

    // Scroll to top on filter switch
    LaunchedEffect(selectedFilter) {
        lazyListState.scrollToItem(0)
    }

    val sectionTextModifier = Modifier
        .padding(horizontal = 16.dp)
        .padding(top = 24.dp, bottom = 8.dp)

    CollapsingHeader(
        title = stringResource(R.string.radio),
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

            if (favoriteStations.isNotEmpty()) {
                item(key = "favorites_title") {
                    BasicText(
                        text = stringResource(R.string.favorites),
                        style = typography.m.semiBold,
                        modifier = sectionTextModifier
                    )
                }

                item(key = "favorites_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Dimensions.items.horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = favoriteStations,
                            key = { "fav_${it.id}" },
                            contentType = { "radio_favorite" }
                        ) { station ->
                            val isThisStationPlaying = currentMediaId == "radio:${station.id}"
                            RadioFavoriteCard(
                                station = station,
                                isPlaying = isThisStationPlaying && isPlaying,
                                isLoading = isThisStationPlaying && isPlayerBuffering,
                                onClick = { togglePlay(station, isThisStationPlaying) }
                            )
                        }
                    }
                }
            }

            item(key = "stations_title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(sectionTextModifier),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = stringResource(R.string.radio_stations),
                        style = typography.m.semiBold
                    )
                    if (!isLoading) {
                        BasicText(
                            text = stringResource(R.string.radio_stations_count, displayedStations.size),
                            style = typography.xxs.secondary
                        )
                    }
                }
            }

            if (isLoading && displayedStations.isEmpty()) {
                item(key = "stations_shimmer") {
                    ShimmerHost {
                        repeat(6) {
                            SongItemPlaceholder(thumbnailSize = Dimensions.thumbnails.song)
                        }
                    }
                }
            } else if (displayedStations.isEmpty()) {
                item(key = "empty_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = stringResource(R.string.radio_empty_for_region, selectedFilter),
                            style = typography.xs.secondary.center
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
                    RadioStationRow(
                        station = station,
                        isPlaying = isThisStationPlaying && isPlaying,
                        isLoading = isThisStationPlaying && isPlayerBuffering,
                        isFavorite = isFavorite(station),
                        onTogglePlay = { togglePlay(station, isThisStationPlaying) },
                        onFavoriteClick = { toggleFavorite(station) }
                    )
                }
            }
        }
    }
}

/**
 * Station artwork shared by cards and rows: cached logo, tinted fallback
 * icon, and the playing/loading overlay. Single choke point so both stay
 * identical.
 */
@Composable
private fun RadioStationArtwork(
    station: RadioStation,
    size: Dp,
    isPlaying: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val (colorPalette, _) = LocalAppearance.current
    val context = LocalContext.current

    Box(
        modifier = modifier
            .size(size)
            .clip(LocalAppearance.current.thumbnailShape)
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
                modifier = Modifier.size(size * 0.45f)
            )
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(size * 0.35f)
                )
            }
        } else if (isPlaying) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                MusicBars(
                    color = Color.White,
                    modifier = Modifier.size(size * 0.35f)
                )
            }
        }
    }
}

private fun stationDetails(station: RadioStation): String {
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
    return details.ifBlank { station.country }
}

/**
 * Favorite station card: album-card layout (artwork with name below), tap to
 * play/pause. Mirrors the app's horizontal card shelves.
 */
@Composable
private fun RadioFavoriteCard(
    station: RadioStation,
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (_, typography) = LocalAppearance.current
    val artSize = Dimensions.thumbnails.artist

    ItemContainer(
        alternative = true,
        thumbnailSize = artSize,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        RadioStationArtwork(
            station = station,
            size = artSize,
            isPlaying = isPlaying,
            isLoading = isLoading
        )

        ItemInfoContainer(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = station.name,
                style = typography.xs.semiBold.center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = stationDetails(station),
                style = typography.xxs.secondary.center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Station row mirroring SongItem: same container, type scale, trailing heart
 * and playing highlight. Tap toggles playback for the active station so a
 * replay never restarts the stream by accident.
 */
@Composable
private fun RadioStationRow(
    station: RadioStation,
    isPlaying: Boolean,
    isLoading: Boolean,
    isFavorite: Boolean,
    onTogglePlay: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    val backgroundColor by animateColorAsState(
        targetValue = if (isPlaying) colorPalette.background2 else Color.Transparent,
        label = ""
    )

    ItemContainer(
        alternative = false,
        thumbnailSize = Dimensions.thumbnails.song,
        modifier = modifier
            .background(backgroundColor)
            .clip(LocalAppearance.current.thumbnailShape)
            .clickable(onClick = onTogglePlay)
    ) {
        RadioStationArtwork(
            station = station,
            size = Dimensions.thumbnails.song,
            isPlaying = isPlaying,
            isLoading = isLoading
        )

        ItemInfoContainer {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BasicText(
                    text = station.name,
                    style = typography.xs.semiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

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
            }

            BasicText(
                text = stationDetails(station),
                style = typography.xs.semiBold.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Filter Bottom Sheet Menu: grouped selection for Indian places and World
 * countries.
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
                text = stringResource(R.string.select_region),
                style = typography.m.semiBold.copy(color = colorPalette.text),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            SegmentedControl(
                segments = listOf(
                    stringResource(R.string.region_india),
                    stringResource(R.string.region_world)
                ),
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
                            text = if (place == "Maharashtra") {
                                stringResource(R.string.radio_default_place, place)
                            } else place,
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
