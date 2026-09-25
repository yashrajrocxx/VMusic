package app.pulse.android.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import app.pulse.android.Database
import app.pulse.android.internal
import app.pulse.android.backup.BackupProtocol.METRO_DB_VERSION
import app.pulse.android.backup.BackupProtocol.METRO_ROOM_IDENTITY_HASH
import app.pulse.android.backup.BackupProtocol.formatSecondsToDuration
import app.pulse.android.backup.BackupProtocol.looksLikeSyncedLyrics
import app.pulse.android.backup.BackupProtocol.parseDurationToSeconds
import java.io.File

/**
 * Bidirectional translation between our database and MetroList's backup format.
 *
 * MetroList backups are ZIPs holding a `song.db` SQLite file in MetroList's Room schema
 * (v38, see `METRO_CREATE_SQL`) plus a DataStore `settings.preferences_pb`. Neither side
 * can open the other's live database (table names, columns and user versions differ), so
 * both directions translate row-by-row through raw SQL with explicit column lists:
 *
 * - Export writes a fresh `song.db` that MetroList opens natively at equal version
 *   (no migration runs, no validation trips).
 * - Import stages the foreign `song.db` read-only (never involving Room) and replaces
 *   our library tables inside a single transaction, like MetroList's own restore.
 *
 * Tables without a counterpart on one side are skipped and documented in code; every
 * select is guarded by `PRAGMA table_info` so older/newer fork backups degrade
 * gracefully instead of failing.
 */
