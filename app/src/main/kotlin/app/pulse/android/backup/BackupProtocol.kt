package app.pulse.android.backup

import android.content.Context
import android.content.Intent
import app.pulse.android.service.PlayerService
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Shared constants and helpers for the backup ecosystem.
 *
 * Two interoperable formats are supported:
 * - Pulse native (`.zip`): `data.db` + `pulse_settings.json`. Complete and self-contained.
 * - MetroList compatible (`.backup`): `song.db` (MetroList v38 schema) +
 *   `settings.preferences_pb` (DataStore proto) + `pulse_settings.json` (round-trip extras,
 *   ignored by MetroList). Restorable in MetroList, InnerTune and other forks, whose own
 *   backups restore here through the same code path.
 * - Legacy raw `.db` files (old Pulse single-file backups) keep working via content sniffing.
 */
object BackupProtocol {
    const val METRO_DB_ENTRY = "song.db"
    const val METRO_SETTINGS_ENTRY = "settings.preferences_pb"
    const val METRO_DB_VERSION = 38
    const val METRO_ROOM_IDENTITY_HASH = "bcf76ed36a3cb785f239e5cfbd28440e"

    const val PULSE_DB_ENTRY = "data.db"
    const val PULSE_SETTINGS_JSON_ENTRY = "pulse_settings.json"
    const val PULSE_DB_VERSION = 32

    const val OCTET_STREAM_MIME = "application/octet-stream"

    enum class BackupKind {
        PulseZip,
        MetroZip,
        MetroDbOnly,
        LegacyDb,
        Unknown
    }

    data class BackupPreview(
        val kind: BackupKind,
        val dbVersion: Int = -1,
        val songs: Int = 0,
        val liked: Int = 0,
        val playlists: Int = 0,
        val events: Int = 0,
        val searches: Int = 0,
        val hasAuthData: Boolean = false,
        val accountName: String? = null,
        val accountEmail: String? = null
    )

    /** Result of staging an incoming backup file into the cache dir for inspection. */
    data class StagedBackup(
        val kind: BackupKind,
        /** Staged SQLite file, when the backup carries one. */
        val dbFile: File?,
        /** Raw `settings.preferences_pb` bytes, when present. */
        val settingsProto: ByteArray?,
        /** Raw `pulse_settings.json` bytes, when present (our own exports). */
        val pulseSettingsJson: ByteArray?
    )

    fun isZipMagic(header: ByteArray): Boolean =
        header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

    fun isSQLiteMagic(header: ByteArray): Boolean =
        header.size >= 16 && header.decodeToString().startsWith("SQLite format 3\u0000")

    fun ZipFile.hasEntry(name: String): Boolean = getEntry(name) != null

    fun ZipFile.firstDbEntry(): String? {
        val entries = entries().toList()
        return entries.firstOrNull {
            !it.isDirectory && it.name.endsWith(".db", ignoreCase = true) &&
                !it.name.endsWith("-wal", ignoreCase = true) &&
                !it.name.endsWith("-shm", ignoreCase = true)
        }?.name
    }

    /** Tables present in a SQLite file, used to route restores without involving Room. */
    fun sqliteTables(dbPath: String): Set<String> {
        return try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table'", null
                ).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun sqliteVersion(dbPath: String): Int {
        return try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { it.version }
        } catch (_: Exception) {
            -1
        }
    }

    fun parseDurationToSeconds(durationText: String?): Int {
        if (durationText.isNullOrBlank()) return -1
        return runCatching {
            val parts = durationText.trim().split(":").map { it.toLong() }
            if (parts.isEmpty() || parts.size > 3) return -1
            var total = 0L
            for (part in parts) total = total * 60 + part
            total.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }.getOrDefault(-1)
    }

    fun formatSecondsToDuration(totalSeconds: Int?): String? {
        if (totalSeconds == null || totalSeconds < 0) return null
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    /** Looks-like-synced-lyrics heuristic used when the source stores a single text blob. */
    fun looksLikeSyncedLyrics(text: String): Boolean {
        if (!text.contains('[')) return false
        var hits = 0
        var index = 0
        while (hits < 3) {
            index = text.indexOf('[', index)
            if (index == -1) return false
            val close = text.indexOf(']', index + 1)
            if (close == -1 || close - index > 12) {
                index++
                continue
            }
            val inner = text.substring(index + 1, close)
            if (inner.length >= 4 && inner[0].isDigit() && inner[2] == ':') hits++
            index = close + 1
        }
        return true
    }

    fun stopPlayerService(context: Context) {
        runCatching {
            context.stopService(Intent(context, PlayerService::class.java))
        }
    }

    /** Relaunches the app from a clean process, mirroring MetroList's restore restart. */
    fun restartApp(context: Context) {
        runCatching {
            val intent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            context.startActivity(intent)
        }
        exitProcess(0)
    }
}
