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
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.addToCommandQueueThenFlush
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
import com.igeeta.igpod.logic.utils.exoplayer.GramophoneExtractorsFactory
import com.igeeta.igpod.logic.utils.exoplayer.GramophoneMediaSourceFactory
import com.igeeta.igpod.ui.MainActivity
import org.nift4.mediastorecompat.MediaStoreCompat
import uk.akane.libphonograph.dynamicitem.Favorite
import uk.akane.libphonograph.items.albumId
import uk.akane.libphonograph.manipulator.ItemManipulator
import uk.akane.libphonograph.manipulator.PlaylistSerializer
import uk.akane.libphonograph.manipulator.PlaylistSerializer.Entry
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.random.Random


/**
 * [GramophonePlaybackService] is a server service.
 * It's using exoplayer2 as its player backend.
 */
class GramophonePlaybackService : MediaLibraryService(), MediaSessionService.Listener,
    MediaLibraryService.MediaLibrarySession.Callback, Player.Listener, AnalyticsListener,
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

        }

    private var lastSessionId = 0
    private val internalPlaybackThread =
        HandlerThread("ExoPlayer:Playback", Process.THREAD_PRIORITY_AUDIO)
    private var mediaSession: MediaLibrarySession? = null
    val endedWorkaroundPlayer
        get() = mediaSession?.player as EndedWorkaroundPlayer?
    private var controller: MediaBrowser? = null
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
        setShowNotificationForEmptyPlayer(SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_AFTER_STOP_OR_ERROR)
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
                CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF) // shuffle currently disabled, click will enable
                    .setDisplayName(getString(R.string.shuffle))
                    .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, true)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON) // shuffle currently enabled, click will disable
                    .setDisplayName(getString(R.string.shuffle))
                    .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, false)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_OFF) // repeat currently disabled, click will repeat all
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ALL)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_ALL) // repeat all currently enabled, click will repeat one
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ONE)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_ONE) // repeat one currently enabled, click will disable
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
                GramophoneMediaSourceFactory(
                    DefaultDataSource.Factory(this),
                    GramophoneExtractorsFactory().also {
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

                    private val limit by lazy { MediaSession.getBitmapDimensionLimit(this@GramophonePlaybackService) }

                    // the suppression is correct, we want identity of the byte array as it will
                    // stay the same over one song's lifetime
                    @Suppress("KotlinArrayHashCode")
                    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
                        return CallbackToFutureAdapter.getFuture { completer ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(this@GramophonePlaybackService)
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
                                ImageRequest.Builder(this@GramophonePlaybackService)
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
                .setSystemUiPlaybackResumptionOptIn(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                .build()
        addSession(mediaSession!!)
        controller = MediaBrowser.Builder(this, mediaSession!!.token).buildAsync().get()
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
                MediaStoreCompat.needRequestBytesWrite(this@GramophonePlaybackService,
                    uriIn)
            } else {
                MediaStoreCompat.needRequestCreate(this@GramophonePlaybackService,
                    ItemManipulator.getDefaultPlaylistFile(
                        ItemManipulator.FAVORITES).path)
            }
            var error: Exception? = null
            if (token == null) {
                try {
                    val uri = uriIn ?: ItemManipulator.createPlaylist(
                        this@GramophonePlaybackService, ItemManipulator
                            .getDefaultPlaylistFile(ItemManipulator.FAVORITES))
                    val readback = if (uriIn != null) ItemManipulator.readbackPlaylist(
                        this@GramophonePlaybackService, uri) else
                            PlaylistSerializer.Playlist.create()
                    val newSongs = if (rating.isHeart) {
                        readback.entries + song
                    } else {
                        readback.entries.filter { !song.fuzzyEquals(it) }
                    }
                    ItemManipulator.setPlaylistContent(this@GramophonePlaybackService, uri,
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
                        this@GramophonePlaybackService, NOTIFY_CHANNEL_ID).apply {
                        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        setAutoCancel(true)
                        setCategory(NotificationCompat.CATEGORY_ERROR)
                        setSmallIcon(R.drawable.ic_error)
                        setContentTitle(this@GramophonePlaybackService.getString(R.string.favorite_failed_title))
                        setContentText(this@GramophonePlaybackService.getString(R.string.favorite_failed_text))
                        setContentIntent(
                            PendingIntent.getActivity(
                                this@GramophonePlaybackService,
                                PENDING_INTENT_FAVE_ID,
                                Intent(this@GramophonePlaybackService, MainActivity::class.java)
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
        endedWorkaroundPlayer!!.stop()
        handler.removeCallbacks(timer)
        mediaSession!!.setOptOutOfMediaButtonPlaybackResumption(controller!!.currentTimeline.isEmpty)
        controller!!.release()
        controller = null
        mediaSession!!.release()
        endedWorkaroundPlayer!!.release()
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
            // Ensure no further player commands (such as play) are executed until we're done.
            session.addToCommandQueueThenFlush(controller) { Futures.transform(itemsFuture,
                { null }, MoreExecutors.directExecutor()) }
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
        if (isForPlayback) {
            scope.launch {
                lastPlaylistLoaded.await()
                Util.handlePlayButtonAction(endedWorkaroundPlayer)
                settable.setException(MediaSession.ManuallyHandlePlaybackResumption())
            }
            return settable
        }
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

    /*override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val outParams = LibraryParams.Builder()
            .setOffline(true)
            .setSuggested(false)
            .setRecent(false)
            .build()
        val item = MediaItem.Builder()
            .setMediaId("root")
            .setMediaMetadata(MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build())
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(item, outParams))
    }*/

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
        if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            handler.postDelayed({
                setShowNotificationForEmptyPlayer(SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_NEVER)
            }, 2000) // TODO lol
        } else {
            setShowNotificationForEmptyPlayer(SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_AFTER_STOP_OR_ERROR)
        }
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
        val isEmpty = controller?.currentTimeline?.isEmpty != false
        mediaSession!!.connectedControllers.forEach {
            if (mediaSession!!.isMediaNotificationController(it)
                || mediaSession!!.isAutoCompanionController(it)
                || mediaSession!!.isAutomotiveController(it)
            ) {
                mediaSession!!.setMediaButtonPreferences(
                    it, if (isEmpty) emptyList() else
                        ImmutableList.of(getRepeatCommand(), getShufflingCommand())
                )
            }
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
                setContentTitle(this@GramophonePlaybackService.getString(R.string.fgs_failed_title))
                setContentText(this@GramophonePlaybackService.getString(R.string.fgs_failed_text))
                setContentIntent(
                    PendingIntent.getActivity(
                        this@GramophonePlaybackService,
                        PENDING_INTENT_NOTIFY_ID,
                        Intent(this@GramophonePlaybackService, MainActivity::class.java)
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