object MetroListBackup {
    // Exact MetroList v38 DDL (identity hash bcf76ed36a3cb785f239e5cfbd28440e).
    private val METRO_CREATE_SQL = listOf(
        "CREATE TABLE IF NOT EXISTS `song` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `duration` INTEGER NOT NULL, `thumbnailUrl` TEXT, `albumId` TEXT, `albumName` TEXT, `explicit` INTEGER NOT NULL DEFAULT 0, `year` INTEGER, `date` INTEGER, `dateModified` INTEGER, `liked` INTEGER NOT NULL, `likedDate` INTEGER, `totalPlayTime` INTEGER NOT NULL, `inLibrary` INTEGER, `dateDownload` INTEGER, `isLocal` INTEGER NOT NULL DEFAULT false, `libraryAddToken` TEXT, `libraryRemoveToken` TEXT, `lyricsOffset` INTEGER NOT NULL DEFAULT 0, `romanizeLyrics` INTEGER NOT NULL DEFAULT true, `isDownloaded` INTEGER NOT NULL DEFAULT 0, `isUploaded` INTEGER NOT NULL DEFAULT false, `isVideo` INTEGER NOT NULL DEFAULT false, `isEpisode` INTEGER NOT NULL DEFAULT false, `playbackPosition` INTEGER DEFAULT NULL, `uploadEntityId` TEXT DEFAULT NULL, `isCached` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_song_albumId` ON `song` (`albumId`)",
        "CREATE TABLE IF NOT EXISTS `artist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `thumbnailUrl` TEXT, `channelId` TEXT, `lastUpdateTime` INTEGER NOT NULL, `bookmarkedAt` INTEGER, `isLocal` INTEGER NOT NULL DEFAULT false, `isPodcastChannel` INTEGER NOT NULL DEFAULT false, `cachedPageJson` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `album` (`id` TEXT NOT NULL, `playlistId` TEXT, `title` TEXT NOT NULL, `year` INTEGER, `thumbnailUrl` TEXT, `themeColor` INTEGER, `songCount` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `explicit` INTEGER NOT NULL DEFAULT 0, `lastUpdateTime` INTEGER NOT NULL, `bookmarkedAt` INTEGER, `likedDate` INTEGER, `inLibrary` INTEGER, `isLocal` INTEGER NOT NULL DEFAULT false, `isUploaded` INTEGER NOT NULL DEFAULT false, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `playlist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `browseId` TEXT, `createdAt` INTEGER, `lastUpdateTime` INTEGER, `isEditable` INTEGER NOT NULL DEFAULT true, `bookmarkedAt` INTEGER, `remoteSongCount` INTEGER, `playEndpointParams` TEXT, `thumbnailUrl` TEXT, `shuffleEndpointParams` TEXT, `radioEndpointParams` TEXT, `isLocal` INTEGER NOT NULL DEFAULT false, `isAutoSync` INTEGER NOT NULL DEFAULT false, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `song_artist_map` (`songId` TEXT NOT NULL, `artistId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`songId`, `artistId`), FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`artistId`) REFERENCES `artist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_song_artist_map_songId` ON `song_artist_map` (`songId`)",
        "CREATE INDEX IF NOT EXISTS `index_song_artist_map_artistId` ON `song_artist_map` (`artistId`)",
        "CREATE TABLE IF NOT EXISTS `song_album_map` (`songId` TEXT NOT NULL, `albumId` TEXT NOT NULL, `index` INTEGER NOT NULL, PRIMARY KEY(`songId`, `albumId`), FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`albumId`) REFERENCES `album`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_song_album_map_songId` ON `song_album_map` (`songId`)",
        "CREATE INDEX IF NOT EXISTS `index_song_album_map_albumId` ON `song_album_map` (`albumId`)",
        "CREATE TABLE IF NOT EXISTS `album_artist_map` (`albumId` TEXT NOT NULL, `artistId` TEXT NOT NULL, `order` INTEGER NOT NULL, PRIMARY KEY(`albumId`, `artistId`), FOREIGN KEY(`albumId`) REFERENCES `album`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`artistId`) REFERENCES `artist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_album_artist_map_albumId` ON `album_artist_map` (`albumId`)",
        "CREATE INDEX IF NOT EXISTS `index_album_artist_map_artistId` ON `album_artist_map` (`artistId`)",
        "CREATE TABLE IF NOT EXISTS `playlist_song_map` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` TEXT NOT NULL, `songId` TEXT NOT NULL, `position` INTEGER NOT NULL, `setVideoId` TEXT, FOREIGN KEY(`playlistId`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_playlist_song_map_playlistId` ON `playlist_song_map` (`playlistId`)",
        "CREATE INDEX IF NOT EXISTS `index_playlist_song_map_songId` ON `playlist_song_map` (`songId`)",
        "CREATE TABLE IF NOT EXISTS `search_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `query` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_search_history_query` ON `search_history` (`query`)",
        "CREATE TABLE IF NOT EXISTS `format` (`id` TEXT NOT NULL, `itag` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, `codecs` TEXT NOT NULL, `bitrate` INTEGER NOT NULL, `sampleRate` INTEGER, `contentLength` INTEGER NOT NULL, `loudnessDb` REAL, `perceptualLoudnessDb` REAL, `playbackUrl` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `lyrics` (`id` TEXT NOT NULL, `lyrics` TEXT NOT NULL, `provider` TEXT NOT NULL DEFAULT 'Unknown', `translatedLyrics` TEXT NOT NULL DEFAULT '', `translationLanguage` TEXT NOT NULL DEFAULT '', `translationMode` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `playTime` INTEGER NOT NULL, FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_event_songId` ON `event` (`songId`)",
        "CREATE TABLE IF NOT EXISTS `related_song_map` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` TEXT NOT NULL, `relatedSongId` TEXT NOT NULL, FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`relatedSongId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_related_song_map_songId` ON `related_song_map` (`songId`)",
        "CREATE INDEX IF NOT EXISTS `index_related_song_map_relatedSongId` ON `related_song_map` (`relatedSongId`)",
        "CREATE TABLE IF NOT EXISTS `set_video_id` (`videoId` TEXT NOT NULL, `setVideoId` TEXT, PRIMARY KEY(`videoId`))",
        "CREATE TABLE IF NOT EXISTS `playCount` (`song` TEXT NOT NULL, `year` INTEGER NOT NULL, `month` INTEGER NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`song`, `year`, `month`))",
        "CREATE TABLE IF NOT EXISTS `recognition_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `coverArtUrl` TEXT, `coverArtHqUrl` TEXT, `genre` TEXT, `releaseDate` TEXT, `label` TEXT, `shazamUrl` TEXT, `appleMusicUrl` TEXT, `spotifyUrl` TEXT, `isrc` TEXT, `youtubeVideoId` TEXT, `recognizedAt` INTEGER NOT NULL, `liked` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_recognition_history_trackId` ON `recognition_history` (`trackId`)",
        "CREATE TABLE IF NOT EXISTS `speed_dial_item` (`id` TEXT NOT NULL, `secondaryId` TEXT, `title` TEXT NOT NULL, `subtitle` TEXT, `subtitleIds` TEXT, `thumbnailUrl` TEXT, `type` TEXT NOT NULL, `explicit` INTEGER NOT NULL, `createDate` INTEGER NOT NULL, `albumId` TEXT, `albumName` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `podcast` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `thumbnailUrl` TEXT, `channelId` TEXT, `bookmarkedAt` INTEGER, `lastUpdateTime` INTEGER NOT NULL, `libraryAddToken` TEXT, `libraryRemoveToken` TEXT, PRIMARY KEY(`id`))",
        "CREATE VIEW `sorted_song_artist_map` AS SELECT * FROM song_artist_map ORDER BY position",
        "CREATE VIEW `sorted_song_album_map` AS SELECT * FROM song_album_map ORDER BY `index`",
        "CREATE VIEW `playlist_song_map_preview` AS SELECT * FROM playlist_song_map WHERE position <= 3 ORDER BY position"
    )

