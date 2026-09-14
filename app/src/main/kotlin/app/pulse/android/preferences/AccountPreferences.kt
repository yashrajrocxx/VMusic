package app.pulse.android.preferences

import app.pulse.android.GlobalPreferencesHolder

object AccountPreferences : GlobalPreferencesHolder() {
    var innerTubeCookie by string("")
    var visitorData by string("")
    var dataSyncId by string("")
    var authUser by string("0")
    var accountName by string("")
    var accountEmail by string("")
    var accountChannelHandle by string("")
    var accountThumbnailUrl by string("")
    var useLoginForBrowse by boolean(true)

    val isLoggedIn: Boolean
        get() = "SAPISID" in parseCookieString(innerTubeCookie)

    fun parseCookieString(cookie: String): Map<String, String> {
        if (cookie.isBlank()) return emptyMap()
        return cookie.split("; ")
            .mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    fun clear() {
        innerTubeCookie = ""
        visitorData = ""
        dataSyncId = ""
        authUser = "0"
        accountName = ""
        accountEmail = ""
        accountChannelHandle = ""
        accountThumbnailUrl = ""
        app.pulse.android.playback.InnerTubeXPlayer.invalidateBundle()
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
    }
}
