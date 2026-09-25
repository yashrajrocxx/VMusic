package app.pulse.android.backup

import android.content.Context
import android.net.Uri
import app.pulse.android.Database
import app.pulse.android.internal
import app.pulse.android.backup.BackupProtocol.BackupKind
import app.pulse.android.backup.BackupProtocol.BackupPreview
import app.pulse.android.backup.BackupProtocol.METRO_DB_ENTRY
import app.pulse.android.backup.BackupProtocol.METRO_DB_VERSION
import app.pulse.android.backup.BackupProtocol.METRO_SETTINGS_ENTRY
import app.pulse.android.backup.BackupProtocol.OCTET_STREAM_MIME
import app.pulse.android.backup.BackupProtocol.PULSE_DB_ENTRY
import app.pulse.android.backup.BackupProtocol.PULSE_DB_VERSION
import app.pulse.android.backup.BackupProtocol.PULSE_SETTINGS_JSON_ENTRY
import app.pulse.android.backup.BackupProtocol.firstDbEntry
import app.pulse.android.backup.BackupProtocol.hasEntry
import app.pulse.android.backup.BackupProtocol.isSQLiteMagic
import app.pulse.android.backup.BackupProtocol.isZipMagic
import app.pulse.android.backup.BackupProtocol.restartApp
import app.pulse.android.backup.BackupProtocol.sqliteTables
import app.pulse.android.backup.BackupProtocol.sqliteVersion
import app.pulse.android.backup.BackupProtocol.stopPlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Native full backups plus staging/detection/execution for every supported restore
 * source (Pulse, MetroList/forks, legacy raw `.db`).
 *
 * Restore is always staged, validated and previewed before anything destructive runs:
 * - MetroList-shaped sources are translated row-by-row (never file-swapped, since the
 *   schemas are incompatible and a blind swap would crash Room on open).
 * - Pulse/legacy sources are validated (expected tables, supported version) before the
 *   file swap, so an incompatible file can never brick the install.
 * Every successful restore ends in a clean app restart.
 */
object PulseBackup {
    enum class Failure { IoError, Empty, Unrecognized, Incompatible }

    data class Ready(val staged: BackupProtocol.StagedBackup, val preview: BackupPreview)

    sealed interface StageResult {
        data class Ok(val ready: Ready) : StageResult
        data class Rejected(val reason: Failure) : StageResult
    }

