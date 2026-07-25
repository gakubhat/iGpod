/*
 *     Copyright (C) 2025 The Gramophone authors
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package uk.akane.libphonograph.reader

import androidx.media3.common.MediaItem
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.EmptyFileNode
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist
import uk.akane.libphonograph.items.RaagasItem

data class ReaderResult(
    val songList: List<MediaItem>,
    val albumList: List<Album>?,
    val albumArtistList: List<Artist>?,
    val artistList: List<Artist>?,
    val genreList: List<Genre>?,
    val dateList: List<Date>?,
    val raagasList: List<RaagasItem>?,
    val idMap: Map<Long, MediaItem>?,
    val pathMap: Map<String, MediaItem>?,
    val folderStructure: FileNode?,
    val shallowFolder: FileNode?,
    val folders: Set<String>?,
    val foldersForWhitelist: Set<String>?
) {
    companion object {
        fun emptyReaderResult() = ReaderResult(
            listOf(), listOf(), listOf(), listOf(), listOf(), listOf(), listOf(),
            mapOf(), mapOf(),
            EmptyFileNode, EmptyFileNode,
            setOf(), setOf()
        )
    }
}

data class SimpleReaderResult(
    val songList: List<MediaItem>,
    val albumList: List<Album>,
    val albumArtistList: List<Artist>,
    val artistList: List<Artist>,
    val genreList: List<Genre>,
    val dateList: List<Date>,
    val playlistList: List<Playlist>,
    val folderStructure: FileNode,
    val shallowFolder: FileNode,
    val folders: Set<String>,
    val foldersForWhitelist: Set<String>
)
