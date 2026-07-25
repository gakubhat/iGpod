package com.igeeta.igpod.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.igeeta.igpod.R
import com.igeeta.igpod.ui.SyncActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service for syncing playlists from iGeeta server.
 * Shows progress notification and allows cancellation.
 */
class SyncService : Service() {

    companion object {
        const val ACTION_START_SYNC = "com.igeeta.igpod.action.START_SYNC"
        const val ACTION_CANCEL_SYNC = "com.igeeta.igpod.action.CANCEL_SYNC"
        const val EXTRA_PLAYLIST_IDS = "playlist_ids"

        const val BROADCAST_SYNC_PROGRESS = "com.igeeta.igpod.SYNC_PROGRESS"
        const val EXTRA_PROGRESS_TEXT = "progress_text"
        const val EXTRA_PROGRESS_PERCENT = "progress_percent"
        const val EXTRA_IS_SYNCING = "is_syncing"

        private const val CHANNEL_ID = "igeeta_sync"
        private const val NOTIFICATION_ID = 9001

        fun start(context: Context, playlistIds: List<Int>) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_START_SYNC
                putIntegerArrayListExtra(EXTRA_PLAYLIST_IDS, ArrayList(playlistIds))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_CANCEL_SYNC
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var syncJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var syncManager: SyncManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        syncManager = SyncManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                val playlistIds = intent.getIntegerArrayListExtra(EXTRA_PLAYLIST_IDS) ?: emptyList()
                startForeground(NOTIFICATION_ID, buildNotification("Starting sync...", 0))
                startSync(playlistIds)
            }
            ACTION_CANCEL_SYNC -> {
                syncJob?.cancel()
                broadcastProgress("Sync cancelled", 0, false)
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startSync(playlistIds: List<Int>) {
        syncJob = scope.launch {
            broadcastProgress("Starting sync...", 0, true)

            val success = syncManager.syncPlaylists(playlistIds) { progress ->
                val text = when (progress.phase) {
                    SyncPhase.CONNECTING -> "Connecting to server..."
                    SyncPhase.FETCHING_PLAYLISTS -> "Fetching playlists..."
                    SyncPhase.SYNCING_PLAYLISTS -> "Syncing playlists..."
                    SyncPhase.SYNCING_TRACKS -> {
                        if (progress.totalTracks > 0) {
                            "${progress.playlistName}: ${progress.currentTrack}/${progress.totalTracks} — ${progress.currentAction}"
                        } else {
                            "Syncing ${progress.playlistName}..."
                        }
                    }
                    SyncPhase.SYNCING_RATINGS -> progress.currentAction
                    SyncPhase.DONE -> "Sync complete!"
                    SyncPhase.ERROR -> "Error: ${progress.errorMessage}"
                }
                val progressPercent = if (progress.phase == SyncPhase.SYNCING_TRACKS) progress.percent else 0
                updateNotification(text, progressPercent)
                broadcastProgress(text, progressPercent, true)
            }

            if (success) {
                updateNotification("Sync complete!", 100)
                broadcastProgress("Sync complete!", 100, false)
            } else {
                broadcastProgress("Sync failed", 0, false)
            }

            // Small delay so user can see completion
            kotlinx.coroutines.delay(1500)
            stopForeground(true)
            stopSelf()
        }
    }

    private fun broadcastProgress(text: String, percent: Int, isSyncing: Boolean) {
        val intent = Intent(BROADCAST_SYNC_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS_TEXT, text)
            putExtra(EXTRA_PROGRESS_PERCENT, percent)
            putExtra(EXTRA_IS_SYNCING, isSyncing)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "iGeeta Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows sync progress"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SyncActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("iGeeta Sync")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .apply {
                if (progress > 0) {
                    setProgress(100, progress, false)
                }
            }
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val notification = buildNotification(text, progress)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
