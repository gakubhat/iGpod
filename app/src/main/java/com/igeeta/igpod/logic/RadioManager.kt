package com.igeeta.igpod.logic

import android.content.Context
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.igeeta.igpod.logic.GramophoneAlbumArtProvider
import com.igeeta.igpod.sync.SyncDatabase
import com.igeeta.igpod.sync.SyncedRaga
import com.igeeta.igpod.sync.SyncedTrack
import java.io.File
import java.util.Calendar

class RadioManager(private val context: Context) {

    enum class RadioMode { PRAHARA, ARTIST, INSTRUMENT, RAAGA }

    companion object {
        private const val HISTORY_SIZE = 20

        private val PRAHARA_WINDOWS = mapOf(
            1 to "6PM-9PM",
            2 to "9PM-12AM",
            3 to "12AM-3AM",
            4 to "3AM-6AM",
            5 to "6AM-9AM",
            6 to "9AM-12PM",
            7 to "12PM-3PM",
            8 to "3PM-6PM"
        )
    }

    private val syncDb = SyncDatabase.getInstance(context)
    private val history = mutableListOf<String>()
    private var _currentMode = RadioMode.PRAHARA
    private var _currentFilter = emptyList<String>()

    val currentMode: RadioMode get() = _currentMode
    val currentFilter: List<String> get() = _currentFilter

    fun setChannel(mode: RadioMode, filter: List<String> = emptyList()) {
        _currentMode = mode
        _currentFilter = filter
        history.clear()
    }

