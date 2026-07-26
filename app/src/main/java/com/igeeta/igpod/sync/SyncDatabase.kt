package com.igeeta.igpod.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Data classes (plain data, no Room annotations)
// ---------------------------------------------------------------------------

data class SyncedPlaylist(
    val serverId: Int,
    val name: String,
    val description: String = "",
    val isOffline: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lastSyncAt: String? = null,
    val trackCount: Int = 0,
)

data class SyncedTrack(
    val filePath: String,
    val videoId: String = "",
    val contentHash: String? = null,
    val genre: String = "",
    val subGenre: String = "",
    val album: String = "",
    val raag: String = "",
    val title: String = "",
    val artists: String = "[]",
    val instruments: String = "[]",
    val tags: String = "{}",
    val duration: Double = 0.0,
    val fileSize: Long = 0,
    val bitrate: Int = 0,
    val source: String = "",
    val importedAt: String? = null,
    val updatedAt: String? = null,
    val rating: Int = 0,
    val playCount: Int = 0,
    val lastPlayed: String? = null,
    val artworkLocalPath: String? = null,
    val trackArtPath: String = "",
    val localRating: Int = 0,
    val ratingDirty: Int = 0,
    val playlistServerId: Int? = null,
    val position: Int = 0,
    val lastSyncAt: String? = null,
)

data class SyncedPlaylistEntry(
    val id: Int = 0,
    val playlistId: Int,
    val filePath: String,
    val position: Int = 0,
    val addedAt: String? = null,
)

data class SyncLog(
    val id: Int = 0,
    val timestamp: String,
    val action: String,
    val detail: String = "",
)

data class SyncedRaga(
    val name: String,
    val mood: String = "",
    val prahara: String = "",
    val season: String = "",
    val varjitNotes: String = "",
    val associatedEmotions: String = "",
    val description: String = "",
)

// ---------------------------------------------------------------------------
// SQLite Helper
// ---------------------------------------------------------------------------

