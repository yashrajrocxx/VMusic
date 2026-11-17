package app.pulse.android.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pulse.android.preferences.DataPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/**
 * Scheduled, full-fidelity backups into a user-picked SAF folder.
 *
 * The owner's plan for automatic backups is time-based rather than
 * change-based: the user picks a time in settings and WorkManager wakes up
 * once per day (or week) to write the same native zip that the manual "Back up
 * everything" action produces. Only the newest [KEEP_BACKUPS] files are kept.
 */
object AutoBackupManager {
    const val FILE_PREFIX = "Pulse_auto_backup_"
    const val KEEP_BACKUPS = 7

    private val mutex = Mutex()

    fun treeUriOrNull(): Uri? =
        DataPreferences.autoBackupTreeUri.takeIf { it.isNotBlank() }?.toUri()

    fun hasFolderAccess(context: Context): Boolean {
        val treeUri = treeUriOrNull() ?: return false

        return context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
    }

    fun takePersistableAccess(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    fun folderName(context: Context): String? {
        val treeUri = treeUriOrNull() ?: return null

        return runCatching {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )

            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: Uri.decode(treeUri.lastPathSegment.orEmpty()).ifBlank { null }
    }

    /**
     * Writes one backup into the configured folder and prunes older ones.
     * Records the outcome in preferences so the settings screen can show it.
     *
     * [requireEnabled] is false for the manual "Back up now" action, which
     * should also work while the schedule itself is turned off.
     */
    suspend fun runBackup(context: Context, requireEnabled: Boolean = true): Boolean = mutex.withLock {
        val enabled = DataPreferences.autoBackupEnabled
        val treeUri = treeUriOrNull()

        if (
            (requireEnabled && !enabled) ||
            treeUri == null ||
            !hasFolderAccess(context)
        ) {
            DataPreferences.autoBackupLastRun = System.currentTimeMillis()
            DataPreferences.autoBackupLastSuccess = false
            DataPreferences.autoBackupLastError = when {
                requireEnabled && !enabled -> "Automatic backup is disabled"
                treeUri == null -> "No backup folder selected"
                else -> "Access to the backup folder was lost"
            }
            return@withLock false
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val parentDocument = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                val name = "$FILE_PREFIX${
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                }.zip"

                val documentUri = DocumentsContract.createDocument(
                    resolver,
                    parentDocument,
                    "application/zip",
                    name
                ) ?: error("The backup folder refused to create a file")

                if (!PulseBackup.exportNative(context, documentUri)) {
                    runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
                    error("Could not write the backup file")
                }

                prune(context, treeUri)
            }
        }

        DataPreferences.autoBackupLastRun = System.currentTimeMillis()
        DataPreferences.autoBackupLastSuccess = result.isSuccess
        DataPreferences.autoBackupLastError = result.exceptionOrNull()?.message.orEmpty()

        result.isSuccess
    }

    private fun prune(context: Context, treeUri: Uri) {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )

        val backups = mutableListOf<Pair<Uri, String>>()

        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1) ?: continue

                if (!displayName.startsWith(FILE_PREFIX) ||
                    !displayName.endsWith(".zip", ignoreCase = true)
                ) continue

                backups += DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId) to displayName
            }
        }

        // Names carry a yyyyMMdd_HHmmss stamp, so lexical order is chronological.
        backups
            .sortedByDescending { it.second }
            .drop(KEEP_BACKUPS)
            .forEach { (uri, _) ->
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            }
    }
}

class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!DataPreferences.autoBackupEnabled) return Result.success()

        runCatching { AutoBackupManager.runBackup(applicationContext) }
        return Result.success()
    }
}

object AutoBackupScheduler {
    private const val WORK_NAME = "auto_backup_worker"

    /**
     * Keeps an existing schedule alive across app starts. When the stored
     * preferences no longer match the schedule (for example after restoring a
     * backup), the work is rebuilt instead of kept.
     */
    fun ensure(context: Context) = enqueue(context, force = false)

    /** Rebuilds the schedule after the user changes time, frequency or folder. */
    fun reschedule(context: Context) = enqueue(context, force = true)

    private fun enqueue(context: Context, force: Boolean) {
        val workManager = WorkManager.getInstance(context)

        if (!DataPreferences.autoBackupEnabled ||
            DataPreferences.autoBackupTreeUri.isBlank()
        ) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val period = when (DataPreferences.autoBackupFrequency) {
            DataPreferences.AutoBackupFrequency.Daily -> 1.days
            DataPreferences.AutoBackupFrequency.Weekly -> 7.days
        }

        val hour = DataPreferences.autoBackupHour
        val minute = DataPreferences.autoBackupMinute
        // The folder URI is part of the signature: WorkManager state is not
        // included in backups, so after a reinstall + restore the stored
        // signature can match while no work actually exists.
        val signature =
            "${DataPreferences.autoBackupFrequency.name}:$hour:$minute:${DataPreferences.autoBackupTreeUri.hashCode()}"

        // Deciding KEEP vs rebuild needs the query below, which blocks — so
        // the whole decision runs off the caller's thread.
        CoroutineScope(Dispatchers.IO).launch {
            val hasWork = runCatching {
                workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                    .any { !it.state.isFinished }
            }.getOrDefault(false)

            val policy = if (force || !hasWork || signature != DataPreferences.autoBackupScheduleSignature)
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            else ExistingPeriodicWorkPolicy.KEEP

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(period.toJavaDuration())
                .setInitialDelay(
                    nextBackupDelayMillis(hour, minute),
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(Constraints(requiresBatteryNotLow = true))
                .addTag(WORK_NAME)
                .build()

            runCatching {
                workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
            }.onFailure { it.printStackTrace() }
            DataPreferences.autoBackupScheduleSignature = signature
        }
    }

    private fun nextBackupDelayMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
    }
}
