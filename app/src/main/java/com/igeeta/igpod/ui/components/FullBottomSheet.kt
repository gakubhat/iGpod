/*
 *     Copyright (C) 2023 Akane Foundation
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

package com.igeeta.igpod.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.core.graphics.Insets
import androidx.core.graphics.TypefaceCompat
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.postOnAnimationDelayed
import androidx.core.widget.NestedScrollView
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModel
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.request.error
import coil3.size.Scale
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.igeeta.igpod.R
import com.igeeta.igpod.logic.IgpodPlaybackService
import com.igeeta.igpod.logic.dpToPx
import com.igeeta.igpod.logic.getBooleanStrict
import com.igeeta.igpod.logic.getIntStrict
import com.igeeta.igpod.logic.getTimer
import com.igeeta.igpod.logic.playOrPause
import com.igeeta.igpod.logic.setTextAnimation
import com.igeeta.igpod.logic.setTimer
import com.igeeta.igpod.logic.startAnimation
import com.igeeta.igpod.logic.updateMargin
import com.igeeta.igpod.logic.utils.CalculationUtils
import com.igeeta.igpod.logic.utils.ColorUtils
import com.igeeta.igpod.ui.MainActivity
import com.igeeta.igpod.ui.fragments.ArtistSubFragment
import com.igeeta.igpod.ui.fragments.DetailDialogFragment
import com.igeeta.igpod.ui.fragments.GeneralSubFragment
import uk.akane.libphonograph.items.albumId
import uk.akane.libphonograph.items.artistId
import uk.akane.libphonograph.manipulator.PlaylistSerializer.Entry
import kotlin.math.max
import kotlin.math.min

@SuppressLint("NotifyDataSetChanged")
class FullBottomSheet
    (context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
    ConstraintLayout(context, attrs, defStyleAttr, defStyleRes), Player.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener, MaterialButton.OnCheckedChangeListener {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            this(context, attrs, defStyleAttr, 0)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    private val activity
        get() = context as MainActivity
    private val instance: MediaBrowser?
        get() = activity.getPlayer()
    var minimize: (() -> Unit)? = null

    private val viewModel by activity.viewModels<MyViewModel>()
    private var isUserTracking = false
    private var runnableRunning = false
    private var firstTime = false

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    companion object {
        const val SLIDER_UPDATE_INTERVAL: Long = 100
        const val BACKGROUND_COLOR_TRANSITION_SEC: Long = 300
        const val FOREGROUND_COLOR_TRANSITION_SEC: Long = 150
        private const val TAG = "FullBottomSheet"
    }

    private val touchListener =
        object : SeekBar.OnSeekBarChangeListener, Slider.OnSliderTouchListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dest = instance?.mediaMetadata?.durationMs
                    if (dest != null) {
                        bottomSheetFullPosition.text =
                            CalculationUtils.convertDurationToTimeStamp((progress.toLong()))
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTracking = true
                progressDrawable.animate = false
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val mediaId = instance?.currentMediaItem
                if (mediaId != null) {
                    if (seekBar != null) {
                        instance?.seekTo((seekBar.progress.toLong()))
                    }
                }
                isUserTracking = false
                progressDrawable.animate =
                    instance?.isPlaying == true || instance?.playWhenReady == true
            }

            override fun onStartTrackingTouch(slider: Slider) {
                isUserTracking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                val mediaId = instance?.currentMediaItem
                if (mediaId != null) {
                    instance?.seekTo((slider.value.toLong()))
                }
                isUserTracking = false
            }
        }
    var visibilityDueToBottomSheet: Int = GONE
        set(value) {
            if (field != value) {
                field = value
                visibility = value
            }
        }
    private val bottomSheetFullCover: TransformableImageView
    private val bottomSheetFullTitle: TextView
    private val bottomSheetFullSubtitle: TextView
    private val bottomSheetFullControllerButton: MaterialButton
    private val bottomSheetFullNextButton: MaterialButton
    private val bottomSheetFullPreviousButton: MaterialButton
    private val bottomSheetFullDuration: TextView
    private val bottomSheetFullPosition: TextView
    private val bottomSheetFullSlideUpButton: MaterialButton
    private val bottomSheetShuffleButton: MaterialButton
    private val bottomSheetLoopButton: MaterialButton
    private val bottomSheetPlaylistButton: MaterialButton
    private val bottomSheetTimerButton: MaterialButton
    private val bottomSheetFavoriteButton: MaterialButton
    private val bottomSheetFullSeekBar: SeekBar
    private val bottomSheetFullSlider: Slider
    private val bottomSheetFullCoverFrame: MaterialCardView
    private val progressDrawable: SquigglyProgress
    private var pqs: PlaylistQueueSheet? = null

    init {
        inflate(context, R.layout.full_player, this)
        bottomSheetFullCoverFrame = findViewById(R.id.album_cover_frame)
        bottomSheetFullCover = findViewById(R.id.full_sheet_cover)
        bottomSheetFullTitle = findViewById(R.id.full_song_name)
        bottomSheetFullSubtitle = findViewById(R.id.full_song_artist)
        bottomSheetFullPreviousButton = findViewById(R.id.sheet_previous_song)
        bottomSheetFullControllerButton = findViewById(R.id.sheet_mid_button)
        bottomSheetFullNextButton = findViewById(R.id.sheet_next_song)
        bottomSheetFullPosition = findViewById(R.id.position)
        bottomSheetFullDuration = findViewById(R.id.duration)
        bottomSheetFullSeekBar = findViewById(R.id.slider_squiggly)
        bottomSheetFullSlider = findViewById(R.id.slider_vert)
        bottomSheetFullSlideUpButton = findViewById(R.id.slide_down)
        bottomSheetShuffleButton = findViewById(R.id.sheet_random)
        bottomSheetLoopButton = findViewById(R.id.sheet_loop)
        bottomSheetTimerButton = findViewById(R.id.timer)
        bottomSheetFavoriteButton = findViewById(R.id.favor)
        bottomSheetPlaylistButton = findViewById(R.id.playlist)
        refreshSettings(null)
        prefs.registerOnSharedPreferenceChangeListener(this)
        activity.controllerViewModel.customCommandListeners.addCallback(activity.lifecycle) { _, command, _ ->
            when (command.customAction) {
                IgpodPlaybackService.SERVICE_TIMER_CHANGED -> updateTimer()

                else -> {
                    return@addCallback Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
            }
            return@addCallback Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        val seekBarProgressWavelength =
            context.resources
                .getDimensionPixelSize(R.dimen.media_seekbar_progress_wavelength)
                .toFloat()
        val seekBarProgressAmplitude =
            context.resources
                .getDimensionPixelSize(R.dimen.media_seekbar_progress_amplitude)
                .toFloat()
        val seekBarProgressPhase =
            context.resources
                .getDimensionPixelSize(R.dimen.media_seekbar_progress_phase)
                .toFloat()
        val seekBarProgressStrokeWidth =
            context.resources
                .getDimensionPixelSize(R.dimen.media_seekbar_progress_stroke_width)
                .toFloat()

        bottomSheetFullSeekBar.progressDrawable = SquigglyProgress().also {
            progressDrawable = it
            it.waveLength = seekBarProgressWavelength
            it.lineAmplitude = seekBarProgressAmplitude
            it.phaseSpeed = seekBarProgressPhase
            it.strokeWidth = seekBarProgressStrokeWidth
            it.transitionEnabled = true
            it.animate = false
            it.setTint(
                MaterialColors.getColor(
                    bottomSheetFullSeekBar,
                    androidx.appcompat.R.attr.colorPrimary,
                )
            )
        }

        bottomSheetFullCover.setOnClickListener {
            activity.startFragment(DetailDialogFragment()) {
                putString("Id", instance?.currentMediaItem?.mediaId)
            }
        }

        bottomSheetFullTitle.setOnClickListener {
            minimize?.invoke()
            activity.startFragment(GeneralSubFragment()) {
                putString("Id", instance?.currentMediaItem?.mediaMetadata?.albumId?.toString())
                putInt("Item", R.id.album)
            }
        }


        bottomSheetFullSubtitle.setOnClickListener {
            minimize?.invoke()
            activity.startFragment(ArtistSubFragment()) {
                putString("Id", instance?.currentMediaItem?.mediaMetadata?.artistId?.toString())
                putInt("Item", R.id.artist)
            }
        }

        bottomSheetTimerButton.setOnClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
            val t = instance?.getTimer()
            val currentText = if (t?.first != null) context.getString(R.string.timer_expiry,
                DateFormat.getTimeFormat(context).format(System.currentTimeMillis() + t.first!!)
            ) else if (t?.second == true) context.getString(R.string.timer_expiry_end_of_this_song)
            else null
            if (currentText != null) {
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.timer)
                    .setView(R.layout.dialog_sleep_timer_active)
                    .setNeutralButton(R.string.unset)  { _, _ ->
                        instance?.setTimer(0, false)
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
                dialog.findViewById<TextView>(R.id.textView)!!.text = currentText
                dialog.findViewById<CheckBox>(R.id.checkBox)!!.let {
                    if (t!!.first == null) {
                        it.visibility = GONE
                    } else {
                        it.isChecked = t.second
                        it.setOnCheckedChangeListener { _, value ->
                            val newTime = instance?.getTimer()
                            instance?.setTimer(newTime?.first ?: 0, value)
                        }
                    }
                }
            } else {
                val minutes = listOf(0, 15, 30, 45, 60, 90)
                val items = minutes.map {
                    if (it > 0)
                        context.resources.getQuantityString(R.plurals.minutes, it, it)
                    else
                        context.resources.getString(R.string.timer_end_of_this_song)
                }
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.timer)
                    .setView(R.layout.dialog_sleep_timer)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .create()
                val spinner = dialog.findViewById<Spinner>(R.id.spinner)!!
                val checkbox = dialog.findViewById<CheckBox>(R.id.checkBox)!!
                checkbox.isChecked = prefs.getBooleanStrict("lastTimerEos", false)
                spinner.adapter = ArrayAdapter(
                    context, android.R.layout.simple_spinner_item, items
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                spinner.setSelection(0)
                dialog.setButton(AlertDialog.BUTTON_POSITIVE,
                    context.getText(android.R.string.ok)) { _, _ ->
                    val position = spinner.selectedItemPosition
                    val duration = minutes[position] * 60 * 1000
                    val eos = duration == 0 || checkbox.isChecked
                    prefs.edit().putBoolean("lastTimerEos", checkbox.isChecked).apply()
                    instance?.setTimer(duration, eos)
                }
                dialog.show()
            }
        }

        bottomSheetLoopButton.setOnClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
            instance?.repeatMode = when (instance?.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> throw IllegalStateException()
            }
        }

        bottomSheetFavoriteButton.addOnCheckedChangeListener(this)

        bottomSheetPlaylistButton.setOnClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
            if (instance != null)
                pqs = PlaylistQueueSheet(context, activity).also { it.show() }
        }
        bottomSheetFullControllerButton.setOnClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
            instance?.playOrPause()
        }
        bottomSheetFullPreviousButton.setOnClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
            instance?.seekToPrevious()
        }
        bottomSheetFullPreviousButton.setOnLongClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.LONG_PRESS)
            instance?.seekBack()
            true
        }
        bottomSheetFullNextButton.setOnClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
            instance?.seekToNext()
        }
        bottomSheetFullNextButton.setOnLongClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.LONG_PRESS)
            instance?.seekForward()
            true
        }
        bottomSheetShuffleButton.addOnCheckedChangeListener { _, isChecked ->
            instance?.shuffleModeEnabled = isChecked
        }

        bottomSheetFullSlider.addOnChangeListener { _, value, isUser ->
            if (isUser) {
                val dest = instance?.mediaMetadata?.durationMs
                if (dest != null) {
                    bottomSheetFullPosition.text =
                        CalculationUtils.convertDurationToTimeStamp((value).toLong())
                }
            }
        }

        bottomSheetFullSeekBar.setOnSeekBarChangeListener(touchListener)
        bottomSheetFullSlider.addOnSliderTouchListener(touchListener)

        bottomSheetFullSlideUpButton.setOnClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
            minimize?.invoke()
        }

        bottomSheetShuffleButton.setOnClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
        }

        val colorSecondaryContainer =
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorSecondaryContainer,
                -1
            )
        val colorSurface = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            -1
        )
        val backgroundProcessedColor = ColorUtils.getColor(
            colorSurface,
            ColorUtils.ColorType.COLOR_BACKGROUND_ELEVATED,
            context
        )
        val colorContrastFainted = ColorUtils.getColor(
            colorSecondaryContainer,
            ColorUtils.ColorType.COLOR_CONTRAST_FAINTED,
            context
        )
        setBackgroundColor(backgroundProcessedColor)
        bottomSheetFullSlider.trackInactiveTintList = ColorStateList.valueOf(colorContrastFainted)

        activity.controllerViewModel.addRecreationalPlayerListener(activity.lifecycle, this) {
            firstTime = true
            updateTimer()
            onRepeatModeChanged(instance?.repeatMode ?: Player.REPEAT_MODE_OFF)
            onShuffleModeEnabledChanged(instance?.shuffleModeEnabled == true)
            onPlaybackStateChanged(instance?.playbackState ?: Player.STATE_IDLE)
            onMediaItemTransition(
                instance?.currentMediaItem,
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            )
            onMediaMetadataChanged(instance?.mediaMetadata ?: MediaMetadata.EMPTY)
            firstTime = false
        }
    }

    private fun updateTimer() {
        val t = instance?.getTimer()
        bottomSheetTimerButton.isChecked = t?.first != null || t?.second == true
        TooltipCompat.setTooltipText(
            bottomSheetTimerButton,
            if (t?.first != null) context.getString(
                if (t.second) R.string.timer_expiry_eos else R.string.timer_expiry,
                DateFormat.getTimeFormat(context).format(System.currentTimeMillis() + t.first!!)
            ) else if (t?.second == true) context.getString(R.string.timer_expiry_end_of_this_song)
            else context.getString(R.string.timer)
        )
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        refreshSettings(key)
    }

    private fun refreshSettings(key: String?) {
        // iGpod: player UI appearance is frozen; no user customization.
        if (key == null || key == "default_progress_bar") {
            // default_progress_bar = false -> use the classic seek bar
            bottomSheetFullSlider.visibility = GONE
            bottomSheetFullSeekBar.visibility = VISIBLE
        }
        if (key == null || key == "centered_title") {
            // centered_title = false -> left aligned
            bottomSheetFullTitle.gravity = Gravity.CENTER_HORIZONTAL or Gravity.START
            bottomSheetFullSubtitle.gravity = Gravity.CENTER_HORIZONTAL or Gravity.START
        }
        if (key == null || key == "bold_title") {
            // bold_title = true
            bottomSheetFullTitle.typeface = TypefaceCompat.create(context, null, 600, false)
        }
        if (key == null || key == "album_round_corner") {
            bottomSheetFullCoverFrame.radius =
                context.resources.getInteger(R.integer.round_corner_radius)
                    .dpToPx(context).toFloat()
        }
        if (key == null || key == "cookie_cover") {
            // cookie_cover = false
            bottomSheetFullCover.setClip(false)
        }
    }

    fun onStop() {
        pqs?.dismiss()
        runnableRunning = false
    }

    override fun dispatchApplyWindowInsets(platformInsets: WindowInsets): WindowInsets {
        val insets = WindowInsetsCompat.toWindowInsetsCompat(platformInsets)
        val myInsets = insets.getInsets(
            WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
        )
        setPadding(myInsets.left, myInsets.top, myInsets.right, myInsets.bottom)
        return WindowInsetsCompat.Builder(insets)
            .setInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout(), Insets.NONE
            )
            .setInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout(), Insets.NONE
            )
            .build()
            .toWindowInsets()!!
    }


    @SuppressLint("NotifyDataSetChanged")
    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int
    ) {
        if (instance?.mediaItemCount != 0) {
            bottomSheetFullCover.dispose()
            bottomSheetFullCover.loadNoPlaceholder(mediaItem?.mediaMetadata?.artworkUri) {
                scale(Scale.FILL)
                error(R.drawable.ic_default_cover)
            }
            bottomSheetFullTitle.setTextAnimation(
                mediaItem?.mediaMetadata?.title ?: "",
                skipAnimation = firstTime
            )
            bottomSheetFullSubtitle.setTextAnimation(
                mediaItem?.mediaMetadata?.artist ?: context.getString(R.string.unknown_artist),
                skipAnimation = firstTime
            )
            updateDuration()
        } else {
            bottomSheetFullCover.dispose()
        }
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        val isHeart = (mediaMetadata.userRating as? HeartRating)?.isHeart == true
        if (bottomSheetFavoriteButton.isChecked != isHeart) {
            bottomSheetFavoriteButton.removeOnCheckedChangeListener(this)
            bottomSheetFavoriteButton.isChecked =
                (mediaMetadata.userRating as? HeartRating)?.isHeart == true
        }
        bottomSheetFavoriteButton.addOnCheckedChangeListener(this) // see onCheckedChanged
    }

    private fun updateDuration() {
        val duration = instance?.contentDuration?.let { if (it == C.TIME_UNSET) null else it }
            ?: instance?.currentMediaItem?.mediaMetadata?.durationMs
        if (duration != null && duration.toInt() != bottomSheetFullSeekBar.max) {
            bottomSheetFullDuration.setTextAnimation(
                CalculationUtils.convertDurationToTimeStamp(duration)
            )
            val position =
                CalculationUtils.convertDurationToTimeStamp(instance?.currentPosition ?: 0)
            if (!isUserTracking) {
                bottomSheetFullSeekBar.max = duration.toInt()
                bottomSheetFullSeekBar.progress = instance?.currentPosition?.toInt() ?: 0
                bottomSheetFullSlider.valueTo = duration.toFloat().coerceAtLeast(1f)
                bottomSheetFullSlider.value =
                    min(instance?.currentPosition?.toFloat() ?: 0f, bottomSheetFullSlider.valueTo)
                bottomSheetFullPosition.text = position
            }
        }
    }

    override fun onCheckedChanged(button: MaterialButton?, isChecked: Boolean) {
        instance?.currentMediaItem?.let { song ->
            val entry = Entry.ofMediaItem(song)
            if (entry != null)
                activity.markIsFavoriteStatus(listOf(entry), isChecked)
        }
        bottomSheetFavoriteButton.removeOnCheckedChangeListener(this) // see onMediaMetadataChanged
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        positionRunnable.run()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        bottomSheetShuffleButton.isChecked = shuffleModeEnabled
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        when (repeatMode) {
            Player.REPEAT_MODE_ALL -> {
                bottomSheetLoopButton.isChecked = true
                bottomSheetLoopButton.icon =
                    AppCompatResources.getDrawable(context, R.drawable.ic_repeat)
            }

            Player.REPEAT_MODE_ONE -> {
                bottomSheetLoopButton.isChecked = true
                bottomSheetLoopButton.icon =
                    AppCompatResources.getDrawable(context, R.drawable.ic_repeat_one)
            }

            Player.REPEAT_MODE_OFF -> {
                bottomSheetLoopButton.isChecked = false
                bottomSheetLoopButton.icon =
                    AppCompatResources.getDrawable(context, R.drawable.ic_repeat)
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        onPlaybackStateChanged(instance?.playbackState ?: Player.STATE_IDLE)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (instance?.isPlaying == true) {
            if (bottomSheetFullControllerButton.getTag(R.id.play_next) as Int? != 1) {
                bottomSheetFullControllerButton.icon =
                    AppCompatResources.getDrawable(
                        context,
                        R.drawable.play_anim
                    )
                bottomSheetFullControllerButton.background =
                    AppCompatResources.getDrawable(context, R.drawable.bg_play_anim)
                bottomSheetFullControllerButton.icon.startAnimation()
                bottomSheetFullControllerButton.background.startAnimation()
                bottomSheetFullControllerButton.setTag(R.id.play_next, 1)
            }
            if (!isUserTracking) {
                progressDrawable.animate = true
            }
            if (!runnableRunning) {
                runnableRunning = true
                handler.postDelayed(positionRunnable, SLIDER_UPDATE_INTERVAL)
            }
            bottomSheetFullCover.startRotation()
        } else if (playbackState != Player.STATE_BUFFERING) {
            if (bottomSheetFullControllerButton.getTag(R.id.play_next) as Int? != 2) {
                bottomSheetFullControllerButton.icon =
                    AppCompatResources.getDrawable(
                        context,
                        R.drawable.pause_anim
                    )
                bottomSheetFullControllerButton.background =
                    AppCompatResources.getDrawable(context, R.drawable.bg_pause_anim)
                bottomSheetFullControllerButton.icon.startAnimation()
                bottomSheetFullControllerButton.background.startAnimation()
                bottomSheetFullControllerButton.setTag(R.id.play_next, 2)
                bottomSheetFullCover.stopRotation()
            }
            if (!isUserTracking) {
                progressDrawable.animate = false
            }
        }
    }

    // https://developer.android.com/media/implement/surfaces/pause-and-resume-media-playback-with-spacebar
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        //androidx.media3.common.util.Log.e("hi","$keyCode") TODO this method is no-op, but why?
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                instance?.playOrPause(); true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                instance?.seekToPrevious(); true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                instance?.seekToNext(); true
            }

            else -> super.onKeyUp(keyCode, event)
        }
    }

    private val positionRunnable = object : Runnable {
        override fun run() {
            updateDuration() // TODO: figure out which callback this can be put in.
            val position =
                CalculationUtils.convertDurationToTimeStamp(instance?.currentPosition ?: 0)
            if (!isUserTracking) {
                bottomSheetFullSeekBar.progress = instance?.currentPosition?.toInt() ?: 0
                bottomSheetFullSlider.value =
                    min(instance?.currentPosition?.toFloat() ?: 0f, bottomSheetFullSlider.valueTo)
                bottomSheetFullPosition.text = position
            }
            if (instance?.isPlaying == true && runnableRunning) {
                handler.postDelayed(this, SLIDER_UPDATE_INTERVAL)
            } else {
                runnableRunning = false
            }
        }
    }

    class MyViewModel : ViewModel() {
    }

}
