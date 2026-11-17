package app.pulse.android.listentogether

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.pulse.android.Dependencies
import app.pulse.android.R
import app.pulse.android.utils.ActionReceiver
import app.pulse.core.ui.utils.isAtLeastAndroid6

/**
 * Host-side actions for Together request notifications. The notification is
 * the only approve prompt that surfaces no matter which tab or screen the
 * host is on (or with the app backgrounded): the in-app cards exist solely
 * on the Together screen.
 *
 * Manifest-registered (exported=false); intents are explicit to this package
 * with the request identity in extras, since [ActionReceiver.Action.pendingIntent]
 * carries no extras and one action serves many concurrent requests.
 */
class TogetherActionReceiver : ActionReceiver("together") {

    val approveSuggestion by action { context, intent ->
        val id = intent.getStringExtra(EXTRA_SUGGESTION_ID).orEmpty()
        if (id.isNotBlank()) ListenTogetherCoordinator.approveSuggestionById(id)
        TogetherNotifications.cancel(context, TogetherNotifications.idForSuggestion(id))
    }

    val rejectSuggestion by action { context, intent ->
        val id = intent.getStringExtra(EXTRA_SUGGESTION_ID).orEmpty()
        if (id.isNotBlank()) ListenTogetherCoordinator.rejectSuggestionById(id)
        TogetherNotifications.cancel(context, TogetherNotifications.idForSuggestion(id))
    }

    val approveJoin by action { context, intent ->
        val userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()
        if (userId.isNotBlank()) ListenTogetherCoordinator.approveJoinById(userId)
        TogetherNotifications.cancel(context, TogetherNotifications.idForJoin(userId))
    }

    val rejectJoin by action { context, intent ->
        val userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()
        if (userId.isNotBlank()) ListenTogetherCoordinator.rejectJoinById(userId)
        TogetherNotifications.cancel(context, TogetherNotifications.idForJoin(userId))
    }

    companion object {
        const val EXTRA_SUGGESTION_ID = "suggestion_id"
        const val EXTRA_USER_ID = "user_id"

        private fun broadcast(
            context: Context,
            action: String,
            requestCode: Int,
            extras: Intent.() -> Unit
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).setPackage(context.packageName).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (isAtLeastAndroid6) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        fun approveSuggestionIntent(context: Context, suggestionId: Int, rawId: String) =
            broadcast(context, "together.approveSuggestion", suggestionId) {
                putExtra(EXTRA_SUGGESTION_ID, rawId)
            }

        fun rejectSuggestionIntent(context: Context, suggestionId: Int, rawId: String) =
            broadcast(context, "together.rejectSuggestion", suggestionId + 1) {
                putExtra(EXTRA_SUGGESTION_ID, rawId)
            }

        fun approveJoinIntent(context: Context, notificationId: Int, userId: String) =
            broadcast(context, "together.approveJoin", notificationId) {
                putExtra(EXTRA_USER_ID, userId)
            }

        fun rejectJoinIntent(context: Context, notificationId: Int, userId: String) =
            broadcast(context, "together.rejectJoin", notificationId + 1) {
                putExtra(EXTRA_USER_ID, userId)
            }
    }
}

/**
 * Heads-up host prompts for suggestions (including Ask-to-play) and join
 * requests. One notification per request id so concurrent requests stack;
 * each is cancelled when its card resolves or the room tears down.
 */
object TogetherNotifications {
    private const val CHANNEL_ID = "together_channel_id"

    private val postedIds = mutableSetOf<Int>()
    private val lock = Any()

    fun idForSuggestion(suggestionId: String) = suggestionId.hashCode()
    fun idForJoin(userId: String) = userId.hashCode()

    fun showSuggestion(
        context: Context,
        suggestion: TogetherSuggestion
    ) {
        val id = idForSuggestion(suggestion.suggestionId)
        val title = suggestion.track?.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.together_suggestions)
        val text = if (suggestion.isPlayRequest) {
            context.getString(R.string.together_wants_to_play, suggestion.fromUsername)
        } else {
            context.getString(R.string.together_suggested_song, suggestion.fromUsername)
        }

        show(
            context = context,
            id = id,
            title = title,
            text = text,
            approve = TogetherActionReceiver.approveSuggestionIntent(context, id, suggestion.suggestionId),
            reject = TogetherActionReceiver.rejectSuggestionIntent(context, id, suggestion.suggestionId)
        )
    }

    fun showJoinRequest(context: Context, userId: String, username: String) {
        val id = idForJoin(userId)

        show(
            context = context,
            id = id,
            title = context.getString(R.string.together_join_requests),
            text = context.getString(R.string.together_wants_to_join, username),
            approve = TogetherActionReceiver.approveJoinIntent(context, id, userId),
            reject = TogetherActionReceiver.rejectJoinIntent(context, id, userId)
        )
    }

    private fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        approve: PendingIntent,
        reject: PendingIntent
    ) = runCatching {
        val content = context.packageManager
            .getLaunchIntentForPackage(context.packageName)?.let { launch ->
                PendingIntent.getActivity(
                    context,
                    id,
                    launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (isAtLeastAndroid6) PendingIntent.FLAG_IMMUTABLE else 0)
                )
            }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .apply { content?.let(::setContentIntent) }
            .addAction(0, context.getString(R.string.together_approve), approve)
            .addAction(0, context.getString(R.string.together_reject), reject)
            .build()

        synchronized(lock) { postedIds += id }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) = runCatching {
        synchronized(lock) { postedIds -= id }
        NotificationManagerCompat.from(context).cancel(id)
    }

    fun cancelAll(context: Context = Dependencies.application) = runCatching {
        val ids = synchronized(lock) {
            postedIds.toList().also { postedIds.clear() }
        }
        val manager = NotificationManagerCompat.from(context)
        ids.forEach(manager::cancel)
    }
}
