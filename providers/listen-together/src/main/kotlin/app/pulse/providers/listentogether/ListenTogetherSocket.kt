package app.pulse.providers.listentogether

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/** Transport-neutral socket abstraction so the client stays testable. */
interface ListenTogetherSocketListener {
    fun onOpen()
    fun onMessage(frame: ByteArray)
    fun onFailure(error: Throwable)
    fun onClosed(cleanly: Boolean)
}

interface ListenTogetherSocket {
    fun send(frame: ByteString): Boolean
    fun closeQuietly()
}

interface ListenTogetherSocketFactory {
    fun create(url: String, headers: Map<String, String>, listener: ListenTogetherSocketListener): ListenTogetherSocket
}

/**
 * OkHttp binary socket. Keep-alive control pings ride on OkHttp's own
 * pingInterval; the protocol-level `ping` message is a separate watch curve
 * the client sends through [ListenTogetherClient].
 */
class OkHttpListenTogetherSocketFactory : ListenTogetherSocketFactory {

    /** One client for the whole process: reuses connection pools and pingers. */
    private val shared by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun create(
        url: String,
        headers: Map<String, String>,
        listener: ListenTogetherSocketListener
    ): ListenTogetherSocket {
        val client = shared

        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val socketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, frame: ByteString) {
                listener.onMessage(frame.toByteArray())
            }

            /** Text frames are not part of metroproto — fatal for the exchange. */
            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onFailure(IllegalArgumentException("unexpected text frame (JSON not allowed)"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(cleanly = true)
            }
        }

        val webSocket = client.newWebSocket(request, socketListener)
        return OkHttpListenTogetherSocket(webSocket, client)
    }
}

private class OkHttpListenTogetherSocket(
    private val webSocket: WebSocket,
    private val client: OkHttpClient
) : ListenTogetherSocket {
    override fun send(frame: ByteString): Boolean = webSocket.send(frame)

    override fun closeQuietly() {
        runCatching { webSocket.close(1000, null) }
    }
}
