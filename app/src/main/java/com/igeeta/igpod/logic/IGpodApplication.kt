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
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composer
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.core.content.edit
import androidx.fragment.app.strictmode.FragmentStrictMode
import androidx.media3.common.util.Log
import androidx.media3.session.DefaultMediaNotificationProvider
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.map.AndroidUriMapper
import coil3.request.NullRequestDataException
import coil3.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.igeeta.igpod.BuildConfig
import com.igeeta.igpod.R
import com.igeeta.igpod.logic.ui.BugHandlerActivity
import com.igeeta.igpod.logic.utils.CoilArtPipeline
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.lsposed.hiddenapibypass.LSPass
import uk.akane.libphonograph.reader.FlowReader
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

class IGpodApplication : Application(), SingletonImageLoader.Factory,
    Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "GramophoneApplication"
    }

    init {
        @SuppressLint("DefaultUncaughtExceptionDelegation")
        Thread.setDefaultUncaughtExceptionHandler(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.MODEL != "robolectric") {
            HiddenApiBypass.setHiddenApiExemptions("")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LSPass.setHiddenApiExemptions("")
        }
        if (BuildConfig.DEBUG) {
            System.setProperty("kotlinx.coroutines.debug", "on")
            @OptIn(ExperimentalComposeRuntimeApi::class)
            Composer.setDiagnosticStackTraceEnabled(true)
        }
    }

    // iGpod: no minimum song-length filter (always 0).
    val minSongLengthSecondsFlow = MutableStateFlow(0L)
    val recentlyAddedFilterSecondFlow = MutableStateFlow(1_209_600L)
    lateinit var reader: FlowReader
        private set

    override fun onCreate() {
        super.onCreate()
        // disk read and write on first launch, but unavoidable as threads would race setDefaultNightMode
        if (BuildConfig.DEBUG && !isColorOS()) {
            // Use StrictMode to find antipattern issues
            StrictMode.setThreadPolicy(
                ThreadPolicy.Builder()
                    .detectAll()
                    .let {
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                            Build.VERSION.SDK_INT == Build.VERSION_CODES.VANILLA_ICE_CREAM
                        ) {
                            it.permitExplicitGc() // platform bug, now fixed
                        } else it
                    }
                    .let {
                        if (Debug.isDebuggerConnected() || isAlpsBoostFwkPresent())
                            it.permitDiskReads()
                        else it
                    }
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectAll()
                    // detectAll does in fact not detect everything :)
                    .let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            it.detectImplicitDirectBoot()
                        } else it
                    }
                    .penaltyLog()
                    .let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            it.penaltyDeathOnFileUriExposure()
                        } else it
                    }
                    .build()
            )
            FragmentStrictMode.defaultPolicy = FragmentStrictMode.Policy.Builder()
                .detectFragmentReuse()
                .detectFragmentTagUsage()
                .detectRetainInstanceUsage()
                .detectSetUserVisibleHint()
                //.detectTargetFragmentUsage() TODO onDisplayPreferenceDialog()
                .detectWrongFragmentContainer()
                .detectWrongNestedHierarchy()
                .penaltyDeath()
                .build()
        }
        android.util.Log.d(TAG, "GramophoneApplication.onCreate()")
        org.nift4.mediastorecompat.Log.setLogger(object : org.nift4.mediastorecompat.Log.Logger {
            override fun d(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                Log.d(tag, message, throwable)
            }

            override fun i(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                Log.i(tag, message, throwable)
            }

            override fun w(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                Log.w(tag, message, throwable)
            }

            override fun e(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                Log.e(tag, message, throwable)
            }
        })
        if (!android.util.Log.isLoggable(TAG, android.util.Log.INFO)) {
            Log.setLogger(object : Log.Logger {
                override fun d(
                    tag: String,
                    message: String,
                    throwable: Throwable?
                ) {
                    android.util.Log.e(tag, "[DEBUG] $message", throwable)
                }

                override fun i(
                    tag: String,
                    message: String,
                    throwable: Throwable?
                ) {
                    android.util.Log.e(tag, "[INFO] $message", throwable)
                }

                override fun w(
                    tag: String,
                    message: String,
                    throwable: Throwable?
                ) {
                    android.util.Log.e(tag, "[WARN] $message", throwable)
                }

                override fun e(
                    tag: String,
                    message: String,
                    throwable: Throwable?
                ) {
                    android.util.Log.e(tag, "[ERROR] $message", throwable)
                }
            })
        }

        // iGeeta: always read the library from the local music database at a fixed path.
        // We only play mp3 from this directory, so there is no MediaStore filtering,
        // blacklist, whitelist or server-host scoping needed.
        val syncDb = com.igeeta.igpod.sync.SyncDatabase.getInstance(this)
        val musicDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MUSIC
        )
        val dbRootPath = File(musicDir, "iGeeta").absolutePath
        android.util.Log.i(TAG, "Database mode: reading from $dbRootPath")

        reader = FlowReader(
            this,
            if (BuildConfig.DISABLE_MEDIA_STORE_FILTER) MutableStateFlow(0) else
                minSongLengthSecondsFlow,
            MutableStateFlow(emptySet()),
            MutableStateFlow(emptySet()),
            MutableStateFlow(null), // iGpod: no enhanced album-cover reading
            recentlyAddedFilterSecondFlow,
            useDatabase = true,
            syncDb = syncDb,
            dbRootPath = dbRootPath
        )
        // iGpod: always follow the system theme (no user theme toggle).
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        // This is a separate thread to avoid disk read on main thread and improve startup time
        CoroutineScope(Dispatchers.Default).launch {
            // https://github.com/androidx/media/issues/805
            if (needsMissingOnDestroyCallWorkarounds()) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
            }

            delay(10000.milliseconds) // Wait until we are idle with useless IO
            withContext(Dispatchers.IO) {
                // Clean up old logs
                val selfLogDir = File(cacheDir, "SelfLog")
                selfLogDir.listFiles()?.forEach(File::delete)
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .diskCache(null)
            .components {
                add(CoilArtPipeline.ThumbnailKeyer())
                add(CoilArtPipeline.AlbumThumbnailKeyer())
                add(CoilArtPipeline.AudioCoverKeyer())
                add(AndroidUriMapper())
                add(CoilArtPipeline.ThumbnailMapper())
                add(CoilArtPipeline.AudioCoverMapper())
                add(CoilArtPipeline.AlbumThumbnailMapper())
                add(CoilArtPipeline.ThumbnailFetcherFactory())
                add(CoilArtPipeline.AlbumThumbnailFetcherFactory())
                add(CoilArtPipeline.SongCoverFetcherFactory())
            }
            .run {
                if (!BuildConfig.DEBUG) this else
                    logger(object : Logger {
                        override var minLevel = Logger.Level.Verbose
                        override fun log(
                            tag: String,
                            level: Logger.Level,
                            message: String?,
                            throwable: Throwable?
                        ) {
                            if (level < minLevel) return
                            val println = { it: String ->
                                when (level) {
                                    Logger.Level.Verbose -> Log.d(tag, it)
                                    Logger.Level.Debug -> Log.d(tag, it)
                                    Logger.Level.Info -> Log.i(tag, it)
                                    Logger.Level.Warn -> Log.w(tag, it)
                                    Logger.Level.Error -> Log.e(tag, it)
                                }
                            }
                            if (message != null) {
                                println(message)
                            }
                            // Let's keep the log readable and ignore normal events' stack traces.
                            if (throwable != null && throwable !is NullRequestDataException
                                && throwable !is CoilArtPipeline.NoAlbumArtException
                                && (throwable !is IOException
                                        || throwable.message != "No album art found"
                                        && throwable.message != "No embedded album art found"
                                        && throwable.message != "No thumbnails in Downloads directories"
                                        && throwable.message != "No thumbnails in top-level directories")
                            ) {
                                println(Log.getThrowableString(throwable)!!)
                            }
                        }
                    })
            }
            .build()
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        // TODO convert to notification that opens BugHandlerActivity on click, and let JVM
        //  go through the normal exception process (to get stats from play). disadvantage: we can't
        //  cheat the statistic that way
        val exceptionMessage = Log.getThrowableString(e)
        val threadName = Thread.currentThread().name
        Log.e(TAG, "Error on thread $threadName:\n $exceptionMessage")
        val intent = Intent(this, BugHandlerActivity::class.java)
        intent.putExtra("exception_message", exceptionMessage)
        intent.putExtra("thread", threadName)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        exitProcess(10)
    }

    private fun isAlpsBoostFwkPresent(): Boolean {
        try {
            Class.forName("com.mediatek.boostfwk.BoostFwkManagerImpl")
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun isColorOS(): Boolean {
        val props = listOf(
            "ro.build.version.opporom",
            "ro.oplus.os.version"
        )
        return props.any {
            !getSystemProperty(it).isNullOrBlank()
        }
    }

    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String): String? {
        return try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java)
            get.invoke(null, key) as String
        } catch (e: Exception) {
            null
        }
    }
}
