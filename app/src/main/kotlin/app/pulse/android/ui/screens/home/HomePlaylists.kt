package app.pulse.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.android.Database
import app.pulse.android.LocalPlayerAwareWindowInsets
import app.pulse.android.R
import app.pulse.android.models.Playlist
import app.pulse.android.models.PlaylistPreview
import app.pulse.android.preferences.DataPreferences
import app.pulse.android.preferences.OrderPreferences
import app.pulse.android.preferences.UIStatePreferences
import app.pulse.android.query
import app.pulse.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.pulse.android.ui.components.themed.CollapsingHeader
import app.pulse.android.ui.components.themed.CollapsingHeaderContentSpacer
import app.pulse.android.ui.components.themed.HeaderIconButton
import app.pulse.android.ui.components.themed.HeaderPillRow
import app.pulse.android.ui.components.themed.SecondaryTextButton
import app.pulse.android.ui.components.NewMenu
import app.pulse.android.ui.components.NewMenuDivider
import app.pulse.android.ui.components.NewMenuEntry
import app.pulse.android.ui.components.themed.ImportPlaylistDialog
import app.pulse.android.ui.components.themed.TextFieldDialog
import app.pulse.android.ui.components.themed.VerticalDivider
import app.pulse.android.ui.items.PlaylistItem
import app.pulse.android.ui.screens.Route
import app.pulse.android.ui.screens.builtinplaylist.BuiltInPlaylistScreen
import app.pulse.compose.persist.persist
import app.pulse.compose.persist.persistList
import app.pulse.core.data.enums.PlaylistSortBy
import app.pulse.core.data.enums.SortOrder
import app.pulse.core.ui.Dimensions
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import app.pulse.core.data.enums.BuiltInPlaylist
import app.pulse.core.ui.LocalAppearance
import coil3.compose.AsyncImage
import kotlinx.collections.immutable.toImmutableList

