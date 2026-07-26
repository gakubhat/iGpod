package com.igeeta.igpod.sync

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.igeeta.igpod.logic.GramophoneAlbumArtProvider
import org.json.JSONArray
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.RaagasItem
import uk.akane.libphonograph.items.EXTRA_FILE
import uk.akane.libphonograph.reader.ReaderResult
import uk.akane.libphonograph.utils.MiscUtils
import java.io.File

object DbReader {

    fun readFromDatabase(
        syncDb: SyncDatabase,
        rootPath: String,
        context: android.content.Context
    ): ReaderResult {
        val tracks = runBlocking { syncDb.getAllTracks() }
        if (tracks.isEmpty()) {
            return ReaderResult.emptyReaderResult()
        }

        val rootDir = File(rootPath)
        val artworkBaseDir = File(context.filesDir, "artwork")
        val songs = mutableListOf<MediaItem>()
        val albumMap = hashMapOf<String, MiscUtils.AlbumImpl>()
        val artistMap = hashMapOf<String, Artist>()
        val genreMap = hashMapOf<String, Genre>()
        val dateMap = hashMapOf<String, Date>()
        val pathMap = hashMapOf<String, MediaItem>()

        for ((index, track) in tracks.withIndex()) {
            val fullPath = File(rootDir, track.filePath)
            if (!fullPath.exists()) continue

            val id = track.filePath.hashCode().toLong()
            val fileUri = Uri.fromFile(fullPath)
            val artworkUri = resolveArtworkUri(track, artworkBaseDir, id, fullPath)

            val artists = parseJsonArray(track.artists)
            val primaryArtist = artists.firstOrNull()

            val song = MediaItem.Builder()
                .setMediaId("Db:$id")
                .setUri(fileUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setDurationMs((track.duration * 1000).toLong())
                        .setTitle(track.title.ifEmpty { track.filePath.substringAfterLast('/') })
                        .setArtist(primaryArtist)
                        .setAlbumTitle(track.album.ifEmpty { null })
                        .setArtworkUri(artworkUri)
                        .setGenre(track.genre.ifEmpty { null })
                        .setReleaseYear(null)
                        .setUserRating(androidx.media3.common.HeartRating(track.rating >= 3))
                        .setExtras(android.os.Bundle().apply {
                            putString(EXTRA_FILE, fullPath.absolutePath)
                        })
                        .build()
                )
                .build()

            songs.add(song)
            pathMap[track.filePath] = song

            if (track.album.isNotEmpty()) {
                val albumKey = track.album
                albumMap.getOrPut(albumKey) {
                    MiscUtils.AlbumImpl(
                        id = albumKey.hashCode().toLong(),
                        title = albumKey,
                        albumArtist = primaryArtist,
                        albumArtistId = primaryArtist?.hashCode()?.toLong(),
                        cover = artworkUri,
                        albumYear = null,
                        albumAddDate = null,
                        albumModifiedDate = null,
                        songList = mutableListOf()
                    )
                }.songList.add(song)
            }

            for (artist in artists) {
                if (artist.isEmpty()) continue
                artistMap.getOrPut(artist) {
                    Artist(
                        id = artist.hashCode().toLong(),
                        title = artist,
                        songList = mutableListOf(),
                        albumList = mutableListOf()
                    )
                }.let { artistObj ->
                    (artistObj.songList as MutableList).add(song)
                }
            }

            if (track.subGenre.isNotEmpty()) {
                genreMap.getOrPut(track.subGenre) {
                    Genre(
                        id = track.subGenre.hashCode().toLong(),
                        title = track.subGenre,
                        songList = mutableListOf()
                    )
                }.let { genreObj ->
                    (genreObj.songList as MutableList).add(song)
                }
            }
        }

        val albumList = albumMap.values.toList()
        val artistList = artistMap.values.toList()
        val genreList = genreMap.values.toList()
        val dateList = dateMap.values.toList()

        // Build raagas list from raga_metadata table
        val raagasList = buildRaagasList(syncDb, pathMap)

        return ReaderResult(
            songList = songs,
            albumList = albumList,
            albumArtistList = artistList,
            artistList = artistList,
            genreList = genreList,
            dateList = dateList,
            raagasList = raagasList,
            idMap = mapOf(),
            pathMap = pathMap,
            folderStructure = uk.akane.libphonograph.items.EmptyFileNode,
            shallowFolder = uk.akane.libphonograph.items.EmptyFileNode,
            folders = setOf(),
            foldersForWhitelist = setOf()
        )
    }

    /**
     * Resolve album art for a track.
     *
     * Artwork files are stored on disk under the sync root, with their relative
     * paths coming from the synced database ([SyncedTrack.trackArtPath] for
     * track-specific art, [SyncedTrack.artworkLocalPath] for album art). We prefer
     * the dedicated track art, then fall back to album art, both sourced from the
     * DB. If neither exists (e.g. MP3 has embedded art), we fall back to the
     * content provider that extracts embedded art from the audio file.
     */
    private fun resolveArtworkUri(
        track: SyncedTrack,
        artworkBaseDir: File,
        id: Long,
        fullPath: File,
    ): Uri {
        val candidates = listOf(track.trackArtPath, track.artworkLocalPath)
            .mapNotNull { rel -> rel?.takeIf { it.isNotEmpty() }?.let { File(artworkBaseDir, it) } }
            .filter { it.exists() && it.length() > 0 }
        val artFile = candidates.firstOrNull()
        return if (artFile != null) {
            Uri.fromFile(artFile)
        } else {
            // Fallback: embedded art inside the MP3 file, served via content provider.
            GramophoneAlbumArtProvider.buildSongUri(id, fullPath)
        }
    }

    private fun parseJsonArray(json: String): List<String> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildRaagasList(
        syncDb: SyncDatabase,
        pathMap: Map<String, androidx.media3.common.MediaItem>
    ): List<RaagasItem> {
        return runBlocking {
            val ragas = syncDb.getAllRagas()
            ragas.mapNotNull { raga ->
                val tracks = syncDb.getTracksByRaag(raga.name)
                val mediaItems = tracks.mapNotNull { track ->
                    pathMap[track.filePath]
                }
                if (mediaItems.isEmpty()) return@mapNotNull null
                RaagasItem(
                    id = raga.name.hashCode().toLong(),
                    title = raga.name,
                    songList = mediaItems,
                    mood = raga.mood,
                    prahara = raga.prahara,
                    season = raga.season,
                    varjitNotes = raga.varjitNotes,
                    associatedEmotions = raga.associatedEmotions,
                    description = raga.description,
                )
            }
        }
    }

    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}