class SyncDbHelper(context: Context) : SQLiteOpenHelper(context, "igeeta_sync.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE synced_playlists (
                serverId INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT DEFAULT '',
                is_offline INTEGER DEFAULT 0,
                created_at TEXT,
                updated_at TEXT,
                last_sync_at TEXT,
                track_count INTEGER DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE synced_tracks (
                file_path TEXT PRIMARY KEY,
                video_id TEXT DEFAULT '',
                content_hash TEXT,
                genre TEXT DEFAULT '',
                sub_genre TEXT DEFAULT '',
                album TEXT DEFAULT '',
                raag TEXT DEFAULT '',
                title TEXT DEFAULT '',
                artists TEXT DEFAULT '[]',
                instruments TEXT DEFAULT '[]',
                tags TEXT DEFAULT '{}',
                duration REAL DEFAULT 0,
                file_size INTEGER DEFAULT 0,
                bitrate INTEGER DEFAULT 0,
                source TEXT DEFAULT '',
                imported_at TEXT,
                updated_at TEXT,
                rating INTEGER DEFAULT 0,
                play_count INTEGER DEFAULT 0,
                last_played TEXT,
                artwork_local_path TEXT,
                local_rating INTEGER DEFAULT 0,
                rating_dirty INTEGER DEFAULT 0,
                playlist_server_id INTEGER,
                position INTEGER DEFAULT 0,
                last_sync_at TEXT,
                track_art_path TEXT DEFAULT '',
                chapter_title TEXT DEFAULT '',
                chapter_index INTEGER DEFAULT -1,
                chapter_start REAL DEFAULT -1,
                chapter_end REAL DEFAULT -1
            )
        """)
        db.execSQL("""
            CREATE TABLE synced_playlist_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist_id INTEGER NOT NULL,
                file_path TEXT NOT NULL,
                position INTEGER DEFAULT 0,
                added_at TEXT
            )
        """)
        db.execSQL("CREATE INDEX idx_entries_playlist ON synced_playlist_entries(playlist_id)")
        db.execSQL("CREATE INDEX idx_tracks_playlist ON synced_tracks(playlist_server_id)")
        db.execSQL("CREATE INDEX idx_tracks_genre ON synced_tracks(genre)")
        db.execSQL("CREATE INDEX idx_tracks_raag ON synced_tracks(raag)")
        db.execSQL("CREATE INDEX idx_tracks_album ON synced_tracks(album)")
        db.execSQL("""
            CREATE TABLE sync_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT,
                action TEXT,
                detail TEXT DEFAULT ''
            )
        """)
        db.execSQL("""
            CREATE TABLE raga_metadata (
                name TEXT PRIMARY KEY,
                mood TEXT DEFAULT '',
                prahara TEXT DEFAULT '',
                ritu TEXT DEFAULT '',
                season TEXT DEFAULT '',
                varjit_notes TEXT DEFAULT '',
                associated_emotions TEXT DEFAULT '',
                description TEXT DEFAULT ''
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS albums (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                genre TEXT DEFAULT '',
                sub_genre TEXT DEFAULT '',
                name TEXT DEFAULT '',
                cover_art TEXT DEFAULT '',
                all_artwork TEXT DEFAULT '[]',
                artists TEXT DEFAULT '[]',
                year INTEGER DEFAULT 0,
                label TEXT DEFAULT '',
                catalog TEXT DEFAULT ''
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS raga_metadata (
                    name TEXT PRIMARY KEY,
                    mood TEXT DEFAULT '',
                    prahara TEXT DEFAULT '',
                    season TEXT DEFAULT '',
                    varjit_notes TEXT DEFAULT '',
                    associated_emotions TEXT DEFAULT '',
                    description TEXT DEFAULT ''
                )
            """)
        }
        if (oldVersion < 3) {
            // Add missing columns to synced_tracks
            try { db.execSQL("ALTER TABLE synced_tracks ADD COLUMN track_art_path TEXT DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE synced_tracks ADD COLUMN chapter_title TEXT DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE synced_tracks ADD COLUMN chapter_index INTEGER DEFAULT -1") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE synced_tracks ADD COLUMN chapter_start REAL DEFAULT -1") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE synced_tracks ADD COLUMN chapter_end REAL DEFAULT -1") } catch (_: Exception) {}
            // Add ritu column to raga_metadata
            try { db.execSQL("ALTER TABLE raga_metadata ADD COLUMN ritu TEXT DEFAULT ''") } catch (_: Exception) {}
            // Add albums table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS albums (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    genre TEXT DEFAULT '',
                    sub_genre TEXT DEFAULT '',
                    name TEXT DEFAULT '',
                    cover_art TEXT DEFAULT '',
                    all_artwork TEXT DEFAULT '[]',
                    artists TEXT DEFAULT '[]',
                    year INTEGER DEFAULT 0,
                    label TEXT DEFAULT '',
                    catalog TEXT DEFAULT ''
                )
            """)
        }
    }
}

// ---------------------------------------------------------------------------
// Database singleton + DAO-like accessors
// ---------------------------------------------------------------------------

class SyncDatabase private constructor(context: Context) {

    private val dbHelper = SyncDbHelper(context.applicationContext)
    private val db get() = dbHelper.writableDatabase
    private val _playlistChangeFlow = MutableStateFlow(0L)
    private val _trackChangeFlow = MutableStateFlow(0L)

    private fun notifyPlaylists() { _playlistChangeFlow.value++ }
    private fun notifyTracks() { _trackChangeFlow.value++ }

    // -- Playlists --

