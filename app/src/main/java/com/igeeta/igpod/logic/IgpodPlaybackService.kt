/*
 *     Copyright (C) 2024 Akane Foundation
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

package com.igeeta.igpod.logic

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.BundleListRetriever
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.HeartRating
import androidx.media3.common.IllegalSeekPositionException
import com.igeeta.igpod.logic.IgpodAlbumArtProvider
import com.igeeta.igpod.sync.SyncDatabase
import com.igeeta.igpod.sync.SyncedTrack
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.isBitmapFactorySupportedMimeType
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.AsyncFunction
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.igeeta.igpod.R
import com.igeeta.igpod.logic.utils.CircularShuffleOrder
import com.igeeta.igpod.logic.utils.Flags
import com.igeeta.igpod.logic.utils.LastPlayedManager
import com.igeeta.igpod.logic.utils.MediaItemList
import com.igeeta.igpod.logic.utils.exoplayer.EndedWorkaroundPlayer
import com.igeeta.igpod.logic.utils.exoplayer.IgpodExtractorsFactory
import com.igeeta.igpod.logic.utils.exoplayer.IgpodMediaSourceFactory
import com.igeeta.igpod.ui.MainActivity
import org.nift4.mediastorecompat.MediaStoreCompat
import uk.akane.libphonograph.dynamicitem.Favorite
import uk.akane.libphonograph.items.albumId
import uk.akane.libphonograph.manipulator.ItemManipulator
import uk.akane.libphonograph.manipulator.PlaylistSerializer
import uk.akane.libphonograph.manipulator.PlaylistSerializer.Entry
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.random.Random


/**
 * [IgpodPlaybackService] is a server service.
 * It's using exoplayer2 as its player backend.
 */
