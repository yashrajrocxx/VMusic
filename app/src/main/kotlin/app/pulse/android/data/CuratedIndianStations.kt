package app.pulse.android.data

import app.pulse.android.models.RadioStation

object CuratedIndianStations {

    val defaultCity = "Nanded"

    val stations = listOf(
        // =================== NANDED ===================
        RadioStation(
            id = "air_nanded",
            name = "Akashvani Nanded",
            streamUrl = "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio010/hlspbaudio010_Auto.m3u8",
            frequency = "101.1 FM",
            city = "Nanded",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Marathi",
            lat = 19.1383,
            lon = 77.3210,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Regional", "Talk", "News", "Culture", "Marathi")
        ),
        RadioStation(
            id = "radio_city_nanded",
            name = "Radio City Nanded",
            streamUrl = "https://eu8.fastcast4u.com/proxy/clyedupq/stream",
            frequency = "91.1 FM",
            city = "Nanded",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 19.1383,
            lon = 77.3210,
            logoUrl = "https://mytuner.global.ssl.fastly.net/media/tvos_radios/ppqbgfej6skx.jpeg",
            bitrate = 128,
            tags = listOf("Bollywood", "Music", "Hindi", "Entertainment")
        ),
        RadioStation(
            id = "red_fm_nanded",
            name = "Red FM Nanded",
            streamUrl = "https://funasia.streamguys1.com/live9",
            frequency = "93.5 FM",
            city = "Nanded",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 19.1383,
            lon = 77.3210,
            logoUrl = "https://mytuner.global.ssl.fastly.net/media/tvos_radios/ppqbgfej6skx.jpeg",
            bitrate = 128,
            tags = listOf("Bajaate Raho", "Bollywood", "RJ Talk", "Hindi")
        ),
        RadioStation(
            id = "my_fm_nanded",
            name = "MY FM Nanded",
            streamUrl = "https://stream.zeno.fm/84h97t3ewg0uv",
            frequency = "94.3 FM",
            city = "Nanded",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 19.1383,
            lon = 77.3210,
            logoUrl = "https://mytuner.global.ssl.fastly.net/media/tvos_radios/ppqbgfej6skx.jpeg",
            bitrate = 128,
            tags = listOf("Entertainment", "Bollywood", "Talk")
        ),

        // =================== PUNE ===================
        RadioStation(
            id = "air_pune_fm",
            name = "AIR Pune FM",
            streamUrl = "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio010/hlspbaudio010_Auto.m3u8",
            frequency = "101.0 FM",
            city = "Pune",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Marathi",
            lat = 18.5204,
            lon = 73.8567,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Regional", "Marathi", "News", "Classical", "Talk")
        ),
        RadioStation(
            id = "radio_city_marathi",
            name = "Radio City Marathi",
            streamUrl = "http://stream.zeno.fm/48kybvzrwfeuv",
            frequency = "91.1 Web",
            city = "Pune",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Marathi",
            lat = 18.5204,
            lon = 73.8567,
            logoUrl = "https://mytuner.global.ssl.fastly.net/media/tvos_radios/ppqbgfej6skx.jpeg",
            bitrate = 128,
            tags = listOf("Marathi", "Songs", "Music", "Entertainment")
        ),
        RadioStation(
            id = "radio_mirchi_pune",
            name = "Radio Mirchi Pune",
            streamUrl = "https://mirchiplaylive.akamaized.net/hls/live/2036929-b/MUM/CLUBMI_Auto.m3u8",
            frequency = "98.3 FM",
            city = "Pune",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 18.5204,
            lon = 73.8567,
            logoUrl = "https://mytuner.global.ssl.fastly.net/media/tvos_radios/ppqbgfej6skx.jpeg",
            bitrate = 128,
            tags = listOf("Bollywood", "Hits", "Hindi", "RJ Talk")
        ),

        // =================== MUMBAI ===================
        RadioStation(
            id = "vividh_bharati",
            name = "Vividh Bharati",
            streamUrl = "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio008/hlspbaudio008_Auto.m3u8",
            frequency = "102.8 FM",
            city = "Mumbai",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 18.9220,
            lon = 72.8347,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("National", "Golden Era", "Retro", "Hindi", "Talk")
        ),
        RadioStation(
            id = "air_rainbow_mumbai",
            name = "AIR FM Rainbow Mumbai",
            streamUrl = "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio008/hlspbaudio008_Auto.m3u8",
            frequency = "107.1 FM",
            city = "Mumbai",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 18.9220,
            lon = 72.8347,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Music", "Hindi", "English", "Youth")
        ),
        RadioStation(
            id = "filmy_mirchi",
            name = "Filmy Mirchi",
            streamUrl = "https://stream-142.zeno.fm/6n6ewddtad0uv",
            frequency = "Online",
            city = "Mumbai",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 18.9220,
            lon = 72.8347,
            logoUrl = "https://mytuner.global.ssl.fastly.net/media/tvos_radios/ppqbgfej6skx.jpeg",
            bitrate = 128,
            tags = listOf("Bollywood", "Filmy", "Top 20")
        ),
        RadioStation(
            id = "meethi_mirchi",
            name = "Meethi Mirchi",
            streamUrl = "https://drive.uber.radio/uber/bollywoodnow/icecast.audio",
            frequency = "Online",
            city = "Mumbai",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 18.9220,
            lon = 72.8347,
            logoUrl = "https://mytuner.global.ssl.fastly.net/media/tvos_radios/ppqbgfej6skx.jpeg",
            bitrate = 128,
            tags = listOf("Melodies", "Romance", "Bollywood")
        ),

        // =================== MAHARASHTRA REGIONAL ===================
        RadioStation(
            id = "air_nagpur",
            name = "AIR Nagpur",
            streamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio070/playlist.m3u8",
            frequency = "100.6 FM",
            city = "Nagpur",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Marathi",
            lat = 21.1458,
            lon = 79.0882,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Marathi", "News", "Vidarbha", "Culture")
        ),
        RadioStation(
            id = "air_kolhapur",
            name = "AIR Kolhapur",
            streamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio063/playlist.m3u8",
            frequency = "102.3 FM",
            city = "Kolhapur",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Marathi",
            lat = 16.7050,
            lon = 74.2433,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Marathi", "Regional", "Folk", "Talk")
        ),
        RadioStation(
            id = "air_aurangabad",
            name = "AIR Chhatrapati Sambhajinagar",
            streamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio001/playlist.m3u8",
            frequency = "101.7 FM",
            city = "Chhatrapati Sambhajinagar",
            state = "Maharashtra",
            country = "India",
            countryCode = "IN",
            language = "Marathi",
            lat = 19.8762,
            lon = 75.3433,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Marathi", "Marathwada", "News", "Culture")
        ),

        // =================== DELHI ===================
        RadioStation(
            id = "air_delhi_gold",
            name = "AIR Delhi FM Gold",
            streamUrl = "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio005/hlspbaudio005_Auto.m3u8",
            frequency = "106.4 FM",
            city = "Delhi",
            state = "Delhi",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 28.6139,
            lon = 77.2090,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("National", "Gold", "News", "Hindi", "Retro")
        ),
        RadioStation(
            id = "bollywood_gaane_purane",
            name = "Bollywood Gaane Purane",
            streamUrl = "https://stream.zeno.fm/6n6ewddtad0uv",
            frequency = "Online",
            city = "Delhi",
            state = "Delhi",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 28.6139,
            lon = 77.2090,
            logoUrl = "https://mytuner.global.ssl.fastly.net/media/tvos_radios/ppqbgfej6skx.jpeg",
            bitrate = 128,
            tags = listOf("Retro", "Classic Bollywood", "Kishore Kumar", "Lata Mangeshkar")
        ),

        // =================== BENGALURU ===================
        RadioStation(
            id = "air_bengaluru",
            name = "AIR Vividh Bharati Bengaluru",
            streamUrl = "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio026/hlspbaudio026_Auto.m3u8",
            frequency = "102.9 FM",
            city = "Bengaluru",
            state = "Karnataka",
            country = "India",
            countryCode = "IN",
            language = "Kannada",
            lat = 12.9716,
            lon = 77.5946,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Kannada", "Music", "Regional")
        ),

        // =================== HYDERABAD ===================
        RadioStation(
            id = "vividh_bharati_hyderabad",
            name = "AIR Vividh Bharati Hyderabad",
            streamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio034/playlist.m3u8",
            frequency = "102.8 FM",
            city = "Hyderabad",
            state = "Telangana",
            country = "India",
            countryCode = "IN",
            language = "Telugu",
            lat = 17.3850,
            lon = 78.4867,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Telugu", "Hindi", "Melodies")
        ),

        // =================== CHENNAI ===================
        RadioStation(
            id = "air_fm_gold_chennai",
            name = "AIR FM Gold Chennai",
            streamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio021/chunklist.m3u8",
            frequency = "101.4 FM",
            city = "Chennai",
            state = "Tamil Nadu",
            country = "India",
            countryCode = "IN",
            language = "Tamil",
            lat = 13.0827,
            lon = 80.2707,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Tamil", "Gold", "Music", "Carnatic")
        ),

        // =================== KOLKATA ===================
        RadioStation(
            id = "air_gold_kolkata",
            name = "AIR FM Gold Kolkata",
            streamUrl = "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio057/hlspbaudio05764kbps.m3u8",
            frequency = "100.2 FM",
            city = "Kolkata",
            state = "West Bengal",
            country = "India",
            countryCode = "IN",
            language = "Bengali",
            lat = 22.5726,
            lon = 88.3639,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Bengali", "Music", "Rabindra Sangeet", "News")
        ),

        // =================== PUNJAB ===================
        RadioStation(
            id = "air_punjabi_jalandhar",
            name = "AIR Punjabi Jalandhar",
            streamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio001/playlist.m3u8",
            frequency = "102.7 FM",
            city = "Jalandhar",
            state = "Punjab",
            country = "India",
            countryCode = "IN",
            language = "Punjabi",
            lat = 31.3260,
            lon = 75.5762,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Punjabi", "Folk", "Bhangra", "Gurbani")
        ),

        // =================== AHMEDABAD ===================
        RadioStation(
            id = "air_ahmedabad",
            name = "AIR Ahmedabad",
            streamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio001/playlist.m3u8",
            frequency = "101.4 FM",
            city = "Ahmedabad",
            state = "Gujarat",
            country = "India",
            countryCode = "IN",
            language = "Gujarati",
            lat = 23.0225,
            lon = 72.5714,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Gujarati", "Garba", "Folk", "News")
        ),

        // =================== JAIPUR ===================
        RadioStation(
            id = "air_jaipur",
            name = "AIR Jaipur",
            streamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio001/playlist.m3u8",
            frequency = "101.2 FM",
            city = "Jaipur",
            state = "Rajasthan",
            country = "India",
            countryCode = "IN",
            language = "Hindi",
            lat = 26.9124,
            lon = 75.7873,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Rajasthani", "Folk", "Culture", "Hindi")
        ),

        // =================== KOCHI ===================
        RadioStation(
            id = "air_kochi",
            name = "AIR Kochi",
            streamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio001/playlist.m3u8",
            frequency = "102.3 FM",
            city = "Kochi",
            state = "Kerala",
            country = "India",
            countryCode = "IN",
            language = "Malayalam",
            lat = 9.9312,
            lon = 76.2673,
            logoUrl = null,
            bitrate = 128,
            tags = listOf("Malayalam", "South", "Melodies")
        )
    )

