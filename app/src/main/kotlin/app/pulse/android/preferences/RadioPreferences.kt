package app.pulse.android.preferences

import app.pulse.android.GlobalPreferencesHolder
import app.pulse.android.data.CuratedIndianStations
import app.pulse.android.models.RadioStation

object RadioPreferences : GlobalPreferencesHolder() {
    var favoriteStations by json(CuratedIndianStations.defaultFavorites)
    var isWorldMode by boolean(false)
    var selectedLanguage by string("All")
    var selectedFilter by string("Maharashtra")
    var selectedCity by string("Maharashtra")
    var lastPlayedStationId by string("air_nanded")

    fun isFavorite(stationId: String): Boolean {
        return favoriteStations.any { it.id == stationId }
    }

    fun toggleFavorite(station: RadioStation) {
        val current = favoriteStations.toMutableList()
        val index = current.indexOfFirst { it.id == station.id }
        if (index >= 0) {
            current.removeAt(index)
        } else {
            current.add(0, station)
        }
        favoriteStations = current
    }
}