@Route
@Composable
fun HomePlaylists(
    onPlaylistClick: (Playlist) -> Unit,
    onBuiltInPlaylistClick: (BuiltInPlaylist) -> Unit = {},
    onSearchClick: () -> Unit
) = with(OrderPreferences) {
    val (colorPalette) = LocalAppearance.current

    var isCreatingANewPlaylist by rememberSaveable { mutableStateOf(false) }
    var isImportingPlaylist by rememberSaveable { mutableStateOf(false) }
    var isMenuVisible by rememberSaveable { mutableStateOf(false) }

    var favoritesCount by remember { mutableIntStateOf(0) }
    var firstFavoriteThumbnailUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        Database.favorites().collect { songs ->
            favoritesCount = songs.size
            firstFavoriteThumbnailUrl = songs.firstOrNull()?.thumbnailUrl
        }
    }

    if (isImportingPlaylist) ImportPlaylistDialog(
        onDismiss = { isImportingPlaylist = false }
    )

    if (isCreatingANewPlaylist) TextFieldDialog(
        hintText = stringResource(R.string.enter_playlist_name_prompt),
        onDismiss = { isCreatingANewPlaylist = false },
        onAccept = { text ->
            query {
                Database.insert(Playlist(name = text))
            }
        }
    )
    var items by persistList<PlaylistPreview>("home/playlists")

    LaunchedEffect(playlistSortBy, playlistSortOrder) {
        Database
            .playlistPreviews(playlistSortBy, playlistSortOrder)
            .collect { items = it.toImmutableList() }
    }

    val lazyGridState = rememberLazyGridState()

    Box {
    CollapsingHeader(
        title = stringResource(R.string.playlists),
        lazyGridState = lazyGridState,
        expandedFontSize = 36.sp,
        collapsedFontSize = 26.sp,
        headerActions = {
            HeaderPillRow {
            HeaderIconButton(
                icon = R.drawable.add,
                onClick = { isCreatingANewPlaylist = true }
            )
            HeaderIconButton(
                icon = R.drawable.import_playlist, // this is the playlist page
                onClick = { isImportingPlaylist = true }
            )
Box {
                HeaderIconButton(
                    icon = R.drawable.hamburger,
                    onClick = { isMenuVisible = !isMenuVisible }
                )


                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                    NewMenu(
                    visible = isMenuVisible,
                    onDismiss = { isMenuVisible = false }
                ) {
                NewMenuEntry(
                    icon = R.drawable.medical,
                    text = "Sort by song count",
                    checked = playlistSortBy == PlaylistSortBy.SongCount,
                    onClick = {
                        playlistSortBy = PlaylistSortBy.SongCount
                        isMenuVisible = false
                    }
                )
                NewMenuEntry(
                    icon = R.drawable.text,
                    text = "Sort by name",
                    checked = playlistSortBy == PlaylistSortBy.Name,
                    onClick = {
                        playlistSortBy = PlaylistSortBy.Name
                        isMenuVisible = false
                    }
                )
                NewMenuEntry(
                    icon = R.drawable.time,
                    text = "Sort by date added",
                    checked = playlistSortBy == PlaylistSortBy.DateAdded,
                    onClick = {
                        playlistSortBy = PlaylistSortBy.DateAdded
                        isMenuVisible = false
                    }
                )

                NewMenuDivider()

                NewMenuEntry(
                    icon = R.drawable.arrow_up,
                    text = "Sort order",
                    secondaryText = if (playlistSortOrder == SortOrder.Ascending) "Ascending" else "Descending",
                    onClick = {
                        playlistSortOrder = !playlistSortOrder
                        isMenuVisible = false
                    }
                )
                NewMenuEntry(
                    icon = if (UIStatePreferences.playlistsAsGrid) R.drawable.grid else R.drawable.list,
                    text = "Layout",
                    secondaryText = if (UIStatePreferences.playlistsAsGrid) "Grid" else "List",
                    onClick = {
                        UIStatePreferences.playlistsAsGrid = !UIStatePreferences.playlistsAsGrid
                        isMenuVisible = false
                    }
                )
                }
                }                }
                }
            }
    ) {
        LazyVerticalGrid(
            state = lazyGridState,
            columns = if (UIStatePreferences.playlistsAsGrid)
                GridCells.Adaptive(Dimensions.thumbnails.playlist + Dimensions.items.alternativePadding * 2)
            else GridCells.Fixed(1),
            contentPadding = PaddingValues(
                top = LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues()
                    .calculateTopPadding() + 0.dp,
                bottom = LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues()
                    .calculateBottomPadding()
            ),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.items.alternativePadding),
            verticalArrangement = if (UIStatePreferences.playlistsAsGrid)
                Arrangement.spacedBy(Dimensions.items.alternativePadding)
            else Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .background(colorPalette.background0)
        ) {
            item(key = "spacer", span = { GridItemSpan(maxLineSpan) }) {
                Spacer(modifier = Modifier.height(CollapsingHeaderContentSpacer))
            }

            item(key = "builtin_favorites", span = { GridItemSpan(if (UIStatePreferences.playlistsAsGrid) 1 else maxLineSpan) }) {
                PlaylistItem(
                    thumbnailContent = {
                        Box(
                            modifier = it.background(colorPalette.background1),
                            contentAlignment = Alignment.Center
                        ) {
                            if (firstFavoriteThumbnailUrl != null) {
                                AsyncImage(
                                    model = firstFavoriteThumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.35f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.heart),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(colorPalette.accent),
                                        modifier = Modifier.size(if (UIStatePreferences.playlistsAsGrid) 32.dp else 22.dp)
                                    )
                                }
                            } else {
                                Image(
                                    painter = painterResource(R.drawable.heart),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colorPalette.accent),
                                    modifier = Modifier.size(if (UIStatePreferences.playlistsAsGrid) 36.dp else 24.dp)
                                )
                            }
                        }
                    },
                    songCount = favoritesCount,
                    name = stringResource(R.string.favorites),
                    channelName = null,
                    thumbnailSize = if (UIStatePreferences.playlistsAsGrid) Dimensions.thumbnails.playlist else Dimensions.thumbnails.playlist - 24.dp,
                    alternative = UIStatePreferences.playlistsAsGrid,
                    showChevron = true,
                    modifier = Modifier
                        .clickable(onClick = { onBuiltInPlaylistClick(BuiltInPlaylist.Favorites) })
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                )
            }

            items(
                items = items,
                key = { it.playlist.id },
                contentType = { "playlist" }
            ) { playlistPreview ->
                PlaylistItem(
                    playlist = playlistPreview,
                    thumbnailSize = if (UIStatePreferences.playlistsAsGrid) Dimensions.thumbnails.playlist else Dimensions.thumbnails.playlist - 24.dp,
                    alternative = UIStatePreferences.playlistsAsGrid,
                    showChevron = true,
                    modifier = Modifier
                        .clickable(onClick = { onPlaylistClick(playlistPreview.playlist) })
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                )
            }
        }
    }

    FloatingActionsContainerWithScrollToTop(
            lazyGridState = lazyGridState,
            icon = null
        )
    }
}