    // Pre-seeded favorites requested by the user: Nanded + top Maharashtra & National
    val defaultFavorites: List<RadioStation> = listOf(
        stations.first { it.id == "air_nanded" },
        stations.first { it.id == "vividh_bharati" },
        stations.first { it.id == "air_pune_fm" },
        stations.first { it.id == "radio_city_nanded" },
        stations.first { it.id == "red_fm_nanded" },
        stations.first { it.id == "my_fm_nanded" },
        stations.first { it.id == "air_delhi_gold" },
        stations.first { it.id == "bollywood_gaane_purane" }
    )

    val defaultFilter = "Maharashtra"

    val indianPlaces = listOf(
        "Maharashtra",
        "Nanded",
        "Pune",
        "Mumbai",
        "Nagpur",
        "Nashik",
        "Delhi NCR",
        "Bengaluru",
        "Hyderabad",
        "Kolkata",
        "Chennai",
        "Ahmedabad",
        "Jaipur",
        "Lucknow",
        "Chandigarh",
        "All India"
    )

    data class CountryFilter(val name: String, val code: String)

    val countries = listOf(
        CountryFilter("United States", "US"),
        CountryFilter("United Kingdom", "GB"),
        CountryFilter("Canada", "CA"),
        CountryFilter("Australia", "AU"),
        CountryFilter("Germany", "DE"),
        CountryFilter("France", "FR"),
        CountryFilter("Japan", "JP"),
        CountryFilter("United Arab Emirates", "AE"),
        CountryFilter("Pakistan", "PK"),
        CountryFilter("Bangladesh", "BD"),
        CountryFilter("Sri Lanka", "LK"),
        CountryFilter("Nepal", "NP"),
        CountryFilter("Russia", "RU"),
        CountryFilter("Brazil", "BR"),
        CountryFilter("Italy", "IT"),
        CountryFilter("Spain", "ES")
    )

    fun getStationsForIndianPlace(place: String): List<RadioStation> {
        return when (place) {
            "All India" -> stations
            "Maharashtra" -> stations.filter { it.state.equals("Maharashtra", ignoreCase = true) }
            "Delhi NCR" -> stations.filter { it.city.contains("Delhi", ignoreCase = true) || it.state.contains("Delhi", ignoreCase = true) }
            else -> stations.filter { it.city.equals(place, ignoreCase = true) || it.state.equals(place, ignoreCase = true) }
        }
    }

    val allCities = stations.map { it.city }.distinct()
    val allLanguages = listOf("All", "Marathi", "Hindi", "Punjabi", "Tamil", "Telugu", "Kannada", "Bengali", "Malayalam", "Gujarati")
    val allTags = listOf("All", "Music", "Bollywood", "Talk", "News", "Regional", "Retro")
}