    fun getCurrentPrahara(): Int {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 18..23 -> 1  // 6PM-9PM, 9PM-12AM
            in 0..2 -> 2   // 9PM-12AM, 12AM-3AM
            in 3..5 -> 4   // 3AM-6AM
            in 6..8 -> 5   // 6AM-9AM
            in 9..11 -> 6  // 9AM-12PM
            in 12..14 -> 7 // 12PM-3PM
            in 15..17 -> 8 // 3PM-6PM
            else -> 1
        }
    }

    fun getPraharaWindow(prahara: Int): String {
        return PRAHARA_WINDOWS[prahara] ?: "Unknown"
    }

    fun getRagasForCurrentTime(): List<SyncedRaga> {
        val prahara = getCurrentPrahara()
        return runBlocking { syncDb.getAllRagas() }.filter { raga ->
            raga.prahara.contains("$prahara:")
        }
    }

    fun getDistinctArtists(): List<String> {
        val tracks = runBlocking { syncDb.getAllTracks() }
        val allArtists = mutableSetOf<String>()
        for (track in tracks) {
            try {
                val array = org.json.JSONArray(track.artists)
                for (i in 0 until array.length()) {
                    val artist = array.getString(i)
                    if (artist.isNotBlank()) allArtists.add(artist)
                }
            } catch (_: Exception) {}
        }
        return allArtists.sorted()
    }

    fun getDistinctInstruments(): List<String> {
        val tracks = runBlocking { syncDb.getAllTracks() }
        val allInstruments = mutableSetOf<String>()
        for (track in tracks) {
            try {
                val array = org.json.JSONArray(track.instruments)
                for (i in 0 until array.length()) {
                    val inst = array.getString(i)
                    if (inst.isNotBlank()) allInstruments.add(inst)
                }
            } catch (_: Exception) {}
        }
        return allInstruments.sorted()
    }

    fun getDistinctRaags(): List<String> {
        val tracks = runBlocking { syncDb.getAllTracks() }
        return tracks.map { it.raag }.filter { it.isNotBlank() }.distinct().sorted()
    }

    fun getNextTrack(): MediaItem? {
        android.util.Log.d("RadioManager", "getNextTrack: mode=$_currentMode, filter=$_currentFilter")
        val candidates = when (_currentMode) {
            RadioMode.PRAHARA -> getPraharaCandidates()
            RadioMode.ARTIST -> getFilteredCandidates("artist")
            RadioMode.INSTRUMENT -> getFilteredCandidates("instrument")
            RadioMode.RAAGA -> getFilteredCandidates("raaga")
        }
        android.util.Log.d("RadioManager", "getNextTrack: ${candidates.size} candidates")

        val filtered = candidates.filter { it.filePath !in history }
        if (filtered.isEmpty()) {
            // If all candidates are in history, clear history and try again
            history.clear()
            val retry = candidates.shuffled()
            if (retry.isEmpty()) return null
            val track = retry.first()
            addToHistory(track.filePath)
            return trackToMediaItem(track)
        }

        val track = filtered.random()
        addToHistory(track.filePath)
        return trackToMediaItem(track)
    }

    private fun getPraharaCandidates(): List<SyncedTrack> {
        val allTracks = runBlocking { syncDb.getAllTracks() }
        android.util.Log.d("RadioManager", "getPraharaCandidates: ${allTracks.size} total tracks")
        val prahara = getCurrentPrahara()
        android.util.Log.d("RadioManager", "getPraharaCandidates: current prahara=$prahara")

        // Strategy: find ragas for current prahara that have tracks
        val ragasForPrahara = getRagasForCurrentTime().map { it.name }.toSet()
        android.util.Log.d("RadioManager", "getPraharaCandidates: ragasForPrahara=$ragasForPrahara")
        val praharaTracks = allTracks.filter { it.raag in ragasForPrahara }
        android.util.Log.d("RadioManager", "getPraharaCandidates: praharaTracks=${praharaTracks.size}")
        if (praharaTracks.isNotEmpty()) return praharaTracks

        // Fallback: try adjacent praharas
        for (offset in listOf(1, -1, 2, -2)) {
            val adjPrahara = ((prahara + offset - 1).rem(8)) + 1
            val adjRagas = runBlocking { syncDb.getAllRagas() }
                .filter { it.prahara.contains("$adjPrahara:") }
                .map { it.name }.toSet()
            val adjTracks = allTracks.filter { it.raag in adjRagas }
            if (adjTracks.isNotEmpty()) return adjTracks
        }

        // Fallback: any track with a raaga
        val raagTracks = allTracks.filter { it.raag.isNotBlank() }
        android.util.Log.d("RadioManager", "getPraharaCandidates: raagTracks=${raagTracks.size}")
        if (raagTracks.isNotEmpty()) return raagTracks

        // Fallback: any track
        android.util.Log.d("RadioManager", "getPraharaCandidates: returning all ${allTracks.size} tracks")
        return allTracks
    }

    private fun getFilteredCandidates(type: String): List<SyncedTrack> {
        val allTracks = runBlocking { syncDb.getAllTracks() }
        if (_currentFilter.isEmpty()) return allTracks

        return allTracks.filter { track ->
            when (type) {
                "artist" -> {
                    try {
                        val array = org.json.JSONArray(track.artists)
                        val trackArtists = (0 until array.length()).map { array.getString(it) }
                        _currentFilter.any { filter -> trackArtists.any { it.contains(filter, ignoreCase = true) } }
                    } catch (_: Exception) { false }
                }
                "instrument" -> {
                    try {
                        val array = org.json.JSONArray(track.instruments)
                        val trackInsts = (0 until array.length()).map { array.getString(it) }
                        _currentFilter.any { filter -> trackInsts.any { it.contains(filter, ignoreCase = true) } }
                    } catch (_: Exception) { false }
                }
                "raaga" -> _currentFilter.any { it.equals(track.raag, ignoreCase = true) }
                else -> true
            }
        }
    }

    private fun addToHistory(path: String) {
        history.add(path)
        if (history.size > HISTORY_SIZE) {
            history.removeAt(0)
        }
    }

    private fun trackToMediaItem(track: SyncedTrack): MediaItem {
        val syncRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "iGeeta"
        )
        val fullPath = File(syncRoot, track.filePath)
        val id = track.filePath.hashCode().toLong()

        val artists = try {
            val array = org.json.JSONArray(track.artists)
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) { emptyList() }

        val artworkUri = GramophoneAlbumArtProvider.buildSongUri(id, fullPath)

        return MediaItem.Builder()
            .setMediaId("Db:$id")
            .setUri(android.net.Uri.fromFile(fullPath))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setDurationMs((track.duration * 1000).toLong())
                    .setTitle(track.title.ifEmpty { track.filePath.substringAfterLast('/') })
                    .setArtist(artists.firstOrNull())
                    .setAlbumTitle(track.album.ifEmpty { null })
                    .setArtworkUri(artworkUri)
                    .setGenre(track.genre.ifEmpty { null })
                    .setUserRating(androidx.media3.common.HeartRating(false))
                    .build()
            )
            .build()
    }

    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}