    suspend fun getAllPlaylists(): List<SyncedPlaylist> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncedPlaylist>()
        db.query("synced_playlists", null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toPlaylist())
            }
        }
        list
    }

    fun observePlaylists(): Flow<List<SyncedPlaylist>> {
        return _playlistChangeFlow.map { kotlinx.coroutines.runBlocking { getAllPlaylists() } }
    }

    suspend fun getPlaylist(serverId: Int): SyncedPlaylist? = withContext(Dispatchers.IO) {
        db.query("synced_playlists", null, "serverId = ?", arrayOf(serverId.toString()), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPlaylist() else null
        }
    }

    suspend fun upsertPlaylist(playlist: SyncedPlaylist) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("serverId", playlist.serverId)
            put("name", playlist.name)
            put("description", playlist.description)
            put("is_offline", playlist.isOffline)
            put("created_at", playlist.createdAt)
            put("updated_at", playlist.updatedAt)
            put("last_sync_at", playlist.lastSyncAt)
            put("track_count", playlist.trackCount)
        }
        db.insertWithOnConflict("synced_playlists", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        notifyPlaylists()
    }

    // -- Tracks --

    suspend fun getAllTracks(): List<SyncedTrack> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncedTrack>()
        db.query("synced_tracks", null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toTrack())
            }
        }
        list
    }

    suspend fun getTrackByPath(path: String): SyncedTrack? = withContext(Dispatchers.IO) {
        db.query("synced_tracks", null, "file_path = ?", arrayOf(path), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTrack() else null
        }
    }

    suspend fun getTracksByPlaylist(playlistId: Int): List<SyncedTrack> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncedTrack>()
        db.query("synced_tracks", null, "playlist_server_id = ?", arrayOf(playlistId.toString()),
            null, null, "position ASC").use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toTrack())
            }
        }
        list
    }

    suspend fun getDirtyRatings(): List<SyncedTrack> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncedTrack>()
        db.query("synced_tracks", null, "rating_dirty = 1", null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toTrack())
            }
        }
        list
    }

    suspend fun searchTracks(query: String): List<SyncedTrack> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncedTrack>()
        val wildcard = "%$query%"
        db.query("synced_tracks", null,
            "title LIKE ? OR artists LIKE ? OR raag LIKE ? OR genre LIKE ? OR album LIKE ? OR instruments LIKE ?",
            arrayOf(wildcard, wildcard, wildcard, wildcard, wildcard, wildcard),
            null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toTrack())
            }
        }
        list
    }

    suspend fun upsertTrack(track: SyncedTrack) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("file_path", track.filePath)
            put("video_id", track.videoId)
            put("content_hash", track.contentHash)
            put("genre", track.genre)
            put("sub_genre", track.subGenre)
            put("album", track.album)
            put("raag", track.raag)
            put("title", track.title)
            put("artists", track.artists)
            put("instruments", track.instruments)
            put("tags", track.tags)
            put("duration", track.duration)
            put("file_size", track.fileSize)
            put("bitrate", track.bitrate)
            put("source", track.source)
            put("imported_at", track.importedAt)
            put("updated_at", track.updatedAt)
            put("rating", track.rating)
            put("play_count", track.playCount)
            put("last_played", track.lastPlayed)
            put("artwork_local_path", track.artworkLocalPath)
            put("track_art_path", track.trackArtPath)
            put("local_rating", track.localRating)
            put("rating_dirty", track.ratingDirty)
            put("playlist_server_id", track.playlistServerId)
            put("position", track.position)
            put("last_sync_at", track.lastSyncAt)
        }
        db.insertWithOnConflict("synced_tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        notifyTracks()
    }

    suspend fun setLocalRating(path: String, rating: Int) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("local_rating", rating)
            put("rating_dirty", 1)
        }
        db.update("synced_tracks", cv, "file_path = ?", arrayOf(path))
        notifyTracks()
    }

    suspend fun clearRatingDirty(path: String) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply { put("rating_dirty", 0) }
        db.update("synced_tracks", cv, "file_path = ?", arrayOf(path))
    }

    suspend fun syncServerRating(path: String, rating: Int) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("rating", rating)
            put("local_rating", rating)
            put("rating_dirty", 0)
        }
        db.update("synced_tracks", cv, "file_path = ?", arrayOf(path))
        notifyTracks()
    }

    suspend fun getTrackCount(): Int = withContext(Dispatchers.IO) {
        db.compileStatement("SELECT COUNT(*) FROM synced_tracks").use { stmt ->
            stmt.simpleQueryForLong().toInt()
        }
    }

    // -- Playlist Entries --

    suspend fun getPlaylistEntries(playlistId: Int): List<SyncedPlaylistEntry> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncedPlaylistEntry>()
        db.query("synced_playlist_entries", null, "playlist_id = ?", arrayOf(playlistId.toString()),
            null, null, "position ASC").use { cursor ->
            while (cursor.moveToNext()) {
                list.add(SyncedPlaylistEntry(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    playlistId = cursor.getInt(cursor.getColumnIndexOrThrow("playlist_id")),
                    filePath = cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
                    position = cursor.getInt(cursor.getColumnIndexOrThrow("position")),
                    addedAt = cursor.getString(cursor.getColumnIndexOrThrow("added_at")),
                ))
            }
        }
        list
    }

    suspend fun upsertPlaylistEntry(entry: SyncedPlaylistEntry) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("playlist_id", entry.playlistId)
            put("file_path", entry.filePath)
            put("position", entry.position)
            put("added_at", entry.addedAt)
        }
        db.insert("synced_playlist_entries", null, cv)
    }

    suspend fun deletePlaylistEntries(playlistId: Int) = withContext(Dispatchers.IO) {
        db.delete("synced_playlist_entries", "playlist_id = ?", arrayOf(playlistId.toString()))
    }

    // -- Sync Log --

    suspend fun insertLog(log: SyncLog) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("timestamp", log.timestamp)
            put("action", log.action)
            put("detail", log.detail)
        }
        db.insert("sync_log", null, cv)
    }

    suspend fun getRecentLogs(limit: Int = 20): List<SyncLog> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncLog>()
        db.rawQuery("SELECT * FROM sync_log ORDER BY id DESC LIMIT $limit", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(SyncLog(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp")),
                    action = cursor.getString(cursor.getColumnIndexOrThrow("action")),
                    detail = cursor.getString(cursor.getColumnIndexOrThrow("detail")),
                ))
            }
        }
        list
    }

    // -- Bulk Delete Methods --

    suspend fun deleteAllTracks() = withContext(Dispatchers.IO) {
        db.delete("synced_tracks", null, null)
        notifyTracks()
    }

    suspend fun deleteAllPlaylists() = withContext(Dispatchers.IO) {
        db.delete("synced_playlists", null, null)
        notifyPlaylists()
    }

    suspend fun deleteAllPlaylistEntries() = withContext(Dispatchers.IO) {
        db.delete("synced_playlist_entries", null, null)
    }

    suspend fun deleteAllRagas() = withContext(Dispatchers.IO) {
        db.delete("raga_metadata", null, null)
    }

    suspend fun deleteAllLogs() = withContext(Dispatchers.IO) {
        db.delete("sync_log", null, null)
    }

    suspend fun deleteTracksNotInPlaylists(): Int = withContext(Dispatchers.IO) {
        db.delete("synced_tracks",
            "file_path NOT IN (SELECT file_path FROM synced_playlist_entries)",
            null)
    }

    // -- Helpers --

    private fun Cursor.toPlaylist() = SyncedPlaylist(
        serverId = getInt(getColumnIndexOrThrow("serverId")),
        name = getString(getColumnIndexOrThrow("name")),
        description = getString(getColumnIndexOrThrow("description")),
        isOffline = getInt(getColumnIndexOrThrow("is_offline")),
        createdAt = getString(getColumnIndexOrThrow("created_at")),
        updatedAt = getString(getColumnIndexOrThrow("updated_at")),
        lastSyncAt = getString(getColumnIndexOrThrow("last_sync_at")),
        trackCount = getInt(getColumnIndexOrThrow("track_count")),
    )

    private fun Cursor.toTrack() = SyncedTrack(
        filePath = getString(getColumnIndexOrThrow("file_path")),
        videoId = getString(getColumnIndexOrThrow("video_id")),
        contentHash = getString(getColumnIndexOrThrow("content_hash")),
        genre = getString(getColumnIndexOrThrow("genre")),
        subGenre = getString(getColumnIndexOrThrow("sub_genre")),
        album = getString(getColumnIndexOrThrow("album")),
        raag = getString(getColumnIndexOrThrow("raag")),
        title = getString(getColumnIndexOrThrow("title")),
        artists = getString(getColumnIndexOrThrow("artists")),
        instruments = getString(getColumnIndexOrThrow("instruments")),
        tags = getString(getColumnIndexOrThrow("tags")),
        duration = getDouble(getColumnIndexOrThrow("duration")),
        fileSize = getLong(getColumnIndexOrThrow("file_size")),
        bitrate = getInt(getColumnIndexOrThrow("bitrate")),
        source = getString(getColumnIndexOrThrow("source")),
        importedAt = getString(getColumnIndexOrThrow("imported_at")),
        updatedAt = getString(getColumnIndexOrThrow("updated_at")),
        rating = getInt(getColumnIndexOrThrow("rating")),
        playCount = getInt(getColumnIndexOrThrow("play_count")),
        lastPlayed = getString(getColumnIndexOrThrow("last_played")),
        artworkLocalPath = getString(getColumnIndexOrThrow("artwork_local_path")),
        trackArtPath = getString(getColumnIndexOrThrow("track_art_path")),
        localRating = getInt(getColumnIndexOrThrow("local_rating")),
        ratingDirty = getInt(getColumnIndexOrThrow("rating_dirty")),
        playlistServerId = getInt(getColumnIndexOrThrow("playlist_server_id")),
        position = getInt(getColumnIndexOrThrow("position")),
        lastSyncAt = getString(getColumnIndexOrThrow("last_sync_at")),
    )

    // -- Raga Metadata --

    suspend fun getAllRagas(): List<SyncedRaga> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncedRaga>()
        db.query("raga_metadata", null, null, null, null, null, "name ASC").use { cursor ->
            while (cursor.moveToNext()) {
                list.add(SyncedRaga(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    mood = cursor.getString(cursor.getColumnIndexOrThrow("mood")),
                    prahara = cursor.getString(cursor.getColumnIndexOrThrow("prahara")),
                    season = cursor.getString(cursor.getColumnIndexOrThrow("season")),
                    varjitNotes = cursor.getString(cursor.getColumnIndexOrThrow("varjit_notes")),
                    associatedEmotions = cursor.getString(cursor.getColumnIndexOrThrow("associated_emotions")),
                    description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                ))
            }
        }
        list
    }

    suspend fun searchRagas(query: String): List<SyncedRaga> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncedRaga>()
        val wildcard = "%$query%"
        db.query("raga_metadata", null,
            "name LIKE ? OR mood LIKE ? OR season LIKE ? OR description LIKE ?",
            arrayOf(wildcard, wildcard, wildcard, wildcard),
            null, null, "name ASC").use { cursor ->
            while (cursor.moveToNext()) {
                list.add(SyncedRaga(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    mood = cursor.getString(cursor.getColumnIndexOrThrow("mood")),
                    prahara = cursor.getString(cursor.getColumnIndexOrThrow("prahara")),
                    season = cursor.getString(cursor.getColumnIndexOrThrow("season")),
                    varjitNotes = cursor.getString(cursor.getColumnIndexOrThrow("varjit_notes")),
                    associatedEmotions = cursor.getString(cursor.getColumnIndexOrThrow("associated_emotions")),
                    description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                ))
            }
        }
        list
    }

    suspend fun upsertRaga(raga: SyncedRaga) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("name", raga.name)
            put("mood", raga.mood)
            put("prahara", raga.prahara)
            put("season", raga.season)
            put("varjit_notes", raga.varjitNotes)
            put("associated_emotions", raga.associatedEmotions)
            put("description", raga.description)
        }
        db.insertWithOnConflict("raga_metadata", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun getTracksByRaag(raag: String): List<SyncedTrack> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncedTrack>()
        db.query("synced_tracks", null, "raag = ?", arrayOf(raag),
            null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toTrack())
            }
        }
        list
    }

    fun close() {
        dbHelper.close()
    }

    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null

        fun getInstance(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncDatabase(context).also { INSTANCE = it }
            }
        }
    }
}
