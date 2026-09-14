package app.pulse.android.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.pulse.android.LocalPlayerAwareWindowInsets
import app.pulse.android.R
import app.pulse.android.playback.InnerTubeXPlayer
import app.pulse.android.preferences.AccountPreferences
import app.pulse.android.ui.components.themed.IconButton
import app.pulse.android.utils.bold
import app.pulse.android.utils.medium
import app.pulse.core.ui.LocalAppearance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.security.MessageDigest

private class LoginJsInterface {
    var onVisitorDataReceived: ((String?) -> Unit)? = null
    var onDataSyncIdReceived: ((String?) -> Unit)? = null
    var onAuthUserReceived: ((String?) -> Unit)? = null

    @JavascriptInterface
    fun onRetrieveVisitorData(visitorData: String?) {
        onVisitorDataReceived?.invoke(visitorData)
    }

    @JavascriptInterface
    fun onRetrieveDataSyncId(dataSyncId: String?) {
        onDataSyncIdReceived?.invoke(dataSyncId)
    }

    @JavascriptInterface
    fun onRetrieveAuthUser(authUser: String?) {
        onAuthUserReceived?.invoke(authUser)
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun LoginScreen(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val (colorPalette, typography) = LocalAppearance.current
    val coroutineScope = rememberCoroutineScope()
    val jsInterface = remember { LoginJsInterface() }
    var isChecking by remember { mutableStateOf(false) }
    var checkingStatusText by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Only intercept back if the WebView itself has navigation history to step back into.
    // When canGoBack is false, BackHandler is disabled, allowing ViMusic's RouteHandler
    // predictive back handler to dismiss the screen naturally without infinite recursion.
    BackHandler(enabled = webViewRef?.canGoBack() == true && !isChecking) {
        webViewRef?.goBack()
    }

    // Clean up WebView resources on exit to prevent memory leaks and orphaned JS callbacks
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    clearHistory()
                    removeAllViews()
                    destroy()
                }
                webViewRef = null
            }
        }
    }

    suspend fun fetchAccountInfo(cookie: String) = withContext(Dispatchers.IO) {
        runCatching {
            val cookieMap = AccountPreferences.parseCookieString(cookie)
            val sapisid = cookieMap["SAPISID"] ?: cookieMap["__Secure-3PAPISID"] ?: return@runCatching
            val origin = "https://music.youtube.com"
            val currentTime = System.currentTimeMillis() / 1000
            val md = MessageDigest.getInstance("SHA-1")
            val hashBytes = md.digest("$currentTime $sapisid $origin".toByteArray(Charsets.UTF_8))
            val sapisidHash = hashBytes.joinToString("") { "%02x".format(it) }
            val authHeader = "SAPISIDHASH ${currentTime}_$sapisidHash"

            val client = OkHttpClient()
            val requestBody = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20240101.01.00"}}}"""
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false")
                .post(requestBody)
                .header("Cookie", cookie)
                .header("Authorization", authHeader)
                .header("Origin", origin)
                .header("Referer", "$origin/")
                .header("X-Origin", origin)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@runCatching

            val json = Json { ignoreUnknownKeys = true }
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

            val header = jsonResponse["actions"]
                ?.jsonArray?.getOrNull(0)
                ?.jsonObject?.get("openPopupAction")
                ?.jsonObject?.get("popup")
                ?.jsonObject?.get("multiPageMenuRenderer")
                ?.jsonObject?.get("header")
                ?.jsonObject?.get("activeAccountHeaderRenderer")
                ?.jsonObject

            if (header != null) {
                val name = header["accountName"]
                    ?.jsonObject?.get("runs")
                    ?.jsonArray?.getOrNull(0)
                    ?.jsonObject?.get("text")
                    ?.jsonPrimitive?.contentOrNull

                val email = header["email"]
                    ?.jsonObject?.get("runs")
                    ?.jsonArray?.getOrNull(0)
                    ?.jsonObject?.get("text")
                    ?.jsonPrimitive?.contentOrNull

                val thumbnailUrl = header["accountPhoto"]
                    ?.jsonObject?.get("thumbnails")
                    ?.jsonArray?.getOrNull(0)
                    ?.jsonObject?.get("url")
                    ?.jsonPrimitive?.contentOrNull

                if (!name.isNullOrBlank()) {
                    AccountPreferences.accountName = name
                }
                if (!email.isNullOrBlank()) {
                    AccountPreferences.accountEmail = email
                }
                if (!thumbnailUrl.isNullOrBlank()) {
                    AccountPreferences.accountThumbnailUrl = thumbnailUrl
                }
                Timber.d("Account info fetched: name=$name, email=$email")
            }
        }.onFailure {
            Timber.w(it, "Failed to retrieve user profile details")
        }
    }

    suspend fun extractAuthData(webView: WebView?): Boolean {
        val view = webView ?: return false
        repeat(20) {
            val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
            val cookieMap = AccountPreferences.parseCookieString(cookie)

            val visitorDataDeferred = CompletableDeferred<String?>()
            val dataSyncIdDeferred = CompletableDeferred<String?>()
            val authUserDeferred = CompletableDeferred<String?>()

            jsInterface.onVisitorDataReceived = { if (!visitorDataDeferred.isCompleted) visitorDataDeferred.complete(it) }
            jsInterface.onDataSyncIdReceived = { if (!dataSyncIdDeferred.isCompleted) dataSyncIdDeferred.complete(it) }
            jsInterface.onAuthUserReceived = { if (!authUserDeferred.isCompleted) authUserDeferred.complete(it) }

            view.loadUrl("javascript:Android.onRetrieveVisitorData(window.yt&&window.yt.config_?window.yt.config_.VISITOR_DATA:null)")
            view.loadUrl("javascript:Android.onRetrieveDataSyncId(window.yt&&window.yt.config_?window.yt.config_.DATASYNC_ID:null)")
            view.loadUrl("javascript:Android.onRetrieveAuthUser(window.yt&&window.yt.config_?String(window.yt.config_.SESSION_INDEX||0):'0')")

            val visitorData = withTimeoutOrNull(1000) { visitorDataDeferred.await() }
            val dataSyncId = withTimeoutOrNull(1000) { dataSyncIdDeferred.await() }
            val authUser = withTimeoutOrNull(1000) { authUserDeferred.await() }

            if ("SAPISID" in cookieMap) {
                AccountPreferences.innerTubeCookie = cookie
                if (!visitorData.isNullOrBlank()) {
                    AccountPreferences.visitorData = visitorData
                }
                if (!dataSyncId.isNullOrBlank()) {
                    AccountPreferences.dataSyncId = dataSyncId.substringBefore("||")
                }
                if (!authUser.isNullOrBlank()) {
                    AccountPreferences.authUser = authUser.filter(Char::isDigit).ifBlank { "0" }
                }
                InnerTubeXPlayer.invalidateBundle()
                Timber.d("Google Login successfully captured auth cookies!")

                checkingStatusText = context.getString(R.string.fetching_account_info)
                fetchAccountInfo(cookie)
                return true
            }
            delay(500)
        }
        return false
    }

    fun handleAuthenticatedPage() {
        if (isChecking) return
        isChecking = true
        coroutineScope.launch {
            val success = extractAuthData(webViewRef)
            if (success) {
                delay(600)
                onDismiss()
            } else {
                isChecking = false
                checkingStatusText = null
            }
        }
    }

    val insets = LocalPlayerAwareWindowInsets.current
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        .asPaddingValues()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorPalette.background0)
            .padding(insets),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDismiss,
                icon = R.drawable.chevron_back,
                color = colorPalette.text,
            )

            Spacer(modifier = Modifier.width(12.dp))

            BasicText(
                text = stringResource(R.string.login_with_google),
                style = typography.m.bold.copy(color = colorPalette.text),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webViewContext ->
                    WebView(webViewContext).apply {
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val scheme = request.url.scheme
                                if (scheme == "http" || scheme == "https") return false
                                if (scheme == "intent") {
                                    runCatching {
                                        Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME).apply {
                                            addCategory(Intent.CATEGORY_BROWSABLE)
                                            component = null
                                            selector = null
                                        }.let(context::startActivity)
                                    }
                                }
                                return true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                val pageUri = url?.let(Uri::parse)
                                if (pageUri?.scheme == "https" && pageUri.host == "music.youtube.com") {
                                    handleAuthenticatedPage()
                                }
                            }
                        }
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }
                        addJavascriptInterface(jsInterface, "Android")
                        webViewRef = this
                        loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
                    }
                },
            )

            if (isChecking) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorPalette.background0.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = colorPalette.accent)
                        Spacer(modifier = Modifier.height(16.dp))
                        BasicText(
                            text = checkingStatusText ?: stringResource(R.string.please_wait),
                            style = typography.s.medium.copy(color = colorPalette.text),
                        )
                    }
                }
            }
        }
    }
}