    private const val LOCAL_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    // region Export: ours -> MetroList song.db

    private data class OurSong(
        val id: String,
        val title: String,
        val artistsText: String?,
        val durationText: String?,
        val thumbnailUrl: String?,
        val likedAt: Long?,
        val totalPlayTimeMs: Long,
        val explicit: Boolean
    )

    private data class OurArtist(val id: String, val name: String?, val thumbnailUrl: String?, val timestamp: Long?, val bookmarkedAt: Long?)
    private data class OurAlbum(val id: String, val title: String?, val thumbnailUrl: String?, val year: String?, val timestamp: Long?, val bookmarkedAt: Long?)
    private data class OurPlaylist(val id: Long, val name: String, val browseId: String?, val thumbnail: String?)
    private data class OurFormat(val songId: String, val itag: Int?, val mimeType: String?, val bitrate: Long?, val contentLength: Long?, val loudnessDb: Float?, val url: String?)
    private data class OurLyrics(val songId: String, val fixed: String?, val synced: String?)

    fun exportMetroDb(context: Context, destFile: File) {
        Database.checkpoint()
        val db = Database.internal.openHelper.writableDatabase

        val songs = readAll(db, "SELECT id, title, artistsText, durationText, thumbnailUrl, likedAt, totalPlayTimeMs, explicit FROM Song") {
            OurSong(str(0) ?: return@readAll null, str(1) ?: "", str(2), str(3), str(4), lng(5), lng(6) ?: 0L, lng(7) == 1L)
        }
        val songById = songs.associateBy { it.id }
        val songSeconds = songs.associate { it.id to parseDurationToSeconds(it.durationText) }
        val artists = readAll(db, "SELECT id, name, thumbnailUrl, timestamp, bookmarkedAt FROM Artist") {
            OurArtist(str(0) ?: return@readAll null, str(1), str(2), lng(3), lng(4))
        }
        val artistById = artists.associateBy { it.id }
        val songArtistPositions = mutableMapOf<String, Int>()
        val songArtistRows = mutableListOf<Triple<String, String, Int>>()
        db.query(SimpleSQLiteQuery("SELECT songId, artistId FROM SongArtistMap ORDER BY songId")).use { cursor ->
            while (cursor.moveToNext()) {
                val songId = cursor.str(0) ?: continue
                val artistId = cursor.str(1) ?: continue
                if (songById[songId] == null) continue
                val position = songArtistPositions.merge(songId, 0) { old, _ -> old + 1 } ?: 0
                songArtistRows.add(Triple(songId, artistId, position))
            }
        }
        val albums = readAll(db, "SELECT id, title, thumbnailUrl, year, timestamp, bookmarkedAt FROM Album") {
            OurAlbum(str(0) ?: return@readAll null, str(1), str(2), str(3), lng(4), lng(5))
        }
        val albumSongCount = mutableMapOf<String, Int>()
        val albumDuration = mutableMapOf<String, Long>()
        val songAlbumRows = mutableListOf<Triple<String, String, Int>>()
        db.query(SimpleSQLiteQuery("SELECT songId, albumId, position FROM SongAlbumMap")).use { cursor ->
            while (cursor.moveToNext()) {
                val songId = cursor.str(0) ?: continue
                val albumId = cursor.str(1) ?: continue
                if (songById[songId] == null) continue
                val index = if (cursor.isNull(2)) 0 else cursor.getInt(2)
                songAlbumRows.add(Triple(songId, albumId, index))
                albumSongCount[albumId] = (albumSongCount[albumId] ?: 0) + 1
                val seconds = songSeconds[songId] ?: -1
                if (seconds > 0) albumDuration[albumId] = (albumDuration[albumId] ?: 0L) + seconds
            }
        }
        // MetroList treats playlist membership like library membership; mirror that.
        val songsInPlaylists = mutableSetOf<String>()
        val playlistMaps = mutableListOf<Triple<Long, String, Int>>()
        db.query(SimpleSQLiteQuery("SELECT songId, playlistId, position FROM SongPlaylistMap ORDER BY playlistId, position")).use { cursor ->
            while (cursor.moveToNext()) {
                val songId = cursor.str(0) ?: continue
                if (songById[songId] == null) continue
                songsInPlaylists.add(songId)
                playlistMaps.add(Triple(cursor.getLong(1), songId, cursor.getInt(2)))
            }
        }
        val playlists = readAll(db, "SELECT id, name, browseId, thumbnail FROM Playlist") {
            OurPlaylist(getLong(0), str(1) ?: "", str(2), str(3))
        }
        val events = mutableListOf<Triple<String, Long, Long>>()
        db.query(SimpleSQLiteQuery("SELECT songId, timestamp, playTime FROM Event ORDER BY timestamp")).use { cursor ->
            while (cursor.moveToNext()) {
                val songId = cursor.str(0) ?: continue
                if (songById[songId] == null) continue
                events.add(Triple(songId, cursor.getLong(1), cursor.getLong(2)))
            }
        }
        val queries = mutableListOf<String>()
        db.query(SimpleSQLiteQuery("SELECT query FROM SearchQuery")).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.str(0)?.takeIf { it.isNotBlank() }?.let(queries::add)
            }
        }
        val formats = readAll(db, "SELECT songId, itag, mimeType, bitrate, contentLength, loudnessDb, url FROM Format") {
            OurFormat(str(0) ?: return@readAll null, int_(1), str(2), lng(3), lng(4), dbl(5)?.toFloat(), str(6))
        }.filter { songById[it.songId] != null }
        val lyrics = readAll(db, "SELECT songId, fixed, synced FROM Lyrics") {
            OurLyrics(str(0) ?: return@readAll null, str(1), str(2))
        }.filter { songById[it.songId] != null }

        if (destFile.exists()) destFile.delete()
        SQLiteDatabase.openOrCreateDatabase(destFile, null).use { out ->
            out.execSQL("PRAGMA journal_mode = DELETE")
            out.beginTransaction()
            try {
                for (sql in METRO_CREATE_SQL) out.execSQL(sql)
                val now = System.currentTimeMillis()
                for (song in songs) {
                    val seconds = songSeconds[song.id] ?: -1
                    val liked = song.likedAt != null
                    val inLibrary = song.likedAt ?: if (song.id in songsInPlaylists) now else null
                    out.execSQL(
                        "INSERT INTO song (id, title, duration, thumbnailUrl, albumId, albumName, explicit, liked, likedDate, totalPlayTime, inLibrary, isLocal, lyricsOffset, romanizeLyrics, isDownloaded, isUploaded, isVideo, isEpisode, isCached) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        args(song.id, song.title, seconds, song.thumbnailUrl, null, null, bool(song.explicit), bool(liked), song.likedAt, song.totalPlayTimeMs, inLibrary, 0, 0, 1, 0, 0, 0, 0, 0)
                    )
                }
                // Fill album linkage from our album maps (first album wins, like most forks).
                val songAlbumFirst = mutableMapOf<String, Pair<String, String?>>()
                val albumById = albums.associateBy { it.id }
                for ((songId, albumId, _) in songAlbumRows) {
                    if (albumById[albumId] == null) continue
                    songAlbumFirst.getOrPut(songId) { albumId to albumById[albumId]?.title }
                }
                for ((songId, album) in songAlbumFirst) {
                    out.execSQL(
                        "UPDATE song SET albumId = ?, albumName = ? WHERE id = ?",
                        args(album.first, album.second, songId)
                    )
                }
                for (artist in artists) {
                    out.execSQL(
                        "INSERT INTO artist (id, name, thumbnailUrl, lastUpdateTime, bookmarkedAt, isLocal, isPodcastChannel) VALUES (?,?,?,?,?,?,?)",
                        args(artist.id, artist.name ?: "", artist.thumbnailUrl, artist.timestamp ?: now, artist.bookmarkedAt, 0, 0)
                    )
                }
                for ((songId, artistId, position) in songArtistRows) {
                    if (artistById[artistId] == null) continue
                    out.execSQL(
                        "INSERT OR IGNORE INTO song_artist_map (songId, artistId, position) VALUES (?,?,?)",
                        args(songId, artistId, position)
                    )
                }
                for (album in albums) {
                    out.execSQL(
                        "INSERT INTO album (id, title, year, thumbnailUrl, songCount, duration, explicit, lastUpdateTime, bookmarkedAt, isLocal, isUploaded) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        args(
                            album.id, album.title ?: "", album.year?.toIntOrNull(),
                            album.thumbnailUrl, albumSongCount[album.id] ?: 0,
                            albumDuration[album.id] ?: 0L, 0, album.timestamp ?: now,
                            album.bookmarkedAt, 0, 0
                        )
                    )
                }
                for ((songId, albumId, index) in songAlbumRows) {
                    if (albumById[albumId] == null) continue
                    out.execSQL(
                        "INSERT OR IGNORE INTO song_album_map (songId, albumId, `index`) VALUES (?,?,?)",
                        args(songId, albumId, index)
                    )
                }
                val usedPlaylistIds = mutableSetOf<String>()
                val playlistIdMap = mutableMapOf<Long, String>()
                for (playlist in playlists) {
                    var metroId: String
                    do {
                        metroId = generateLocalPlaylistId()
                    } while (!usedPlaylistIds.add(metroId))
                    playlistIdMap[playlist.id] = metroId
                    out.execSQL(
                        "INSERT INTO playlist (id, name, browseId, thumbnailUrl, isEditable, isLocal, isAutoSync) VALUES (?,?,?,?,?,?,?)",
                        args(metroId, playlist.name, playlist.browseId, playlist.thumbnail, 1, 0, 0)
                    )
                }
                for ((playlistId, songId, position) in playlistMaps) {
                    val metroId = playlistIdMap[playlistId] ?: continue
                    out.execSQL(
                        "INSERT INTO playlist_song_map (playlistId, songId, position) VALUES (?,?,?)",
                        args(metroId, songId, position)
                    )
                }
                for (query in queries) {
                    out.execSQL("INSERT OR IGNORE INTO search_history (query) VALUES (?)", args(query))
                }
                for ((songId, timestamp, playTime) in events) {
                    out.execSQL(
                        "INSERT INTO event (songId, timestamp, playTime) VALUES (?,?,?)",
                        args(songId, timestamp, playTime)
                    )
                }
                for (format in formats) {
                    out.execSQL(
                        "INSERT OR REPLACE INTO format (id, itag, mimeType, codecs, bitrate, contentLength, loudnessDb, playbackUrl) VALUES (?,?,?,?,?,?,?,?)",
                        args(format.songId, format.itag ?: 0, format.mimeType ?: "", "", format.bitrate ?: 0L, format.contentLength ?: 0L, format.loudnessDb?.toDouble(), format.url)
                    )
                }
                for (lyric in lyrics) {
                    val text = lyric.synced ?: lyric.fixed ?: continue
                    out.execSQL(
                        "INSERT OR REPLACE INTO lyrics (id, lyrics, provider, translatedLyrics, translationLanguage, translationMode) VALUES (?,?,?,?,?,?)",
                        args(lyric.songId, text, "Unknown", "", "", "")
                    )
                }
                out.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                out.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
                    args(METRO_ROOM_IDENTITY_HASH)
                )
                out.execSQL("PRAGMA user_version = $METRO_DB_VERSION")
                out.setTransactionSuccessful()
            } finally {
                out.endTransaction()
            }
        }
    }

    private fun bool(value: Boolean): Long = if (value) 1L else 0L

    private fun args(vararg values: Any?): Array<Any?> = arrayOf(*values)

    private fun generateLocalPlaylistId(): String = buildString {
        append("LP")
        repeat(8) { append(LOCAL_ID_ALPHABET.random()) }
    }

    // region Import: MetroList song.db -> ours

    data class MetroCounts(val songs: Int, val liked: Int, val playlists: Int, val events: Int, val searches: Int)

    fun previewMetroDb(dbPath: String): MetroCounts? {
        return runCatching {
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val tables = tableNames(db)
                fun count(table: String, where: String? = null): Int {
                    if (table !in tables) return 0
                    db.rawQuery(
                        "SELECT COUNT(*) FROM `$table`" + (where?.let { " WHERE $it" } ?: ""), null
                    ).use { cursor ->
                        return if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                }
                MetroCounts(
                    songs = count("song"),
                    liked = if ("liked" in columnNames(db, "song")) count("song", "liked != 0") else 0,
                    playlists = count("playlist"),
                    events = count("event"),
                    searches = count("search_history")
                )
            }
        }.getOrNull()
    }

    fun previewPulseDb(dbPath: String): MetroCounts? {
        return runCatching {
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val tables = tableNames(db)
                fun count(table: String, where: String? = null): Int {
                    if (table !in tables) return 0
                    db.rawQuery(
                        "SELECT COUNT(*) FROM `$table`" + (where?.let { " WHERE $it" } ?: ""), null
                    ).use { cursor ->
                        return if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                }
                MetroCounts(
                    songs = count("Song"),
                    liked = if ("likedAt" in columnNames(db, "Song")) count("Song", "likedAt IS NOT NULL") else 0,
                    playlists = count("Playlist"),
                    events = count("Event"),
                    searches = count("SearchQuery")
                )
            }
        }.getOrNull()
    }

    /**
     * Replaces our library tables with the contents of a MetroList-shaped database.
     * Runs inside a single Room transaction; the caller stops the player service first
     * and restarts the app afterwards.
     */
    fun importMetroDb(dbPath: String) {
        val src = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val songCols = columnNames(src, "song")
            val artistCols = columnNames(src, "artist")
            val albumCols = columnNames(src, "album")
            val playlistCols = columnNames(src, "playlist")
            val songArtistCols = columnNames(src, "song_artist_map")
            val songAlbumCols = columnNames(src, "song_album_map")
            val playlistSongCols = columnNames(src, "playlist_song_map")
            val eventCols = columnNames(src, "event")
            val searchCols = columnNames(src, "search_history")
            val formatCols = columnNames(src, "format")
            val lyricsCols = columnNames(src, "lyrics")
            val now = System.currentTimeMillis()

            val db = Database.internal.openHelper.writableDatabase
            Database.internal.runInTransaction {
                // Wipe library tables child-first (keep queue, sessions and app tables).
                db.execSQL("DELETE FROM SongPlaylistMap")
                db.execSQL("DELETE FROM SongArtistMap")
                db.execSQL("DELETE FROM SongAlbumMap")
                db.execSQL("DELETE FROM Event")
                db.execSQL("DELETE FROM Format")
                db.execSQL("DELETE FROM Lyrics")
                db.execSQL("DELETE FROM SearchQuery")
                db.execSQL("DELETE FROM Playlist")
                db.execSQL("DELETE FROM Artist")
                db.execSQL("DELETE FROM Album")
                db.execSQL("DELETE FROM Song")

                val importedSongs = mutableSetOf<String>()

                select(src, "song", songCols, listOf("id", "title", "duration", "thumbnailUrl", "liked", "likedDate", "totalPlayTime", "explicit")) {
                    val id = str("id") ?: return@select
                    val liked = (lng("liked") ?: 0L) != 0L
                    val likedAt = lng("likedDate") ?: if (liked) now else null
                    val seconds = int_("duration")
                    db.execSQL(
                        "INSERT OR IGNORE INTO Song (id, title, durationText, thumbnailUrl, likedAt, totalPlayTimeMs, blacklisted, explicit) VALUES (?,?,?,?,?,?,?,?)",
                        args(id, str("title") ?: "", formatSecondsToDuration(seconds), str("thumbnailUrl"), likedAt, lng("totalPlayTime") ?: 0L, 0L, bool((lng("explicit") ?: 0L) != 0L))
                    )
                    importedSongs.add(id)
                }

                // Artists first so song artist names resolve for artistsText.
                val artistNames = mutableMapOf<String, MutableList<String>>()
                select(src, "song_artist_map", songArtistCols, listOf("songId", "artistId")) {
                    val songId = str("songId") ?: return@select
                    val artistId = str("artistId") ?: return@select
                    artistNames.getOrPut(songId) { mutableListOf() }.add(artistId)
                }
                val artistNameById = mutableMapOf<String, String>()
                select(src, "artist", artistCols, listOf("id", "name", "thumbnailUrl", "lastUpdateTime", "bookmarkedAt")) {
                    val id = str("id") ?: return@select
                    val name = str("name") ?: ""
                    artistNameById[id] = name
                    db.execSQL(
                        "INSERT OR IGNORE INTO Artist (id, name, thumbnailUrl, timestamp, bookmarkedAt) VALUES (?,?,?,?,?)",
                        args(id, name, str("thumbnailUrl"), lng("lastUpdateTime"), lng("bookmarkedAt"))
                    )
                }
                // Backfill artistsText on songs from resolved artist names.
                for ((songId, artistIds) in artistNames) {
                    if (songId !in importedSongs) continue
                    val names = artistIds.mapNotNull { artistNameById[it] }.filter { it.isNotBlank() }
                    if (names.isNotEmpty()) {
                        db.execSQL("UPDATE Song SET artistsText = ? WHERE id = ?", args(names.joinToString(), songId))
                    }
                    for (artistId in artistIds) {
                        if (artistId !in artistNameById) continue
                        db.execSQL("INSERT OR IGNORE INTO SongArtistMap (songId, artistId) VALUES (?,?)", args(songId, artistId))
                    }
                }

                select(src, "album", albumCols, listOf("id", "title", "thumbnailUrl", "year", "lastUpdateTime", "bookmarkedAt")) {
                    val id = str("id") ?: return@select
                    db.execSQL(
                        "INSERT OR IGNORE INTO Album (id, title, thumbnailUrl, year, timestamp, bookmarkedAt) VALUES (?,?,?,?,?,?)",
                        args(id, str("title") ?: "", str("thumbnailUrl"), lng("year")?.toString(), lng("lastUpdateTime"), lng("bookmarkedAt"))
                    )
                }
                select(src, "song_album_map", songAlbumCols, listOf("songId", "albumId", "`index`")) {
                    val songId = str("songId") ?: return@select
                    val albumId = str("albumId") ?: return@select
                    if (songId !in importedSongs) return@select
                    db.execSQL(
                        "INSERT OR IGNORE INTO SongAlbumMap (songId, albumId, position) VALUES (?,?,?)",
                        args(songId, albumId, int_("index"))
                    )
                }

                val playlistIdMap = mutableMapOf<String, Long>()
                select(src, "playlist", playlistCols, listOf("id", "name", "browseId", "thumbnailUrl")) {
                    val metroId = str("id") ?: return@select
                    val rowId = db.compileStatement(
                        "INSERT INTO Playlist (name, browseId, thumbnail) VALUES (?,?,?)"
                    ).use { stmt ->
                        stmt.bindString(1, str("name") ?: "")
                        str("browseId")?.let { stmt.bindString(2, it) } ?: stmt.bindNull(2)
                        str("thumbnailUrl")?.let { stmt.bindString(3, it) } ?: stmt.bindNull(3)
                        stmt.executeInsert()
                    }
                    if (rowId != -1L) playlistIdMap[metroId] = rowId
                }
                select(src, "playlist_song_map", playlistSongCols, listOf("playlistId", "songId", "position")) {
                    val playlistId = playlistIdMap[str("playlistId")] ?: return@select
                    val songId = str("songId") ?: return@select
                    if (songId !in importedSongs) return@select
                    db.execSQL(
                        "INSERT OR IGNORE INTO SongPlaylistMap (songId, playlistId, position) VALUES (?,?,?)",
                        args(songId, playlistId, int_("position") ?: 0)
                    )
                }

                select(src, "event", eventCols, listOf("songId", "timestamp", "playTime")) {
                    val songId = str("songId") ?: return@select
                    if (songId !in importedSongs) return@select
                    db.execSQL(
                        "INSERT INTO Event (songId, timestamp, playTime) VALUES (?,?,?)",
                        args(songId, lng("timestamp") ?: now, lng("playTime") ?: 0L)
                    )
                }
                select(src, "search_history", searchCols, listOf("query")) {
                    val query = str("query")?.takeIf { it.isNotBlank() } ?: return@select
                    db.execSQL("INSERT OR IGNORE INTO SearchQuery (query) VALUES (?)", args(query))
                }
                select(src, "format", formatCols, listOf("id", "itag", "mimeType", "bitrate", "contentLength", "loudnessDb", "playbackUrl")) {
                    val songId = str("id") ?: return@select
                    if (songId !in importedSongs) return@select
                    db.execSQL(
                        "INSERT OR REPLACE INTO Format (songId, itag, mimeType, bitrate, contentLength, loudnessDb, url) VALUES (?,?,?,?,?,?,?)",
                        args(songId, int_("itag"), str("mimeType"), lng("bitrate"), lng("contentLength"), dbl("loudnessDb")?.toFloat(), str("playbackUrl"))
                    )
                }
                select(src, "lyrics", lyricsCols, listOf("id", "lyrics")) {
                    val songId = str("id") ?: return@select
                    if (songId !in importedSongs) return@select
                    val text = str("lyrics")?.takeIf { it.isNotBlank() } ?: return@select
                    if (looksLikeSyncedLyrics(text)) {
                        db.execSQL("INSERT OR REPLACE INTO Lyrics (songId, synced) VALUES (?,?)", args(songId, text))
                    } else {
                        db.execSQL("INSERT OR REPLACE INTO Lyrics (songId, fixed) VALUES (?,?)", args(songId, text))
                    }
                }
            }
        } finally {
            runCatching { src.close() }
        }
    }

    // region Raw-SQL helpers (guarded, bind-args only)

    private fun tableNames(db: SQLiteDatabase): Set<String> = buildSet {
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun columnNames(db: SQLiteDatabase, table: String): Set<String> {
        if (table !in tableNames(db)) return emptySet()
        return buildSet {
            db.rawQuery("PRAGMA table_info('$table')", null).use { cursor ->
                val index = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(index))
            }
        }
    }

    /** Iterates rows of [table] selecting only [wanted] columns that actually exist. */
    private inline fun select(
        db: SQLiteDatabase,
        table: String,
        available: Set<String>,
        wanted: List<String>,
        crossinline block: RowReader.() -> Unit
    ) {
        val columns = wanted.filter { it.trim('`') in available }
        if (columns.isEmpty() || table !in tableNames(db)) return
        db.rawQuery("SELECT ${columns.joinToString()} FROM `$table`", null).use { cursor ->
            val reader = RowReader(cursor, columns)
            while (cursor.moveToNext()) block(reader)
        }
    }

    private class RowReader(private val cursor: Cursor, private val columns: List<String>) {
        private fun index(name: String): Int =
            cursor.getColumnIndexOrThrow(columns.first { it.trim('`') == name })

        private fun has(name: String): Boolean = columns.any { it.trim('`') == name }

        fun str(name: String): String? {
            if (!has(name)) return null
            val i = index(name)
            return if (cursor.isNull(i)) null else cursor.getString(i)
        }

        fun lng(name: String): Long? {
            if (!has(name)) return null
            val i = index(name)
            return if (cursor.isNull(i)) null else cursor.getLong(i)
        }

        fun int_(name: String): Int? {
            if (!has(name)) return null
            val i = index(name)
            return if (cursor.isNull(i)) null else cursor.getInt(i)
        }

        fun dbl(name: String): Double? {
            if (!has(name)) return null
            val i = index(name)
            return if (cursor.isNull(i)) null else cursor.getDouble(i)
        }
    }

    private fun Cursor.str(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.lng(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.int_(index: Int): Int? = if (isNull(index)) null else getInt(index)
    private fun Cursor.dbl(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private inline fun <T> readAll(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String,
        crossinline block: Cursor.() -> T?
    ): List<T> {
        return db.query(SimpleSQLiteQuery(sql)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) block(cursor)?.let(::add)
            }
        }
    }

    // endregion
}
