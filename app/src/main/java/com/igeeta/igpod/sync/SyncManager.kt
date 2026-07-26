package com.igeeta.igpod.sync

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates sync between iGeeta server and local storage.
 * Mirrors the server's directory structure under /Music/iGeeta/.
 */
class SyncManager(private val context: Context) {

    companion object {
        /** Root directory for synced content — mirrors server's ROOT_DIR */
        fun getSyncRoot(context: Context): File {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            return File(musicDir, "iGeeta")
        }
    }

    private val db = SyncDatabase.getInstance(context)

    private fun getConfig(): SyncConfig? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val host = prefs.getString("igeeta_host", "") ?: ""
        val portStr = prefs.getString("igeeta_port", "8000") ?: "8000"
        val port = portStr.toIntOrNull() ?: 8000
        val config = SyncConfig(host, port)
        return if (config.isValid) config else null
    }

    // -----------------------------------------------------------------------
    // Main sync entry point
    // -----------------------------------------------------------------------

    /**
     * Sync selected playlists from server.
     * @param playlistIds Server playlist IDs to sync
     * @param onProgress Callback for progress updates
     * @return true on success
     */
    suspend fun syncPlaylists(
        playlistIds: List<Int>,
        onProgress: (SyncProgress) -> Unit = {},
    ): Boolean {
        val config = getConfig() ?: run {
            onProgress(SyncProgress(phase = SyncPhase.ERROR, errorMessage = "Server not configured"))
            return false
        }

        val api = IgeetaApi(config)
        val syncRoot = getSyncRoot(context)
        syncRoot.mkdirs()

        try {
            // 1. Test connection
            onProgress(SyncProgress(phase = SyncPhase.CONNECTING))
            if (!api.testConnection()) {
                onProgress(SyncProgress(phase = SyncPhase.ERROR, errorMessage = "Connection failed"))
                return false
            }

            // 2. Push local ratings first
            onProgress(SyncProgress(phase = SyncPhase.SYNCING_RATINGS, currentAction = "Pushing ratings..."))
            pushRatings(api)

            // 3. Sync each playlist
            onProgress(SyncProgress(phase = SyncPhase.SYNCING_PLAYLISTS, currentAction = "Fetching playlists..."))
            var totalTracksSynced = 0
            var totalTracksSkipped = 0

            for (playlistId in playlistIds) {
                val serverPlaylist = try {
                    api.getPlaylist(playlistId)
                } catch (e: Exception) {
                    onProgress(SyncProgress(
                        phase = SyncPhase.ERROR,
                        errorMessage = "Failed to fetch playlist $playlistId: ${e.message}"
                    ))
                    continue
                }

                onProgress(SyncProgress(
                    phase = SyncPhase.SYNCING_TRACKS,
                    playlistName = serverPlaylist.name,
                    totalTracks = serverPlaylist.tracks.size,
                    currentAction = "Syncing ${serverPlaylist.name}..."
                ))

                // Upsert playlist metadata
                db.upsertPlaylist(SyncedPlaylist(
                    serverId = serverPlaylist.id,
                    name = serverPlaylist.name,
                    description = serverPlaylist.description,
                    isOffline = serverPlaylist.isOffline,
                    createdAt = serverPlaylist.createdAt,
                    updatedAt = serverPlaylist.updatedAt,
                    lastSyncAt = now(),
                    trackCount = serverPlaylist.tracks.size,
                ))

                // Clear old entries and re-insert
                db.deletePlaylistEntries(playlistId)

                // Sync tracks
                for ((index, track) in serverPlaylist.tracks.withIndex()) {
                    onProgress(SyncProgress(
                        phase = SyncPhase.SYNCING_TRACKS,
                        playlistName = serverPlaylist.name,
                        currentTrack = index + 1,
                        totalTracks = serverPlaylist.tracks.size,
                        currentAction = track.title.ifBlank { track.filePath }
                    ))

                    val synced = syncTrack(api, syncRoot, track, playlistId, index)
                    if (synced) totalTracksSynced++ else totalTracksSkipped++

                    // Insert playlist entry
                    db.upsertPlaylistEntry(SyncedPlaylistEntry(
                        playlistId = playlistId,
                        filePath = track.filePath,
                        position = index,
                        addedAt = now(),
                    ))
                }
            }

            // 4. Pull ratings from server
            onProgress(SyncProgress(phase = SyncPhase.SYNCING_RATINGS, currentAction = "Syncing ratings..."))
            pullRatings(api)

            // 5. Sync raga metadata
            onProgress(SyncProgress(phase = SyncPhase.SYNCING_RATINGS, currentAction = "Syncing raga metadata..."))
            syncRagas(api)

            // 6. Clean orphaned tracks (not in any playlist)
            onProgress(SyncProgress(phase = SyncPhase.SYNCING_RATINGS, currentAction = "Cleaning orphans..."))
            val orphanedCount = db.deleteTracksNotInPlaylists()
            if (orphanedCount > 0) {
                // Delete orphaned audio files
                for (track in db.getAllTracks()) {
                    val localPath = File(syncRoot, track.filePath)
                    if (localPath.exists()) {
                        localPath.delete()
                    }
                }
            }

            // 7. Log completion
            val detail = "Synced $totalTracksSynced tracks, skipped $totalTracksSkipped (exist), cleaned $orphanedCount orphans"
            db.insertLog(SyncLog(
                timestamp = now(),
                action = "sync",
                detail = detail,
            ))

            onProgress(SyncProgress(
                phase = SyncPhase.DONE,
                currentAction = detail,
            ))

            return true

        } catch (e: Exception) {
            onProgress(SyncProgress(phase = SyncPhase.ERROR, errorMessage = e.message))
            db.insertLog(SyncLog(
                timestamp = now(),
                action = "error",
                detail = e.message ?: "Unknown error",
            ))
            return false
        } finally {
            api.close()
        }
    }

    // -----------------------------------------------------------------------
    // Track sync
    // -----------------------------------------------------------------------

    /**
     * Sync a single track: download audio + artwork, store metadata.
     * @return true if downloaded, false if skipped (already exists)
     */
    private suspend fun syncTrack(
        api: IgeetaApi,
        syncRoot: File,
        serverTrack: ServerTrack,
        playlistId: Int,
        position: Int,
    ): Boolean {
        val localPath = File(syncRoot, serverTrack.filePath)
        val alreadyExists = localPath.exists() && localPath.length() > 0

        if (!alreadyExists) {
            // Download audio
            localPath.parentFile?.mkdirs()
            api.streamTrack(serverTrack.filePath, FileOutputStream(localPath))
        }

        // Download artwork (if available and not already present)
        val artworkPath = findArtworkPath(localPath)
        android.util.Log.d("SyncManager", "syncTrack ${serverTrack.filePath}: artworkUrl=${serverTrack.artworkUrl != null}, existingSidecar=$artworkPath")
        val artworkRelName = if (artworkPath == null && serverTrack.artworkUrl != null) {
            downloadArtwork(api, serverTrack)
        } else null

        // Download track-specific art (DB relative path) if provided by server
        val trackArtRel = serverTrack.trackArtPath
        val trackArtName = if (trackArtRel.isNotEmpty()) {
            val ext = File(trackArtRel).extension.ifEmpty { "jpg" }
            val name = artworkFileName(trackArtRel, ext)
            val f = File(artworkBaseDir, name)
            if (!f.exists() || f.length() == 0L) {
                try {
                    f.parentFile?.mkdirs()
                    api.downloadArtwork(trackArtRel, FileOutputStream(f))
                    name
                } catch (_: Exception) {
                    ""
                }
            } else name
        } else ""

        // Upsert metadata in DB
        val existingTrack = db.getTrackByPath(serverTrack.filePath)
        db.upsertTrack(SyncedTrack(
            filePath = serverTrack.filePath,
            videoId = serverTrack.videoId,
            contentHash = serverTrack.contentHash,
            genre = serverTrack.genre,
            subGenre = serverTrack.subGenre,
            album = serverTrack.album,
            raag = serverTrack.raag,
            title = serverTrack.title,
            artists = com.google.gson.Gson().toJson(serverTrack.artists),
            instruments = com.google.gson.Gson().toJson(serverTrack.instruments),
            tags = com.google.gson.Gson().toJson(serverTrack.tags),
            duration = serverTrack.duration,
            fileSize = serverTrack.fileSize,
            bitrate = serverTrack.bitrate,
            source = serverTrack.source,
            importedAt = serverTrack.importedAt,
            updatedAt = serverTrack.updatedAt,
            rating = serverTrack.rating,
            playCount = serverTrack.playCount,
            lastPlayed = serverTrack.lastPlayed,
            artworkLocalPath = artworkRelName,
            trackArtPath = trackArtName,
            // Preserve local rating if it was set and not synced
            localRating = existingTrack?.localRating ?: serverTrack.rating,
            ratingDirty = existingTrack?.ratingDirty ?: 0,
            playlistServerId = playlistId,
            position = position,
            lastSyncAt = now(),
        ))

        return !alreadyExists
    }

    // -----------------------------------------------------------------------
    // Artwork
    // -----------------------------------------------------------------------

    /**
     * Download album art. Writes into the app-private artwork directory
     * (context.filesDir/artwork), which is always writable without storage
     * permissions — writing directly under /Music/iGeeta fails with EPERM on
     * scoped storage. Returns the stored file name (relative to the artwork
     * dir), or null if no art is available / the download failed.
     */
    private fun downloadArtwork(api: IgeetaApi, track: ServerTrack): String? {
        val artworkUrl = track.artworkUrl ?: return null
        val pathParam = extractArtworkPath(artworkUrl) ?: return null
        val ext = File(pathParam).extension.ifEmpty { "jpg" }
        val name = artworkFileName(pathParam, ext)
        val artworkFile = File(artworkBaseDir, name)
        if (artworkFile.exists() && artworkFile.length() > 0L) return name
        return try {
            artworkFile.parentFile?.mkdirs()
            api.downloadArtwork(pathParam, FileOutputStream(artworkFile))
            android.util.Log.d("SyncManager", "artwork downloaded -> ${artworkFile.absolutePath} (${artworkFile.length()} bytes)")
            name
        } catch (e: Exception) {
            android.util.Log.w("SyncManager", "artwork download failed for ${track.filePath}: ${e.message}", e)
            null
        }
    }

    private val artworkBaseDir: File
        get() = File(context.filesDir, "artwork").apply { if (!exists()) mkdirs() }

    private fun artworkFileName(relPath: String, ext: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(relPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$hash.$ext"
    }

    /**
     * Find existing artwork file next to the audio file.
     * Checks .jpg, .jpeg, .png, .webp — same convention as server.
     */
    private fun findArtworkPath(audioFile: File): File? {
        val base = audioFile.parentFile ?: return null
        val name = audioFile.nameWithoutExtension
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val candidate = File(base, "$name.$ext")
            if (candidate.exists()) return candidate
        }
        return null
    }

    private fun extractArtworkPath(artworkUrl: String): String? {
        // "/api/artwork?path=Classical/track.jpg&t=12345" → "Classical/track.jpg"
        // The path param is URL-encoded by the server (e.g. %2F for '/'), so decode it
        // here. IgeetaApi.downloadArtwork re-encodes it once before the request.
        val queryStart = artworkUrl.indexOf("?path=")
        if (queryStart == -1) return null
        val pathStart = queryStart + 6
        val pathEnd = artworkUrl.indexOf("&", pathStart)
        val raw = if (pathEnd == -1) {
            artworkUrl.substring(pathStart)
        } else {
            artworkUrl.substring(pathStart, pathEnd)
        }
        return try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    // -----------------------------------------------------------------------
    // Rating sync
    // -----------------------------------------------------------------------

    private suspend fun pushRatings(api: IgeetaApi) {
        val dirtyTracks = db.getDirtyRatings()
        for (track in dirtyTracks) {
            try {
                api.setRating(track.filePath, track.localRating)
                db.clearRatingDirty(track.filePath)
            } catch (_: Exception) {
                // Continue with other ratings
            }
        }
    }

    private suspend fun pullRatings(api: IgeetaApi) {
        // Pull server ratings down for every synced track, but never overwrite a
        // local edit that hasn't been pushed yet (rating_dirty = 1).
        val allTracks = db.getAllTracks()
        for (track in allTracks) {
            if (track.ratingDirty == 1) continue
            try {
                val serverTrack = api.getTrack(track.filePath)
                if (serverTrack.rating != track.rating) {
                    db.syncServerRating(track.filePath, serverTrack.rating)
                }
            } catch (_: Exception) {
                // Best-effort: skip tracks that fail to fetch.
            }
        }
    }

    // -----------------------------------------------------------------------
    // Raga metadata sync
    // -----------------------------------------------------------------------

    private suspend fun syncRagas(api: IgeetaApi) {
        try {
            val serverRagas = api.getRagas()
            for (raga in serverRagas) {
                db.upsertRaga(SyncedRaga(
                    name = raga.name,
                    mood = raga.mood,
                    prahara = raga.prahara,
                    season = raga.season,
                    varjitNotes = raga.varjitNotes,
                    associatedEmotions = raga.associatedEmotions,
                    description = raga.description,
                ))
            }
        } catch (_: Exception) {
            // Raga sync is best-effort
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private fun now(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
    }

    private fun relativize(base: File, path: File): String {
        return base.toURI().relativize(path.toURI()).path
    }
}
