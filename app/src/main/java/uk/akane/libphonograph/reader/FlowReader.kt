/*
 *     Copyright (C) 2025 nift4
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

import android.content.Context
import android.os.Build
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import com.igeeta.igpod.logic.emitOrDie
import com.igeeta.igpod.logic.utils.flows.Invalidation
import com.igeeta.igpod.logic.utils.flows.PauseManagingSharedFlow.Companion.sharePauseableIn
import com.igeeta.igpod.logic.utils.flows.conflateAndBlockWhenPaused
import com.igeeta.igpod.logic.utils.flows.provideReplayCacheInvalidationManager
import com.igeeta.igpod.logic.utils.flows.repeatUntilDoneWhenUnpaused
import com.igeeta.igpod.logic.utils.flows.requireReplayCacheInvalidationManager
import uk.akane.libphonograph.dynamicitem.RecentlyAdded
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist
import uk.akane.libphonograph.items.RaagasItem
import com.igeeta.igpod.sync.DbReader
import com.igeeta.igpod.sync.SyncDatabase

/**
 * SimpleReader reimplementation using flows with focus on efficiency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowReader(
    context: Context,
    recentlyAddedFilterSecondFlow: SharedFlow<Long?>, // null means don't generate recently added
    private val syncDb: SyncDatabase, // iGeeta: database instance for DB mode (non-null singleton)
    private val dbRootPath: String, // iGeeta: root path for DB mode
) {
    // IMPORTANT: Do not use distinctUntilChanged() or StateFlow here because equals() on thousands
    // of MediaItems is very, very expensive!
    private var awaitingRefresh = false
    var hadFirstRefresh = false
        private set
    private val scope = CoroutineScope(Dispatchers.IO + CoroutineName("FlowReader"))
    private val finishRefreshTrigger = MutableSharedFlow<Unit>(replay = 0)
    private val manualRefreshTrigger = MutableSharedFlow<Unit>(replay = 1)

    init {
        manualRefreshTrigger.emitOrDie(Unit)
    }

    private suspend fun maybeDoRead(
        context: Context
    ) =
        // iGeeta: DB-only mode — read the library from the local SQLite database.
        DbReader.readFromDatabase(syncDb, dbRootPath, context)

    // These expensive Reader calls are only done if we have someone (UI) observing the result AND
    // something changed. The PauseableFlows mechanism allows us to skip any unnecessary work.
    private val rawPlaylistFlow: Flow<List<Any>> =
        // Database mode: read playlists from SQLite
        manualRefreshTrigger
            .onEach { requireReplayCacheInvalidationManager().invalidate() }
            .conflateAndBlockWhenPaused()
            .flatMapLatest { _ ->
                kotlinx.coroutines.flow.flow {
                    val playlists = syncDb.getAllPlaylists()
                    val pathMap = pathMapFlow.first()
                    val result = playlists.mapNotNull { syncedPlaylist ->
                        val tracks = syncDb.getTracksByPlaylist(syncedPlaylist.serverId) ?: return@mapNotNull null
                        val mediaItems = tracks.mapNotNull { track ->
                            pathMap[track.filePath]
                        }
                        if (mediaItems.isEmpty()) return@mapNotNull null
                        uk.akane.libphonograph.items.Playlist(
                            id = syncedPlaylist.serverId.toLong(),
                            title = syncedPlaylist.name,
                            path = null,
                            cover = null,
                            dateAdded = null,
                            dateModified = null,
                            songList = mediaItems
                        )
                    }
                    emit(result)
                }
            }
            .provideReplayCacheInvalidationManager(copyDownstream = Invalidation.Optional)
            .sharePauseableIn(scope, WhileSubscribed(20000), WhileSubscribed(2000), replay = 1)
    private val readerFlow: Flow<ReaderResult> =
        // Database mode: use manualRefreshTrigger directly, no MediaStore observation
        manualRefreshTrigger
            .onEach { requireReplayCacheInvalidationManager().invalidate() }
            .conflateAndBlockWhenPaused()
            .mapLatest { _ ->
                maybeDoRead(
                    context
                )
            }
            .onEach {
                finishRefreshTrigger.emit(Unit)
                awaitingRefresh = true
            }
            .provideReplayCacheInvalidationManager(copyDownstream = Invalidation.Optional)
            .sharePauseableIn(scope, WhileSubscribed(20000), WhileSubscribed(2000), replay = 1)
    val idMapFlow: Flow<Map<Long, MediaItem>> = readerFlow.map { it.idMap!! }
    val pathMapFlow = readerFlow.map { it.pathMap!! }
    val songListFlow: Flow<List<MediaItem>> = readerFlow.map { it.songList }
    private val recentlyAddedFlow = recentlyAddedFilterSecondFlow.distinctUntilChanged()
        .onEach { requireReplayCacheInvalidationManager().invalidate() }
        .combine(songListFlow) { recentlyAddedFilterSecond, songList ->
            if (recentlyAddedFilterSecond != null)
                RecentlyAdded(
                    (System.currentTimeMillis() / 1000L) - recentlyAddedFilterSecond,
                    songList
                )
            else
                null
        }
        .provideReplayCacheInvalidationManager(copyDownstream = Invalidation.Optional)
        .sharePauseableIn(scope, WhileSubscribed(20000), WhileSubscribed(2000), replay = 1)
    private val mappedPlaylistsFlow: Flow<List<Playlist>> =
        // Database mode: rawPlaylistFlow already returns Playlist objects
        rawPlaylistFlow.map { items ->
            items.filterIsInstance<Playlist>()
        }
    val albumListFlow: Flow<List<Album>> = readerFlow.map { it.albumList!! }
    val albumArtistListFlow: Flow<List<Artist>> = readerFlow.map { it.albumArtistList!! }
    val artistListFlow: Flow<List<Artist>> = readerFlow.map { it.artistList!! }
    val genreListFlow: Flow<List<Genre>> = readerFlow.map { it.genreList!! }
    val dateListFlow: Flow<List<Date>> = readerFlow.map { it.dateList!! }
    val raagasListFlow: Flow<List<RaagasItem>> = readerFlow.map { it.raagasList!! }
    val playlistListFlow = combine(mappedPlaylistsFlow, recentlyAddedFlow)
    { mappedPlaylists, recentlyAdded ->
        if (recentlyAdded != null) mappedPlaylists + recentlyAdded else mappedPlaylists
    }
    val folderStructureFlow: Flow<FileNode> = readerFlow.map { it.folderStructure!! }
    val shallowFolderFlow: Flow<FileNode> = readerFlow.map { it.shallowFolder!! }
    val foldersFlow: Flow<Set<String>> = readerFlow.map { it.folders!! }
    val foldersForWhitelistFlow: Flow<Set<String>> = readerFlow.map { it.foldersForWhitelist!! }

    /**
     * If the library hasn't been loaded yet, forces a load of the library. Otherwise forces a
     * manual refresh of the library. Suspends until new data is available.
     */
    suspend fun refresh() {
        hadFirstRefresh = true
        coroutineScope {
            if (!awaitingRefresh) {
                // The playlist flow uses pull principle, and causes readerFlow to refresh, so
                // getting a value here means all data is up to date
                playlistListFlow.first()
                return@coroutineScope
            }
            val waiter = launch {
                finishRefreshTrigger.first()
            }
            manualRefreshTrigger.emit(Unit)
            waiter.join()
        }
    }
}