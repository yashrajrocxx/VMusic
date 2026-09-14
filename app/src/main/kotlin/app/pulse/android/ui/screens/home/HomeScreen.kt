package app.pulse.android.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import app.pulse.android.R
import app.pulse.android.models.toUiMood
import app.pulse.android.preferences.UIStatePreferences
import app.pulse.android.ui.components.themed.LocalDockScrolled
import app.pulse.android.ui.components.themed.LocalNavigationState
import app.pulse.android.ui.components.themed.Scaffold
import app.pulse.android.ui.screens.GlobalRoutes
import app.pulse.android.ui.screens.Route
import app.pulse.android.ui.screens.albumRoute
import app.pulse.android.ui.screens.artistRoute
import app.pulse.android.ui.screens.builtInPlaylistRoute
import app.pulse.android.ui.screens.builtinplaylist.BuiltInPlaylistScreen
import app.pulse.android.ui.screens.localPlaylistRoute
import app.pulse.android.ui.screens.localplaylist.LocalPlaylistScreen
import app.pulse.android.ui.screens.mood.MoodScreen
import app.pulse.android.ui.screens.mood.MoreAlbumsScreen
import app.pulse.android.ui.screens.mood.MoreMoodsScreen
import app.pulse.android.ui.screens.moodRoute
import app.pulse.android.ui.screens.playlistRoute
import app.pulse.android.ui.screens.searchRoute
import app.pulse.android.ui.screens.recognition.RecognitionScreen
import app.pulse.android.ui.screens.radio.RadioScreen
import app.pulse.android.ui.screens.settings.SettingsScreen
import app.pulse.compose.persist.PersistMapCleanup
import app.pulse.compose.routing.Route0
import app.pulse.compose.routing.RouteHandler

private val moreMoodsRoute = Route0("moreMoodsRoute")
private val moreAlbumsRoute = Route0("moreAlbumsRoute")

private object ProcessColdStartMarker {
    var done = false
}

@Route
@Composable
fun HomeScreen() {
    val saveableStateHolder = rememberSaveableStateHolder()

    PersistMapCleanup("home/")

    val globalNavigationState = LocalNavigationState.current
    val dockScrolled = LocalDockScrolled.current

    RouteHandler {
        val rootScope = this
        GlobalRoutes()

        localPlaylistRoute { playlistId ->
            LocalPlaylistScreen(playlistId = playlistId)
        }

        builtInPlaylistRoute { builtInPlaylist ->
            BuiltInPlaylistScreen(builtInPlaylist = builtInPlaylist)
        }

        moodRoute { mood ->
            MoodScreen(mood = mood)
        }

        moreMoodsRoute {
            MoreMoodsScreen()
        }

        moreAlbumsRoute {
            MoreAlbumsScreen()
        }

        Content {
            val tabBackStack = rememberSaveable { mutableStateListOf<Int>() }
            remember {
                if (!ProcessColdStartMarker.done) {
                    ProcessColdStartMarker.done = true
                    UIStatePreferences.homeScreenTabIndex = 0
                }
            }
            val currentTab = UIStatePreferences.homeScreenTabIndex

            BackHandler(enabled = currentTab != 0 || tabBackStack.isNotEmpty()) {
                val prev = if (tabBackStack.isNotEmpty()) {
                    tabBackStack.removeAt(tabBackStack.lastIndex)
                } else {
                    0
                }
                UIStatePreferences.homeScreenTabIndex = prev
                globalNavigationState.value = globalNavigationState.value?.copy(tabIndex = prev)
            }

            Scaffold(
                key = "home",
                isGlobalNav = true,
                tabIndex = currentTab,
                onTabChange = { newTab ->
                    rootScope.replace(null)
                    dockScrolled.value = false
                    val current = UIStatePreferences.homeScreenTabIndex
                    if (newTab != current) {
                        tabBackStack.remove(newTab)
                        tabBackStack.add(current)
                        UIStatePreferences.homeScreenTabIndex = newTab
                        globalNavigationState.value = globalNavigationState.value?.copy(tabIndex = newTab)
                    }
                },
                tabColumnContent = {
                    tab(0, R.string.home, R.drawable.home)
                    tab(1, R.string.discover, R.drawable.globe)
                    tab(2, R.string.radio, R.drawable.radio)
                    tab(3, R.string.songs, R.drawable.musical_notes)
                    tab(4, R.string.playlists, R.drawable.playlist)
                    tab(5, R.string.artists, R.drawable.person)
                    tab(6, R.string.albums, R.drawable.disc)
                    tab(7, R.string.local, R.drawable.download)
                    tab(8, R.string.recognize, R.drawable.mic)
                    tab(9, R.string.settings, R.drawable.settings)
                }
            ) { currentTabIndex ->
                saveableStateHolder.SaveableStateProvider(key = currentTabIndex) {
                    val onSearchClick = { searchRoute("") }
                    when (currentTabIndex) {
                        0 -> QuickPicks(
                            onAlbumClick = { albumRoute(it.key) },
                            onArtistClick = { artistRoute(it.key) },
                            onPlaylistClick = {
                                playlistRoute(
                                    p0 = it.key,
                                    p1 = null,
                                    p2 = null,
                                    p3 = it.channel?.name == "YouTube Music"
                                )
                            }
                        )

                        1 -> HomeDiscovery(
                            onMoodClick = { mood -> moodRoute(mood.toUiMood()) },
                            onNewReleaseAlbumClick = { albumRoute(it) },
                            onSearchClick = onSearchClick,
                            onMoreMoodsClick = { moreMoodsRoute() },
                            onMoreAlbumsClick = { moreAlbumsRoute() },
                            onPlaylistClick = { playlistRoute(it, null, null, true) }
                        )

                        2 -> RadioScreen()

                        3 -> HomeSongs(
                            onSearchClick = onSearchClick
                        )

                        4 -> HomePlaylists(
                            onPlaylistClick = { localPlaylistRoute(it.id) },
                            onBuiltInPlaylistClick = { builtInPlaylistRoute(it) },
                            onSearchClick = onSearchClick
                        )

                        5 -> HomeArtistList(
                            onArtistClick = { artistRoute(it.id) },
                            onSearchClick = onSearchClick
                        )

                        6 -> HomeAlbums(
                            onAlbumClick = { albumRoute(it.id) },
                            onSearchClick = onSearchClick
                        )

                        7 -> HomeLocalSongs(
                            onSearchClick = onSearchClick
                        )

                        8 -> RecognitionScreen()

                        9 -> SettingsScreen(
                            onBackClick = {
                                val prev = if (tabBackStack.isNotEmpty()) {
                                    tabBackStack.removeAt(tabBackStack.lastIndex)
                                } else {
                                    0
                                }
                                UIStatePreferences.homeScreenTabIndex = prev
                                globalNavigationState.value = globalNavigationState.value?.copy(tabIndex = prev)
                            }
                        )
                    }
                }
            }
        }
    }
}