    suspend fun exportNative(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Database.checkpoint()
            context.contentResolver.openOutputStream(uri)?.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(PULSE_DB_ENTRY))
                    FileInputStream(Database.internal.path).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(PULSE_SETTINGS_JSON_ENTRY))
                    zip.write(SettingsSnapshot.dumpJson(context))
                    zip.closeEntry()
                    zip.finish()
                }
            } ?: error("No output stream")
            true
        }.getOrDefault(false)
    }

    suspend fun exportMetro(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.cacheDir, "metrolist_export.db")
            try {
                MetroListBackup.exportMetroDb(context, tmp)
                context.contentResolver.openOutputStream(uri)?.use { raw ->
                    ZipOutputStream(raw.buffered()).use { zip ->
                        zip.putNextEntry(ZipEntry(METRO_SETTINGS_ENTRY))
                        zip.write(PreferencesProto.encode(SettingsSnapshot.exportProtoEntries()))
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry(METRO_DB_ENTRY))
                        FileInputStream(tmp).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry(PULSE_SETTINGS_JSON_ENTRY))
                        zip.write(SettingsSnapshot.dumpJson(context))
                        zip.closeEntry()
                        zip.finish()
                    }
                } ?: error("No output stream")
            } finally {
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    suspend fun stageForRestore(context: Context, uri: Uri): StageResult = withContext(Dispatchers.IO) {
        runCatching {
            val incoming = File(context.cacheDir, "restore_incoming")
            val stagedDb = File(context.cacheDir, "restore_staged.db")
            stagedDb.delete()
            File("$stagedDb-wal").delete()
            File("$stagedDb-shm").delete()

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(incoming).use { output -> input.copyTo(output) }
            } ?: return@withContext StageResult.Rejected(Failure.IoError)
            if (incoming.length() == 0L) return@withContext StageResult.Rejected(Failure.Empty)

            val header = ByteArray(32)
            var headerRead = 0
            incoming.inputStream().use { stream ->
                while (headerRead < header.size) {
                    val read = stream.read(header, headerRead, header.size - headerRead)
                    if (read <= 0) break
                    headerRead += read
                }
            }

            if (isZipMagic(header)) {
                ZipFile(incoming).use { zip ->
                    val settingsProto = zip.getEntry(METRO_SETTINGS_ENTRY)?.let { entry ->
                        zip.getInputStream(entry).use { it.readBytes() }
                    }
                    val pulseJson = zip.getEntry(PULSE_SETTINGS_JSON_ENTRY)?.let { entry ->
                        zip.getInputStream(entry).use { it.readBytes() }
                    }
                    when {
                        zip.hasEntry(METRO_DB_ENTRY) -> {
                            copyEntry(zip, METRO_DB_ENTRY, stagedDb)
                            validateMetro(stagedDb, settingsProto, pulseJson)
                        }

                        zip.hasEntry(PULSE_DB_ENTRY) -> {
                            copyEntry(zip, PULSE_DB_ENTRY, stagedDb)
                            validatePulse(stagedDb, pulseJson)
                        }

                        else -> {
                            val dbEntry = zip.firstDbEntry()
                                ?: return@withContext StageResult.Rejected(Failure.Unrecognized)
                            copyEntry(zip, dbEntry, stagedDb)
                            validateLooseDb(stagedDb)
                        }
                    }
                }
            } else if (isSQLiteMagic(header)) {
                incoming.copyTo(stagedDb, overwrite = true)
                validateLooseDb(stagedDb)
            } else {
                StageResult.Rejected(Failure.Unrecognized)
            }
        }.getOrElse { StageResult.Rejected(Failure.IoError) }
    }

    suspend fun restore(context: Context, staged: BackupProtocol.StagedBackup): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                stopPlayerService(context)
                when (staged.kind) {
                    BackupKind.MetroZip, BackupKind.MetroDbOnly -> {
                        val dbFile = staged.dbFile ?: error("Missing staged database")
                        MetroListBackup.importMetroDb(dbFile.absolutePath)
                        val pulseJson = staged.pulseSettingsJson
                        if (pulseJson != null && SettingsSnapshot.applyJson(context, pulseJson)) {
                            // Full-fidelity settings from our own export take precedence.
                        } else {
                            staged.settingsProto?.let { bytes ->
                                PreferencesProto.decode(bytes)?.let { entries ->
                                    SettingsSnapshot.applyProtoEntries(context, entries)
                                }
                            }
                        }
                    }

                    BackupKind.PulseZip -> {
                        val dbFile = staged.dbFile ?: error("Missing staged database")
                        val livePath = requireNotNull(Database.internal.path) { "Missing live database" }
                        Database.checkpoint()
                        Database.internal.close()
                        dbFile.copyTo(File(livePath), overwrite = true)
                        File("$livePath-wal").delete()
                        File("$livePath-shm").delete()
                        staged.pulseSettingsJson?.let { SettingsSnapshot.applyJson(context, it) }
                    }

                    BackupKind.LegacyDb -> {
                        val dbFile = staged.dbFile ?: error("Missing staged database")
                        val livePath = requireNotNull(Database.internal.path) { "Missing live database" }
                        Database.checkpoint()
                        Database.internal.close()
                        dbFile.copyTo(File(livePath), overwrite = true)
                        File("$livePath-wal").delete()
                        File("$livePath-shm").delete()
                    }

                    BackupKind.Unknown -> error("Unknown backup kind")
                }
                restartApp(context)
                true
            }.getOrDefault(false)
        }

    // region Staging internals

    private fun copyEntry(zip: ZipFile, name: String, dest: File) {
        zip.getInputStream(zip.getEntry(name)).use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    private fun validateMetro(
        stagedDb: File,
        settingsProto: ByteArray?,
        pulseJson: ByteArray?
    ): StageResult {
        val version = sqliteVersion(stagedDb.absolutePath)
        val tables = sqliteTables(stagedDb.absolutePath)
        if (version !in 1..METRO_DB_VERSION || "song" !in tables) {
            return StageResult.Rejected(Failure.Incompatible)
        }
        val counts = MetroListBackup.previewMetroDb(stagedDb.absolutePath)
            ?: return StageResult.Rejected(Failure.IoError)
        val entries = settingsProto?.let { PreferencesProto.decode(it) }.orEmpty()
        return StageResult.Ok(
            Ready(
                staged = BackupProtocol.StagedBackup(BackupKind.MetroZip, stagedDb, settingsProto, pulseJson),
                preview = BackupPreview(
                    kind = BackupKind.MetroZip,
                    dbVersion = version,
                    songs = counts.songs,
                    liked = counts.liked,
                    playlists = counts.playlists,
                    events = counts.events,
                    searches = counts.searches,
                    hasAuthData = SettingsSnapshot.hasAuthData(entries),
                    accountName = SettingsSnapshot.accountName(entries),
                    accountEmail = SettingsSnapshot.accountEmail(entries)
                )
            )
        )
    }

    private fun validatePulse(stagedDb: File, pulseJson: ByteArray?): StageResult {
        val version = sqliteVersion(stagedDb.absolutePath)
        val tables = sqliteTables(stagedDb.absolutePath)
        if (version !in 1..PULSE_DB_VERSION || "Song" !in tables) {
            return StageResult.Rejected(Failure.Incompatible)
        }
        val counts = MetroListBackup.previewPulseDb(stagedDb.absolutePath)
            ?: return StageResult.Rejected(Failure.IoError)
        val (hasAuth, accountName, accountEmail) = pulseJsonAuth(pulseJson)
        return StageResult.Ok(
            Ready(
                staged = BackupProtocol.StagedBackup(BackupKind.PulseZip, stagedDb, null, pulseJson),
                preview = BackupPreview(
                    kind = BackupKind.PulseZip,
                    dbVersion = version,
                    songs = counts.songs,
                    liked = counts.liked,
                    playlists = counts.playlists,
                    events = counts.events,
                    searches = counts.searches,
                    hasAuthData = hasAuth,
                    accountName = accountName,
                    accountEmail = accountEmail
                )
            )
        )
    }

    private fun validateLooseDb(stagedDb: File): StageResult {
        val tables = sqliteTables(stagedDb.absolutePath)
        return when {
            "song" in tables -> {
                val withSettings = BackupProtocol.StagedBackup(BackupKind.MetroDbOnly, stagedDb, null, null)
                when (val result = validateMetro(stagedDb, null, null)) {
                    is StageResult.Ok -> StageResult.Ok(result.ready.copy(staged = withSettings))
                    is StageResult.Rejected -> result
                }
            }

            "Song" in tables -> {
                val version = sqliteVersion(stagedDb.absolutePath)
                if (version !in 1..PULSE_DB_VERSION) return StageResult.Rejected(Failure.Incompatible)
                val counts = MetroListBackup.previewPulseDb(stagedDb.absolutePath)
                    ?: return StageResult.Rejected(Failure.IoError)
                StageResult.Ok(
                    Ready(
                        staged = BackupProtocol.StagedBackup(BackupKind.LegacyDb, stagedDb, null, null),
                        preview = BackupPreview(
                            kind = BackupKind.LegacyDb,
                            dbVersion = version,
                            songs = counts.songs,
                            liked = counts.liked,
                            playlists = counts.playlists,
                            events = counts.events,
                            searches = counts.searches
                        )
                    )
                )
            }

            else -> StageResult.Rejected(Failure.Unrecognized)
        }
    }

    private fun pulseJsonAuth(pulseJson: ByteArray?): Triple<Boolean, String?, String?> {
        if (pulseJson == null) return Triple(false, null, null)
        return runCatching {
            val prefs = JSONObject(pulseJson.toString(Charsets.UTF_8)).getJSONArray("prefs")
            var cookie = ""
            var name: String? = null
            var email: String? = null
            for (i in 0 until prefs.length()) {
                val entry = prefs.getJSONObject(i)
                when (entry.optString("k")) {
                    "innerTubeCookie" -> cookie = entry.optString("v")
                    "accountName" -> name = entry.optString("v").takeIf { it.isNotBlank() }
                    "accountEmail" -> email = entry.optString("v").takeIf { it.isNotBlank() }
                }
            }
            Triple("SAPISID" in cookie, name, email)
        }.getOrDefault(Triple(false, null, null))
    }

    // endregion

    fun exportMime(): String = OCTET_STREAM_MIME

    fun restoreMimeTypes(): Array<String> = arrayOf(
        OCTET_STREAM_MIME,
        "application/zip",
        "application/x-zip-compressed",
        "application/vnd.sqlite3",
        "application/x-sqlite3"
    )
}
