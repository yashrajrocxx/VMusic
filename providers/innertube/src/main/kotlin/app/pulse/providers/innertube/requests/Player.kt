package app.pulse.providers.innertube.requests

import app.pulse.providers.innertube.Innertube
import app.pulse.providers.innertube.models.Context
import app.pulse.providers.innertube.models.PlayerResponse
import app.pulse.providers.innertube.models.bodies.PlayerBody
import app.pulse.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.util.generateNonce
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private suspend fun Innertube.postPlayer(
    body: PlayerBody,
    context: Context
): PlayerResponse? {
    val cpn = generateNonce(16).decodeToString()
    return runCatchingCancellable {
        client.post(if (context.client.music) PLAYER_MUSIC else PLAYER) {
            setBody(
                body.copy(
                    context = context,
                    cpn = cpn
                )
            )

            context.apply()

            parameter("t", generateNonce(12))
            header("X-Goog-Api-Format-Version", "2")
            parameter("id", body.videoId)
        }.body<PlayerResponse>().also { logger.info("Got $it") }
    }?.getOrNull()?.copy(
        cpn = cpn,
        context = context
    )
}

private suspend fun Innertube.tryContexts(
    body: PlayerBody,
    checkIsValid: Boolean,
    vararg contexts: Context
): PlayerResponse? {
    contexts.forEach { rawContext ->
        if (!currentCoroutineContext().isActive) return null
        val context = sessionVisitorData?.let { visitor ->
            rawContext.copy(client = rawContext.client.copy(defaultVisitorData = visitor))
        } ?: rawContext
        if (isStreamClientBlocked(body.videoId, context.client.clientName)) {
            logger.info("Skipping blocked stream client ${context.client.clientName} for ${body.videoId}")
            return@forEach
        }

        logger.info("Trying ${context.client.clientName} ${context.client.clientVersion} ${context.client.platform}")
        postPlayer(body, context)
            ?.takeIf { checkIsValid && it.isValid }
            ?.let {
                markStreamClientSuccessful(body.videoId, context.client.clientName)
                return it
            }
    }

    return null
}

private val PlayerResponse.isValid
    get() = playabilityStatus?.status == "OK" &&
        streamingData?.adaptiveFormats?.any { !it.url.isNullOrEmpty() } == true

private val Innertube.playbackContexts
    get() = listOf(
        Context.DefaultAndroidVr,
        Context.DefaultIOS,
        Context.DefaultWeb,
        Context.DefaultAndroidMusic,
        Context.DefaultTV
    )

suspend fun Innertube.player(
    body: PlayerBody,
    checkIsValid: Boolean = true
): Result<PlayerResponse?>? = runCatchingCancellable {
    ensureVisitorData()
    tryContexts(
        body = body,
        checkIsValid = checkIsValid,
        *playbackContexts.toTypedArray()
    )
}

/**
 * Raw YouTube playability status for one cheap context (no validity filtering).
 * Used on the stream-failure path only to tell "login required / restricted" apart
 * from genuinely unplayable videos, so the player can offer Google login exactly
 * when authentication — not the video — is the blocker.
 */
suspend fun Innertube.playerPlayabilityStatus(videoId: String): String? = runCatchingCancellable {
    ensureVisitorData()
    val rawContext = Context.DefaultWeb
    val context = sessionVisitorData?.let { visitor ->
        rawContext.copy(client = rawContext.client.copy(defaultVisitorData = visitor))
    } ?: rawContext
    postPlayer(PlayerBody(videoId = videoId), context)?.playabilityStatus?.status
}?.getOrNull()
