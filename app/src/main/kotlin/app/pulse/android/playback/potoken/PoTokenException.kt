package app.pulse.android.playback.potoken

class PoTokenException(message: String) : Exception(message)

// Thrown if the WebView provided by the system is broken or unavailable
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}