class IgpodPlaybackService : MediaLibraryService(), MediaSessionService.Listener,
    MediaLibraryService.MediaLibrarySession.Callback, Player.Listener, MediaController.Listener,
    AnalyticsListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "GramoPlaybackService"
        const val NOTIFY_CHANNEL_ID = "serviceFgsError"
        const val NOTIFY_ID = 1
        private const val FAVE_ID = 2
        private const val PENDING_INTENT_SESSION_ID = 0
        const val PENDING_INTENT_NOTIFY_ID = 1
        const val PENDING_INTENT_WIDGET_ID = 2
        const val PENDING_INTENT_FAVE_ID = 3

        const val SERVICE_SET_TIMER = "set_timer"
        const val SERVICE_QUERY_TIMER = "query_timer"
            const val SERVICE_TIMER_CHANGED = "changed_timer"
        const val SERVICE_SET_MEDIA_ITEMS_SEAMLESSLY = "set_media_items_seamlessly"
        const val SERVICE_SET_MEDIA_ITEMS_ATOMIC = "set_media_items_atomic"

        const val SERVICE_QB_GET_INACTIVE_LIST = "qb_get_inactive_list"
        const val SERVICE_QB_LOAD_QUEUE = "qb_load"
        const val SERVICE_QB_GET_QUEUE_FOR_UI = "qb_get_queue_for_ui"
        const val SERVICE_QB_DEL = "qb_delete"
        const val SERVICE_QB_REORDER = "qb_reorder"
        const val SERVICE_QB_PIN_QUEUE ="qb_pin_queue"
        const val SERVICE_QB_UNPIN_QUEUE ="qb_unpin_queue"

        const val SERVICE_QB_AGE = "qb_age"

        // Android Auto browse-tree node IDs
        private const val ROOT_ID = "root"
        private const val HOME_ID = "home"
        private const val ALBUMS_ID = "albums"
        private const val ARTISTS_ID = "artists"
        private const val RAAGAS_ID = "raagas"
        private const val SONGS_ID = "songs"
        private const val PLAYLISTS_ID = "playlists"
        private const val RADIO_ID = "radio"
        private const val RECENTLY_PLAYED_ID = "recently_played"

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

    private var lastSessionId = 0
    private val internalPlaybackThread =
        HandlerThread("ExoPlayer:Playback", Process.THREAD_PRIORITY_AUDIO)
    private var mediaSession: MediaLibrarySession? = null
    val endedWorkaroundPlayer
        get() = mediaSession?.player as EndedWorkaroundPlayer?
    private var controller: MediaController? = null
    lateinit var qb: QueueBoard
    private lateinit var customCommands: List<CommandButton>
    private lateinit var handler: Handler
    private lateinit var mainExecutor: Executor
    private lateinit var playbackHandler: Handler
    private lateinit var nm: NotificationManagerCompat
    private lateinit var lastPlayedManager: LastPlayedManager
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.Main)
    private val lastPlaylistLoaded = CompletableDeferred<Unit>()

    private fun getRepeatCommand() =
        when (controller!!.repeatMode) {
            Player.REPEAT_MODE_OFF -> customCommands[2]
            Player.REPEAT_MODE_ALL -> customCommands[3]
            Player.REPEAT_MODE_ONE -> customCommands[4]
            else -> throw IllegalArgumentException()
        }

    private fun getShufflingCommand() =
        if (controller!!.shuffleModeEnabled)
            customCommands[1]
        else
            customCommands[0]

    private val timer: Runnable = Runnable {
        if (timerPauseOnEnd) {
            endedWorkaroundPlayer!!.exoPlayer.pauseAtEndOfMediaItems = true
        } else {
            controller!!.pause()
        }
        timerDuration = null
    }
    private var timerPauseOnEnd = false
    private var timerDuration: Long? = null
        set(value) {
            field = value
            if (value != null && value > 0) {
                handler.postDelayed(timer, value - SystemClock.elapsedRealtime())
            } else {
                handler.removeCallbacks(timer)
            }
            mediaSession!!.broadcastCustomCommand(
                SessionCommand(SERVICE_TIMER_CHANGED, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }

    private val seekReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val to =
                intent.extras?.getLong("seekTo", C.INDEX_UNSET.toLong()) ?: C.INDEX_UNSET.toLong()
            if (to != C.INDEX_UNSET.toLong())
                controller?.seekTo(to)
        }
    }

    override fun onCreate() {
        Log.i(TAG, "+onCreate()")
        super.onCreate()
        internalPlaybackThread.start()
        playbackHandler = Handler(internalPlaybackThread.looper)
        handler = Handler(Looper.getMainLooper())
        mainExecutor = ContextCompat.getMainExecutor(this)
        nm = NotificationManagerCompat.from(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        qb = QueueBoard(this)
        setListener(this)
        setForegroundServiceTimeoutMs(120000)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(
                NOTIFY_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH
            ).apply {
                setName(getString(R.string.error_in_bg))
                setVibrationEnabled(true)
                setVibrationPattern(longArrayOf(0L, 200L))
                setLightsEnabled(false)
                setShowBadge(false)
                setSound(null, null)
            }.build()
        )

        customCommands =
            listOf(
                CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF)
                    .setDisplayName(getString(R.string.shuffle))
                    .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, true)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
                    .setDisplayName(getString(R.string.shuffle))
                    .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, false)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_OFF)
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ALL)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_ALL)
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ONE)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_ONE)
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_OFF)
                    .build(),
            )
        prefs.registerOnSharedPreferenceChangeListener(this)
        onSharedPreferenceChanged(prefs, null) // read initial values
        val player = EndedWorkaroundPlayer(
            this,
            exoPlayer = ExoPlayer.Builder(
                this,
                DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setEnableAudioTrackPlaybackParams(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
                IgpodMediaSourceFactory(
                    DefaultDataSource.Factory(this),
                    IgpodExtractorsFactory().also {
                        it.setConstantBitrateSeekingEnabled(true)
                        it.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
                    })
            )
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true
                )
                .setHandleAudioBecomingNoisy(true)
                .setTrackSelector(DefaultTrackSelector(this).apply {
                    setParameters(
                        buildUponParameters()
                        .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                        .setAudioOffloadPreferences(
                            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                                .apply {
                                    val config =
                                        prefs.getStringStrict("offload", "0")?.toIntOrNull()
                                    if (config != null && config > 0 && Flags.OFFLOAD) {
                                        setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                                        setIsGaplessSupportRequired(config == 2)
                                    }
                                }
                                .build()))
                })
                .setPlaybackLooper(internalPlaybackThread.looper)
                .build(),
                queueBoard = qb,
        )
        player.exoPlayer.addAnalyticsListener(EventLogger())
        player.exoPlayer.addAnalyticsListener(this)
        player.exoPlayer.setShuffleOrder(CircularShuffleOrder(player, 0, 0, Random.nextLong()))
        lastPlayedManager = LastPlayedManager(this, player)
        lastPlayedManager.allowSavingState = false

        mediaSession =
            MediaLibrarySession
                .Builder(this, player, this)
                // CacheBitmapLoader is required for MeiZuLyricsMediaNotificationProvider
                .setBitmapLoader(CacheBitmapLoader(object : BitmapLoader {
                    // Coil-based bitmap loader to reuse Coil's caching and to make sure we use
                    // the same cover art as the rest of the app, ie MediaStore's cover

                    private val limit by lazy { MediaSession.getBitmapDimensionLimit(this@IgpodPlaybackService) }

                    // the suppression is correct, we want identity of the byte array as it will
                    // stay the same over one song's lifetime
                    @Suppress("KotlinArrayHashCode")
                    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
                        return CallbackToFutureAdapter.getFuture { completer ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(this@IgpodPlaybackService)
                                    .data(data)
                                    .memoryCacheKey(data.hashCode().toString())
                                    .size(limit, limit)
                                    .allowHardware(false)
                                    .target(
                                        onStart = { _ ->
                                            // We don't need or want a placeholder.
                                        },
                                        onSuccess = { result ->
                                            completer.set((result as BitmapImage).bitmap)
                                        },
                                        onError = { _ ->
                                            completer.setException(
                                                Exception(
                                                    "coil onError called for byte array"
                                                )
                                            )
                                        }
                                    )
                                    .build())
                                .also {
                                    completer.addCancellationListener(
                                        { it.dispose() },
                                        mainExecutor
                                    )
                                }
                            "coil load for ${data.hashCode()}"
                        }
                    }

                    override fun loadBitmap(
                        uri: Uri
                    ): ListenableFuture<Bitmap> {
                        return CallbackToFutureAdapter.getFuture { completer ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(this@IgpodPlaybackService)
                                    .data(uri)
                                    .size(limit, limit)
                                    .allowHardware(false)
                                    .target(
                                        onStart = { _ ->
                                            // We don't need or want a placeholder.
                                        },
                                        onSuccess = { result ->
                                            completer.set((result as BitmapImage).bitmap)
                                        },
                                        onError = { _ ->
                                            completer.setException(
                                                Exception(
                                                    "coil onError called" +
                                                            " (normal if no album art exists)"
                                                )
                                            )
                                        }
                                    )
                                    .build())
                                .also {
                                    completer.addCancellationListener(
                                        { it.dispose() },
                                        mainExecutor
                                    )
                                }
                            "coil load for $uri"
                        }
                    }

                    override fun supportsMimeType(mimeType: String): Boolean {
                        return isBitmapFactorySupportedMimeType(mimeType)
                    }

                    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
                        return metadata.artworkUri?.let { loadBitmap(it) }
                    }
                }))
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        PENDING_INTENT_SESSION_ID,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
        addSession(mediaSession!!)
        controller = MediaController.Builder(this, mediaSession!!.token).buildAsync().get()
        controller!!.addListener(this)
        if (controller!!.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            onAudioSessionIdChanged(controller!!.audioSessionId)
        }
        ContextCompat.registerReceiver(
            this,
            seekReceiver,
            IntentFilter("$packageName.SEEK_TO"),
            @SuppressLint("WrongConstant") // why is this needed?
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == com.igeeta.igpod.sync.SyncService.ACTION_LIBRARY_CHANGED) {
                        try {
                            mediaSession?.notifyChildrenChanged(ROOT_ID, Int.MAX_VALUE, null)
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "notifyChildrenChanged failed", e)
                        }
                    }
                }
            },
            IntentFilter(com.igeeta.igpod.sync.SyncService.ACTION_LIBRARY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch {
            lastPlayedManager.restore { items ->
                if (mediaSession == null) return@restore
                if (items != null) {
                    val list = mapMediaItemsForFavorites(items.items.mediaItems)
                    withContext(Dispatchers.Main) {
                        if (lastPlayedManager.allowSavingState)
                            return@withContext // media items were already applied to player
                        try {
                            if (list.size >= items.items.startIndex) {
                                endedWorkaroundPlayer?.setMediaItems(
                                    list,
                                    items.items.startIndex,
                                    items.items.startPositionMs,
                                    items.title,
                                    false, /* TODO(MQ) */
                                    true, /* TODO(MQ) */
                                    items.seed,
                                    items.isEnded,
                                    items.repeatMode,
                                    items.shuffle,
                                    items.playbackParameters,
                                )
                            } else {
                                endedWorkaroundPlayer?.setMediaItems(
                                    list,
                                    C.INDEX_UNSET,
                                    C.TIME_UNSET,
                                    items.title,
                                    false, /* TODO(MQ) */
                                    true, /* TODO(MQ) */
                                    items.seed,
                                    items.isEnded,
                                    items.repeatMode,
                                    items.shuffle,
                                    items.playbackParameters,
                                )
                                Log.w(TAG, "failed to restore index")
                            }
                        } catch (e: IllegalSeekPositionException) {
                            Log.e(TAG, "failed to restore", e)
                        }
                        if (mediaSession?.connectedControllers?.find {
                                it.connectionHints
                                    .getBoolean("PrepareWhenReady", false)
                            } != null) {
                            handler.post { endedWorkaroundPlayer?.prepare() }
                        }
                    }
                } else
                    lastPlaylistLoaded.complete(Unit)
            }
        }
        scope.launch(Dispatchers.Default) {
            gramophoneApplication.reader.playlistListFlow.map { it.find { p -> p is Favorite } }
                .collect { list ->
                    val ids = list?.songList?.map { it.mediaId } ?: emptyList()
                    withContext(Dispatchers.Main + NonCancellable) {
                        controller?.let { controller ->
                            for (i in 0..<controller.mediaItemCount) {
                                val item = controller.getMediaItemAt(i)
                                val isHeart = (item.mediaMetadata.userRating as? HeartRating)
                                    ?.isHeart == true
                                val shouldBeHeart = ids.contains(item.mediaId)
                                if (isHeart != shouldBeHeart ||
                                    item.mediaMetadata.userRating !is HeartRating) {
                                    controller.replaceMediaItem(i, item
                                        .buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                                            .setUserRating(HeartRating(shouldBeHeart))
                                            .build()).build())
                                }
                            }
                        }
                    }
                }
        }
        Log.i(TAG, "-onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var extras = intent?.extras
        // Deserialize all extras to be able to log them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            extras = extras?.deepCopy()
        } else {
            if (extras != null) {
                for (i in extras.keySet()) {
                    @Suppress("deprecation") extras.get(i)
                }
            }
        }
        Log.i(TAG, "onStartCommand(): $intent, ${extras?.toString()}")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaId: String,
        rating: Rating
    ): ListenableFuture<SessionResult> {
        if (rating !is HeartRating) {
            return Futures.immediateFuture(SessionResult(
                SessionResult.RESULT_ERROR_BAD_VALUE))
        }
        val completion = SettableFuture.create<SessionResult>()
        lifecycleScope.launch(Dispatchers.Default) {
            val item = gramophoneApplication.reader.songListFlow.map {
                it.find { s -> s.mediaId == mediaId } }.first()
            if (item == null) {
                completion.set(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                return@launch
            }
            val song = Entry.ofMediaItem(item)
            if (song == null) {
                completion.set(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                return@launch
            }
            val uriIn = gramophoneApplication.reader.playlistListFlow.map { it.find { p ->
                p is Favorite } }.first()?.id?.let {
                ContentUris.withAppendedId(@Suppress("deprecation")
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, it)
            }
            val token = if (uriIn != null) {
                MediaStoreCompat.needRequestBytesWrite(this@IgpodPlaybackService,
                    uriIn)
            } else {
                MediaStoreCompat.needRequestCreate(this@IgpodPlaybackService,
                    ItemManipulator.getDefaultPlaylistFile(
                        ItemManipulator.FAVORITES).path)
            }
            var error: Exception? = null
            if (token == null) {
                try {
                    val uri = uriIn ?: ItemManipulator.createPlaylist(
                        this@IgpodPlaybackService, ItemManipulator
                            .getDefaultPlaylistFile(ItemManipulator.FAVORITES))
                    val readback = if (uriIn != null) ItemManipulator.readbackPlaylist(
                        this@IgpodPlaybackService, uri) else
                            PlaylistSerializer.Playlist.create()
                    val newSongs = if (rating.isHeart) {
                        readback.entries + song
                    } else {
                        readback.entries.filter { !song.fuzzyEquals(it) }
                    }
                    ItemManipulator.setPlaylistContent(this@IgpodPlaybackService, uri,
                        readback.copy(entries = newSongs), uriIn == null)
                } catch (e: Exception) {
                    Log.e(TAG, "failed to set $rating on $mediaId", e)
                    error = e
                }
            }
            if (token == null && error == null) {
                completion.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } else {
                if (!supportsNotificationPermission() || hasNotificationPermission()) {
                    @SuppressLint("MissingPermission") // false positive
                    nm.notify(FAVE_ID, NotificationCompat.Builder(
                        this@IgpodPlaybackService, NOTIFY_CHANNEL_ID).apply {
                        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        setAutoCancel(true)
                        setCategory(NotificationCompat.CATEGORY_ERROR)
                        setSmallIcon(R.drawable.ic_error)
                        setContentTitle(this@IgpodPlaybackService.getString(R.string.favorite_failed_title))
                        setContentText(this@IgpodPlaybackService.getString(R.string.favorite_failed_text))
                        setContentIntent(
                            PendingIntent.getActivity(
                                this@IgpodPlaybackService,
                                PENDING_INTENT_FAVE_ID,
                                Intent(this@IgpodPlaybackService, MainActivity::class.java)
                                    .putExtra(MainActivity.FAVORITE_ENTRY, song)
                                    .putExtra(MainActivity.FAVORITE_STATE, rating.isHeart),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            )
                        )
                        setVibrate(longArrayOf(0L, 200L))
                        setLights(0, 0, 0)
                        setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                        setSound(null)
                    }.build())
                }
                completion.set(SessionResult(SessionError.ERROR_IO))
                return@launch
            }
        }
        return completion
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        rating: Rating
    ): ListenableFuture<SessionResult> {
        val mediaItemId =
            this.controller?.currentMediaItem?.mediaId ?: return Futures.immediateFuture(
                SessionResult(SessionError.ERROR_INVALID_STATE)
            )
        return onSetRating(session, controller, mediaItemId, rating)
    }

    // When destroying, we should release server side player
    // alongside with the mediaSession.
    override fun onDestroy() {
        Log.i(TAG, "+onDestroy()")
        unregisterReceiver(seekReceiver)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        // Important: this must happen before sending stop() as that changes state ENDED -> IDLE
        lastPlayedManager.save()
        scope.cancel()
        endedWorkaroundPlayer?.stop()
        handler.removeCallbacks(timer)
        controller?.release()
        controller = null
        mediaSession?.release()
        endedWorkaroundPlayer?.release()
        mediaSession = null
        broadcastAudioSessionClose()
        internalPlaybackThread.quitSafely()
        super.onDestroy()
        Log.i(TAG, "-onDestroy()")
    }

    // This onGetSession is a necessary method override needed by
    // MediaSessionService.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    // Configure commands available to the controller in onConnect()
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo)
            : MediaSession.ConnectionResult {
        Log.i(TAG, "onConnect(): $controller")
        val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        val availableSessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        if (session.isMediaNotificationController(controller)
            || session.isAutoCompanionController(controller)
            || session.isAutomotiveController(controller)
        ) {
            if (this.controller?.currentTimeline?.isEmpty == false) {
                builder.setMediaButtonPreferences(
                    ImmutableList.of(
                        getRepeatCommand(),
                        getShufflingCommand()
                    )
                )
            }
        }
        if (controller.connectionHints.getBoolean("PrepareWhenReady", false) &&
            endedWorkaroundPlayer?.currentTimeline?.isEmpty == false
        ) {
            handler.post { this.controller?.prepare() }
        }
        availableSessionCommands.add(SessionCommand(SERVICE_SET_TIMER, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QUERY_TIMER, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_SET_MEDIA_ITEMS_SEAMLESSLY, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_SET_MEDIA_ITEMS_ATOMIC, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QB_GET_INACTIVE_LIST, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QB_GET_QUEUE_FOR_UI, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QB_LOAD_QUEUE, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QB_DEL, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QB_REORDER, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QB_PIN_QUEUE, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QB_UNPIN_QUEUE, Bundle.EMPTY))
        return builder.setAvailableSessionCommands(availableSessionCommands.build()).build()
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        Log.i(TAG, "onPostConnect(): $controller")
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        Log.i(TAG, "onDisconnected(): $controller")
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        var restart = false
        if (restart) {
            controller?.stop()
            controller?.prepare()
        }
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (audioSessionId != lastSessionId) {
            broadcastAudioSessionClose()
            lastSessionId = audioSessionId
            broadcastAudioSession()
        }
    }

    private fun broadcastAudioSession() {
        if (lastSessionId != 0) {
            Log.i(TAG, "broadcast audio session open: $lastSessionId")
            sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, lastSessionId)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            })
        } else {
            Log.e(TAG, "session id is 0? why????? THIS MIGHT BREAK EQUALIZER")
        }
    }

    private fun broadcastAudioSessionClose() {
        if (lastSessionId != 0) {
            Log.i(TAG, "broadcast audio session close: $lastSessionId")
            sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, lastSessionId)
            })
            lastSessionId = 0
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction == SERVICE_SET_MEDIA_ITEMS_SEAMLESSLY
            || customCommand.customAction == SERVICE_SET_MEDIA_ITEMS_ATOMIC) {
            val songList = MediaItemList.getList(
                customCommand.customExtras.getBinder("items")!!)
            val position = customCommand.customExtras.getInt("position")
            val title = customCommand.customExtras.getString("title")!!
            val seamless = customCommand.customAction == SERVICE_SET_MEDIA_ITEMS_SEAMLESSLY
            val itemsFuture = Futures.transform(
                onAddMediaItems(session, controller, songList),
                { songList ->
                    if (seamless) {
                        endedWorkaroundPlayer!!.setMediaItemsSeamlessly(songList,
                            position, title, pinned = false, original = true,
                            repeatMode = null, shuffleModeEnabled = null, playbackParameters = null)
                    } else {
                        val shuffleModeEnabled = if (customCommand.customExtras.containsKey("shuffleEnabled"))
                            customCommand.customExtras.getBoolean("shuffleEnabled") else null
                        val repeatMode = if (customCommand.customExtras.containsKey("repeatMode"))
                            customCommand.customExtras.getInt("repeatMode") else null
                        endedWorkaroundPlayer!!.setMediaItems(songList, startIndex = position,
                            startPositionMs = C.TIME_UNSET, title, pinned = false, original = true,
                            newShuffleOrder = null, ended = false, repeatMode = repeatMode,
                            shuffleModeEnabled = shuffleModeEnabled, playbackParameters = null)
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS)
                },
                mainExecutor
            )
            return itemsFuture
        }
        return Futures.immediateFuture(
            when (customCommand.customAction) {
                SERVICE_SET_TIMER -> {
                    // 0 = clear timer; 0 with pauseOnEnd true will pause on end of current song
                    val duration = customCommand.customExtras.getInt("duration")
                    val pauseOnEnd = customCommand.customExtras.getBoolean("pauseOnEnd")
                    if (duration > 0) {
                        timerPauseOnEnd = pauseOnEnd
                        timerDuration = SystemClock.elapsedRealtime() + duration
                    } else {
                        val currentPauseOnEnd = this.endedWorkaroundPlayer!!.exoPlayer.pauseAtEndOfMediaItems
                        this.endedWorkaroundPlayer!!.exoPlayer.pauseAtEndOfMediaItems = pauseOnEnd
                        if (timerDuration != null) {
                            timerDuration = null
                        } else if (pauseOnEnd != currentPauseOnEnd) {
                            mediaSession!!.broadcastCustomCommand(
                                SessionCommand(SERVICE_TIMER_CHANGED, Bundle.EMPTY),
                                Bundle.EMPTY
                            )
                        }
                    }
                    if (duration > 0 || pauseOnEnd) {
                        prefs.edit {
                            putBoolean("lastTimerEos", pauseOnEnd)
                        }
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                SERVICE_QUERY_TIMER -> {
                    SessionResult(SessionResult.RESULT_SUCCESS).also {
                        timerDuration?.let { td ->
                            it.extras.putInt(
                                "duration",
                                (td - SystemClock.elapsedRealtime()).toInt()
                            )
                            it.extras.putBoolean("pauseOnEnd", timerPauseOnEnd)
                        } ?: it.extras.putBoolean(
                            "pauseOnEnd",
                            this.endedWorkaroundPlayer!!.exoPlayer.pauseAtEndOfMediaItems
                        )
                    }
                }

                SERVICE_QB_GET_INACTIVE_LIST -> {
                    SessionResult(SessionResult.RESULT_SUCCESS).also { res ->
                        val queueList: List<MultiQueueObject> = qb.getInactiveQueues()
                        val binder = MultiQueueList(queueList)
                        res.extras.putBinder("allQueues", binder)
                    }
                }

                SERVICE_QB_GET_QUEUE_FOR_UI -> {
                    SessionResult(SessionResult.RESULT_SUCCESS).also { res ->
                        val index = customCommand.customExtras.getInt("index")
                        val queueList: List<MultiQueueObject> = qb.getQueue(index)
                        val binder = MultiQueueList(queueList)
                        res.extras.putBinder("allQueues", binder)
                    }
                }

                SERVICE_QB_LOAD_QUEUE -> {
                    val index = customCommand.customExtras.getInt("index")
                    val startIndex = customCommand.customExtras.getInt("startIndex")
                    qb.commitQueue(index, startIndex)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                SERVICE_QB_PIN_QUEUE -> {
                    val index = customCommand.customExtras.getInt("index")
                    val status = if (index == -1) {
                        endedWorkaroundPlayer?.currentIsPinned = true
                        true
                    } else qb.pinQueue(index)
                    SessionResult(SessionResult.RESULT_SUCCESS).also { res ->
                        res.extras.putBoolean("status", status)
                    }
                }

                SERVICE_QB_UNPIN_QUEUE -> {
                    val index = customCommand.customExtras.getInt("index")
                    val expiry = qb.unpinQueue(index)
                    SessionResult(SessionResult.RESULT_SUCCESS).also { res ->
                        res.extras.putLong("expiry", expiry)
                    }
                }

                SERVICE_QB_DEL -> {
                    val index = customCommand.customExtras.getInt("index")
                    val status = qb.deleteQueue(index)
                    SessionResult(SessionResult.RESULT_SUCCESS).also { res ->
                        res.extras.putBoolean("status", status)
                        }
                }

                SERVICE_QB_AGE -> {
                    qb.age()
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                else -> {
                    SessionResult(SessionError.ERROR_BAD_VALUE)
                }
            })
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: @Player.PlayWhenReadyChangeReason Int
    ) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            this.endedWorkaroundPlayer?.exoPlayer?.pauseAtEndOfMediaItems = false
            mediaSession!!.broadcastCustomCommand(
                SessionCommand(SERVICE_TIMER_CHANGED, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaItemsWithStartPosition> {
        val settable = SettableFuture.create<MediaItemsWithStartPosition>()
        val job = scope.launch {
            lastPlayedManager.restore { items ->
                if (items == null) {
                    settable.setException(
                        NullPointerException(
                            "null MediaItemsWithStartPosition, see former logs for root cause"
                        ).also { Log.e(TAG, Log.getThrowableString(it)!!) }
                    )
                } else {
                    if (items.items.mediaItems.isNotEmpty()) {
                        var theItem = items.items.mediaItems[items.items.startIndex]
                        if (theItem.mediaMetadata.durationMs != null &&
                            theItem.mediaMetadata.durationMs!! > 0 &&
                            items.items.startPositionMs != C.TIME_UNSET
                        ) {
                            theItem = theItem.buildUpon()
                                .setMediaMetadata(
                                    theItem.mediaMetadata.buildUpon()
                                    .setExtras(Bundle(theItem.mediaMetadata.extras).apply {
                                        if (items.items.startPositionMs == 0L) {
                                            putInt(
                                                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                                                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
                                            )
                                        } else if (items.items.startPositionMs != theItem.mediaMetadata.durationMs!!) {
                                            putInt(
                                                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                                                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                                            )
                                            putDouble(
                                                MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                                                (items.items.startPositionMs.toDouble() /
                                                        theItem.mediaMetadata.durationMs!!)
                                                    .coerceIn(0.0, 1.0)
                                            )
                                        } else {
                                            putInt(
                                                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                                                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                                            )
                                        }
                                    }).build()
                                ).build()
                        }
                        settable.set(
                            MediaItemsWithStartPosition(
                                listOf(theItem),
                                0, items.items.startPositionMs
                            )
                        )
                    } else {
                        settable.set(items.items)
                    }
                }
            }
        }
        job.invokeOnCompletion { t ->
            if (t is CancellationException && !settable.isDone) {
                settable.setException(t)
            }
        }
        return settable
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle(getString(R.string.app_name))
                    .build()
            )
            .build()
        return Futures.immediateFuture(
            LibraryResult.ofItem(item, MediaLibraryService.LibraryParams.Builder().setOffline(true).build())
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val db = SyncDatabase.getInstance(applicationContext)
        val items = when (parentId) {
            ROOT_ID -> listOf(
                categoryItem(HOME_ID, getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, iconRes = com.igeeta.igpod.R.drawable.ic_home),
                categoryItem(ARTISTS_ID, getString(R.string.auto_artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, iconRes = com.igeeta.igpod.R.drawable.ic_person),
                categoryItem(ALBUMS_ID, getString(R.string.auto_albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, iconRes = com.igeeta.igpod.R.drawable.ic_album),
                categoryItem(RAAGAS_ID, "Raagas", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, iconRes = com.igeeta.igpod.R.drawable.ic_song_note),
            )
            HOME_ID -> {
                val recentlyPlayed = runBlocking { db.getRecentlyPlayedTracks(10) }
                val playlists = runBlocking { db.getAllPlaylists() }

                mutableListOf<MediaItem>().apply {
                    add(
                        MediaItem.Builder()
                            .setMediaId("radio:current")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                    .setTitle(PRAHARA_WINDOWS[RadioManager(this@IgpodPlaybackService).getCurrentPrahara()] ?: "Radio")
                                    .setArtist(getString(R.string.auto_radio))
                                    .setArtworkUri(Uri.parse("android.resource://${packageName}/${com.igeeta.igpod.R.drawable.ic_radio}"))
                                    .build()
                            )
                            .build()
                    )
                    if (recentlyPlayed.isNotEmpty()) {
                        add(categoryItem(RECENTLY_PLAYED_ID, "Recently Played", MediaMetadata.MEDIA_TYPE_PLAYLIST))
                    }
                    if (playlists.isNotEmpty()) {
                        add(categoryItem(PLAYLISTS_ID, getString(R.string.auto_playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS))
                    }
                }
            }
            RECENTLY_PLAYED_ID -> runBlocking { db.getRecentlyPlayedTracks(20).map { trackItem(it) } }
            ALBUMS_ID -> runBlocking {
                db.getAllTracks().map { it.album }.filter { it.isNotEmpty() }
                    .distinct().sorted().map { album ->
                        categoryItem(
                            "album:$album", album, MediaMetadata.MEDIA_TYPE_ALBUM,
                            db.getAllTracks().firstOrNull { it.album == album }
                                ?.let { artUriFor(it) }
                        )
                    }
            }
            ARTISTS_ID -> runBlocking {
                db.getAllTracks().flatMap { parseArtists(it.artists) }.filter { it.isNotEmpty() }
                    .distinct().sorted().map { artist ->
                        categoryItem("artist:$artist", artist, MediaMetadata.MEDIA_TYPE_ARTIST)
                    }
            }
            RAAGAS_ID -> runBlocking {
                db.getDistinctRaags().map { raaga ->
                    categoryItem("raaga:$raaga", raaga, MediaMetadata.MEDIA_TYPE_PLAYLIST)
                }
            }
            SONGS_ID -> runBlocking { db.getAllTracks().map { trackItem(it) } }
            PLAYLISTS_ID -> runBlocking {
                db.getAllPlaylists().map { pl ->
                    categoryItem("playlist:${pl.serverId}", pl.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
                }
            }
            else -> {
                // album:<name> / artist:<name> / playlist:<id> / prahara:<N>
                val colon = parentId.indexOf(':')
                if (colon < 0) emptyList()
                else {
                    val kind = parentId.substring(0, colon)
                    val value = parentId.substring(colon + 1)
                    when (kind) {
                        "prahara" -> {
                            val praharaNum = value.toIntOrNull() ?: 1
                            val radioManager = RadioManager(this@IgpodPlaybackService)
                            radioManager.getTracksForPrahara(praharaNum).map { trackItem(it) }
                        }
                        "raaga" -> runBlocking {
                            db.getTracksByRaag(value).map { trackItem(it, "raaga:$value") }
                        }
                        "album" -> runBlocking {
                            db.getAllTracks().filter { it.album == value }.map { trackItem(it, "album:$value") }
                        }
                        "artist" -> runBlocking {
                            db.getAllTracks().filter { parseArtists(it.artists).contains(value) }.map { trackItem(it, "artist:$value") }
                        }
                        "playlist" -> runBlocking {
                            db.getTracksByPlaylist(value.toIntOrNull() ?: -1).map { trackItem(it) }
                        }
                        else -> emptyList()
                    }
                }
            }
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(ArrayList(items), MediaLibraryService.LibraryParams.Builder().setOffline(true).build()))
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val db = SyncDatabase.getInstance(applicationContext)
        val hash = hashFromMediaId(mediaId)
        if (hash != null) {
            val track = runBlocking { db.getAllTracks().firstOrNull { it.filePath.hashCode().toLong() == hash } }
            if (track != null) {
                return Futures.immediateFuture(LibraryResult.ofItem(trackItem(track), null))
            }
        }
        return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
    }

    // Parses the trailing track hash from a mediaId:
    // "Db:<hash>", "album:<name>:<hash>", "artist:<name>:<hash>", "raaga:<name>:<hash>".
    private fun hashFromMediaId(mediaId: String): Long? {
        val segments = mediaId.split(":")
        if (segments.isEmpty()) return null
        if (segments[0] == "Db") return segments[1].toLongOrNull()
        if (segments[0] in setOf("album", "artist", "raaga") && segments.size >= 3) {
            return segments.last().toLongOrNull()
        }
        return null
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaItemsWithStartPosition> {
        if (mediaItems.size == 1) {
            val item = mediaItems[0]
            val expanded = maybeExpandSingleItem(item)
            if (expanded != null) {
                return Futures.immediateFuture(expanded)
            }
        }
        val resolved = resolveMediaItemsForPlayback(mediaItems)
        return Futures.immediateFuture(MediaItemsWithStartPosition(resolved, startIndex.coerceAtMost(resolved.size - 1).coerceAtLeast(0), startPositionMs))
    }

    private fun maybeExpandSingleItem(item: MediaItem): MediaItemsWithStartPosition? {
        val db = SyncDatabase.getInstance(applicationContext)
        val mediaId = item.mediaId

        if (mediaId == "radio:current") {
            val radioManager = RadioManager(this@IgpodPlaybackService)
            val currentPrahara = radioManager.getCurrentPrahara()
            val tracks = radioManager.getTracksForPrahara(currentPrahara)
            if (tracks.isNotEmpty()) {
                val items = tracks.map { trackItem(it) }
                return MediaItemsWithStartPosition(items, 0, 0)
            }
        }

        if (mediaId.startsWith("playlist:")) {
            val playlistId = mediaId.removePrefix("playlist:").toIntOrNull() ?: return null
            val tracks = runBlocking { db.getTracksByPlaylist(playlistId) }
            if (tracks.isNotEmpty()) {
                val items = tracks.map { trackItem(it) }
                return MediaItemsWithStartPosition(items, 0, 0)
            }
        }

        if (mediaId.startsWith("prahara:")) {
            val praharaNum = mediaId.removePrefix("prahara:").toIntOrNull() ?: return null
            val radioManager = RadioManager(this@IgpodPlaybackService)
            val tracks = radioManager.getTracksForPrahara(praharaNum)
            if (tracks.isNotEmpty()) {
                val items = tracks.map { trackItem(it) }
                return MediaItemsWithStartPosition(items, 0, 0)
            }
        }

        if (mediaId.startsWith("Db:") || mediaId.contains(":")) {
            val db = SyncDatabase.getInstance(applicationContext)
            // Context-tagged: "album:<name>:<hash>" / "artist:<name>:<hash>" /
            // "raaga:<name>:<hash>" — expand to the full browsed category.
            val segments = mediaId.split(":")
            if (segments.size >= 3 && segments[0] in setOf("album", "artist", "raaga")) {
                val kind = segments[0]
                val hash = segments.last().toLongOrNull() ?: return null
                val name = segments.subList(1, segments.size - 1).joinToString(":")
                val track = runBlocking { db.getAllTracks().firstOrNull { it.filePath.hashCode().toLong() == hash } }
                if (track != null) {
                    val tracks = when (kind) {
                        "album" -> runBlocking { db.getAllTracks().filter { it.album == name } }
                        "artist" -> runBlocking { db.getAllTracks().filter { parseArtists(it.artists).contains(name) } }
                        else -> runBlocking { db.getTracksByRaag(name) }
                    }
                    if (tracks.isNotEmpty()) {
                        val items = tracks.map { trackItem(it, "$kind:$name") }
                        val index = tracks.indexOfFirst { it.filePath == track.filePath }.coerceAtLeast(0)
                        return MediaItemsWithStartPosition(items, index, 0)
                    }
                }
                return null
            }

            val hash = if (mediaId.startsWith("Db:")) {
                mediaId.removePrefix("Db:").toLongOrNull()
            } else {
                null
            }
            if (hash == null) return null
            val track = runBlocking { db.getAllTracks().firstOrNull { it.filePath.hashCode().toLong() == hash } }
            if (track != null) {
                // Try playlist first
                if (track.playlistServerId != null) {
                    val tracks = runBlocking { db.getTracksByPlaylist(track.playlistServerId) }
                    if (tracks.isNotEmpty()) {
                        val items = tracks.map { trackItem(it) }
                        val indexInPlaylist = tracks.indexOfFirst { it.filePath == track.filePath }.coerceAtLeast(0)
                        return MediaItemsWithStartPosition(items, indexInPlaylist, 0)
                    }
                }
                // Try raaga
                if (!track.raag.isNullOrEmpty()) {
                    val tracks = runBlocking { db.getTracksByRaag(track.raag) }
                    if (tracks.isNotEmpty()) {
                        val items = tracks.map { trackItem(it) }
                        val indexInRaaga = tracks.indexOfFirst { it.filePath == track.filePath }.coerceAtLeast(0)
                        return MediaItemsWithStartPosition(items, indexInRaaga, 0)
                    }
                }
            }
        }

        return null
    }

    private fun resolveMediaItemsForPlayback(mediaItems: List<MediaItem>): List<MediaItem> {
        return mediaItems.map { item ->
            if (item.localConfiguration != null) {
                item
            } else if (item.mediaId != MediaItem.DEFAULT_MEDIA_ID && item.mediaId.isNotEmpty()) {
                val db = SyncDatabase.getInstance(applicationContext)
                val hash = hashFromMediaId(item.mediaId)
                if (hash != null) {
                    val track = runBlocking { db.getAllTracks().firstOrNull { it.filePath.hashCode().toLong() == hash } }
                    if (track != null) return@map trackItem(track)
                }
                item
            } else {
                item
            }
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        val db = SyncDatabase.getInstance(applicationContext)
        val results = searchSyncDatabase(db, query)
        session.notifySearchResultChanged(browser, query, results.size, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val db = SyncDatabase.getInstance(applicationContext)
        val results = searchSyncDatabase(db, query)
        return Futures.immediateFuture(LibraryResult.ofItemList(ArrayList(results), params))
    }

    private fun searchSyncDatabase(db: SyncDatabase, query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        return runBlocking {
            db.getAllTracks().filter { track ->
                track.title.lowercase().contains(q) ||
                    track.album.lowercase().contains(q) ||
                    parseArtists(track.artists).any { it.lowercase().contains(q) } ||
                    track.genre.lowercase().contains(q)
            }.map { trackItem(it) }
        }
    }

    private fun categoryItem(
        id: String,
        title: String,
        mediaType: Int,
        artwork: Uri? = null,
        @androidx.annotation.DrawableRes iconRes: Int = 0,
        playable: Boolean = false
    ): MediaItem {
        val iconUri = if (iconRes != 0) {
            Uri.parse("android.resource://${packageName}/${iconRes}")
        } else artwork
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(playable)
                    .setMediaType(mediaType)
                    .setTitle(title)
                    .apply { if (iconUri != null) setArtworkUri(iconUri) }
                    .build()
            )
            .build()
    }

    // Resolves a file path to a MediaStore content:// URI so Android Auto
    // (running in a separate process) can open the audio file. Falls back to
    // file:// if the track isn't indexed in MediaStore.
    private fun mediaStoreUriFor(track: SyncedTrack): Uri {
        val fullPath = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "iGeeta/${track.filePath}"
        ).absolutePath
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DATA} = ?",
            arrayOf(fullPath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return Uri.fromFile(File(fullPath))
    }

    // Builds a playable MediaItem for Android Auto from a SyncedTrack. Uses a
    // content:// artwork URI (served by IgpodAlbumArtProvider) so the head
    // unit can load art across process boundaries (app-private file:// won't work).
    // context: when the track is listed inside a browsed category (album/artist/
    // raaga), the mediaId carries that context so a tap can expand the whole
    // category into the queue. Format: "album:<name>:<hash>".
    private fun trackItem(track: SyncedTrack, context: String? = null): MediaItem {
        val id = track.filePath.hashCode().toLong()
        val mediaId = context?.let { "$it:$id" } ?: "Db:$id"
        val artists = parseArtists(track.artists).firstOrNull()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(mediaStoreUriFor(track))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setDurationMs((track.duration * 1000).toLong())
                    .setTitle(track.title.ifEmpty { track.filePath.substringAfterLast('/') })
                    .setArtist(artists)
                    .setAlbumTitle(track.album.ifEmpty { null })
                    .setArtworkUri(artUriFor(track))
                    .setGenre(track.genre.ifEmpty { null })
                    .build()
            )
            .build()
    }

    private fun artUriFor(track: SyncedTrack): Uri {
        val id = track.filePath.hashCode().toLong()
        val fullPath = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "iGeeta/${track.filePath}"
        )
        // Mirror RadioManager.trackToMediaItem: prefer server-downloaded artwork
        // in the app-private artwork dir; fall back to the embedded-art content
        // provider URI only when no server art is available.
        val artworkBaseDir = File(applicationContext.filesDir, "artwork")
        val artworkRel = listOf(track.trackArtPath, track.artworkLocalPath)
            .firstOrNull { rel -> !rel.isNullOrEmpty() &&
                File(artworkBaseDir, rel).let { it.exists() && it.length() > 0L } }
        return if (artworkRel != null) {
            // Cross-process-safe: head unit can't read app-private file://,
            // so route server art through the exported album-art content provider.
            IgpodAlbumArtProvider.buildArtworkUri(artworkRel)
        } else {
            IgpodAlbumArtProvider.buildSongUri(id, fullPath)
        }
    }

    private fun parseArtists(json: String): List<String> = try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }

    override fun onTracksChanged(tracks: Tracks) {
        if (!tracks.isEmpty && !tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
            Log.e(TAG, "No audio track selected: $tracks")
            controller!!.stop()
        }

    }

    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData
    ) {
        if (eventTime.mediaPeriodId == null) { // https://github.com/androidx/media/issues/2812
            Log.e(TAG, "mediaPeriodId is NULL in onDownstreamFormatChanged()!!")
            return
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
    }

    override fun onPlaybackParametersChanged(
        eventTime: AnalyticsListener.EventTime,
        playbackParameters: PlaybackParameters
    ) {
    }

    override fun onPlayerError(error: PlaybackException) {
        // TODO
    }

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
        // Stock media3 shows the playback notification during playback by default;
        // the fork's setShowNotificationForEmptyPlayer tuning is unavailable here.
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            // TODO: re-enable this when https://github.com/androidx/media/issues/3248 is fixed
            //lyrics = null
            //scheduleSendingLyrics(true)
        }

        // reshuffle queue when shuffle AND repeat all are enabled
        val player = endedWorkaroundPlayer
        if (player != null && player.currentMediaItemIndex == player.exoPlayer.shuffleOrder.lastIndex &&
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            player.shuffleModeEnabled && player.repeatMode == Player.REPEAT_MODE_ALL
        ) {
            player.exoPlayer.setShuffleOrder(
                CircularShuffleOrder(
                    player,
                    player.exoPlayer.shuffleOrder.lastIndex,
                    player.exoPlayer.mediaItemCount,
                    Random.nextLong()
                )
            )
        }

        lastPlayedManager.save()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.getBooleanStrict("stopPlayingWhenDismissTask", false) &&
            true // AudioPreviewActivity removed
        ) {
            pauseAllPlayersAndStopSelf()
        } else {
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        lastPlayedManager.save()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        super<Player.Listener>.onEvents(player, events)
        // if timeline changed, shuffle order is handled elsewhere instead (cloneAndInsert called by
        // ExoPlayer for common case and nextShuffleOrder for resumption case)
        if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            && !events.contains(Player.EVENT_TIMELINE_CHANGED)
        ) {
            // when enabling shuffle, re-shuffle lists so that the first index is up to date
            Log.i(TAG, "re-shuffling playlist")
            endedWorkaroundPlayer?.let {
                it.exoPlayer.setShuffleOrder(
                    CircularShuffleOrder(
                        it,
                        it.exoPlayer.currentMediaItemIndex,
                        it.exoPlayer.mediaItemCount,
                        Random.nextLong()
                    )
                )
            }
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        val completion = SettableFuture.create<List<MediaItem>>()
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val result = mediaItems.flatMap {
                    if (it.localConfiguration != null)
                        listOf(it)
                    else if (it.mediaId != MediaItem.DEFAULT_MEDIA_ID)
                        gramophoneApplication.reader.songListFlow.first()
                            .filter { m -> m.mediaId == it.mediaId }
                    else if (it.requestMetadata.searchQuery != null)
                        searchForMediaItem(it)
                    else
                        throw UnsupportedOperationException("can't do anything with $it")
                }
                completion.set(mapMediaItemsForFavorites(result))
            } catch (e: UnsupportedOperationException) {
                completion.setException(e)
            }
        }
        return completion
    }

    private suspend fun mapMediaItemsForFavorites(mediaItems: List<MediaItem>): List<MediaItem> {
        val favorites = gramophoneApplication.reader.playlistListFlow.map { it.find { p ->
            p is Favorite } }.first()?.songList?.map { it.mediaId } ?: emptyList()
        return mediaItems.map { item ->
            val isHeart = (item.mediaMetadata.userRating as? HeartRating)
                ?.isHeart == true
            val shouldBeHeart = favorites.contains(item.mediaId)
            if (isHeart != shouldBeHeart ||
                item.mediaMetadata.userRating !is HeartRating) {
                item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setUserRating(
                    HeartRating(shouldBeHeart)).build()).build()
            } else item
        }
    }

    private suspend fun searchForMediaItem(item: MediaItem): List<MediaItem> {
        val text = item.requestMetadata.searchQuery?.trim() ?: ""
        val list = gramophoneApplication.reader.songListFlow.first()
        // TODO support focus and sub queries (see MainActivity)
        return if (text == "") list else list.filter {
            // TODO sort results by match quality? (using raw=natural order)
            // TODO this is copied directly from SearchFragment, which should probably call into
            //  here for its search needs instead in the future
            val isMatchingTitle =
                it.mediaMetadata.title?.contains(text, true) == true
            val isMatchingAlbum =
                it.mediaMetadata.albumTitle?.contains(text, true) == true
            val isMatchingArtist =
                it.mediaMetadata.artist?.contains(text, true) == true
            isMatchingTitle || isMatchingAlbum || isMatchingArtist
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        refreshMediaButtonCustomLayout()
        if (needsMissingOnDestroyCallWorkarounds()) {
            handler.post { lastPlayedManager.save() }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        refreshMediaButtonCustomLayout()
        if (needsMissingOnDestroyCallWorkarounds()) {
            handler.post { lastPlayedManager.save() }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: @Player.TimelineChangeReason Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            lastPlayedManager.allowSavingState = true
            lastPlaylistLoaded.complete(Unit)
            refreshMediaButtonCustomLayout()
        }
    }

    private fun refreshMediaButtonCustomLayout() {
        val session = mediaSession ?: return
        val ctrl = controller ?: return
        val isEmpty = ctrl.currentTimeline.isEmpty
        try {
            session.connectedControllers.forEach {
                if (session.isMediaNotificationController(it)
                    || session.isAutoCompanionController(it)
                    || session.isAutomotiveController(it)
                ) {
                    session.setMediaButtonPreferences(
                        it, if (isEmpty) emptyList() else
                            ImmutableList.of(getRepeatCommand(), getShufflingCommand())
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshMediaButtonCustomLayout failed: ${Log.getThrowableString(e)}")
        }
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
    }

    override fun onForegroundServiceStartNotAllowedException() {
        Log.w(TAG, "Failed to resume playback :/")
        if (mayThrowForegroundServiceStartNotAllowed()
            || mayThrowForegroundServiceStartNotAllowedMiui()
        ) {
            if (supportsNotificationPermission() && !hasNotificationPermission()) {
                Log.e(
                    TAG, Log.getThrowableString(
                        IllegalStateException(
                            "onForegroundServiceStartNotAllowedException shouldn't be called on T+"
                        )
                    )!!
                )
                return
            }
            @SuppressLint("MissingPermission") // false positive
            nm.notify(NOTIFY_ID, NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID).apply {
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setAutoCancel(true)
                setCategory(NotificationCompat.CATEGORY_ERROR)
                setSmallIcon(R.drawable.ic_error)
                setContentTitle(this@IgpodPlaybackService.getString(R.string.fgs_failed_title))
                setContentText(this@IgpodPlaybackService.getString(R.string.fgs_failed_text))
                setContentIntent(
                    PendingIntent.getActivity(
                        this@IgpodPlaybackService,
                        PENDING_INTENT_NOTIFY_ID,
                        Intent(this@IgpodPlaybackService, MainActivity::class.java)
                            .putExtra(MainActivity.PLAYBACK_AUTO_START_FOR_FGS, true),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                setVibrate(longArrayOf(0L, 200L))
                setLights(0, 0, 0)
                setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                setSound(null)
            }.build())
        } else {
            handler.post {
                throw IllegalStateException("onForegroundServiceStartNotAllowedException shouldn't be called on T+")
            }
        }
    }
}
