package com.igeeta.igpod.ui.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ArrayAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.igeeta.igpod.R
import com.igeeta.igpod.logic.RadioManager
import com.igeeta.igpod.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RadioFragment : BaseFragment(false) {

    private lateinit var radioManager: RadioManager
    private var isRadioPlaying = false
    private val scope = CoroutineScope(Dispatchers.Main)

    // Views
    private lateinit var channelSpinner: TextInputEditText
    private lateinit var praharaCard: MaterialCardView
    private lateinit var praharaNumber: TextView
    private lateinit var praharaWindow: TextView
    private lateinit var praharaRagasLabel: TextView
    private lateinit var praharaRagas: TextView
    private lateinit var filterCard: MaterialCardView
    private lateinit var selectFiltersButton: MaterialButton
    private lateinit var filterChips: ChipGroup
    private lateinit var nowPlayingCard: MaterialCardView
    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingArtist: TextView
    private lateinit var nowPlayingRaaga: TextView
    private lateinit var radioToggle: MaterialButton

    // Cached filter options for the dialog
    private var currentFilterOptions: List<String> = emptyList()
    private var currentFilterType: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_radio, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = requireActivity() as MainActivity
        radioManager = mainActivity.radioManager

        initViews(view)
        setupChannelSpinner()
        setupFilterButton()
        setupToggleButton()
        updatePraharaInfo()
        updateNowPlaying()
    }

    private fun initViews(view: View) {
        channelSpinner = view.findViewById(R.id.channel_spinner)
        praharaCard = view.findViewById(R.id.prahara_card)
        praharaNumber = view.findViewById(R.id.prahara_number)
        praharaWindow = view.findViewById(R.id.prahara_window)
        praharaRagasLabel = view.findViewById(R.id.prahara_ragas_label)
        praharaRagas = view.findViewById(R.id.prahara_ragas)
        filterCard = view.findViewById(R.id.filter_card)
        selectFiltersButton = view.findViewById(R.id.select_filters_button)
        filterChips = view.findViewById(R.id.filter_chips)
        nowPlayingCard = view.findViewById(R.id.now_playing_card)
        nowPlayingTitle = view.findViewById(R.id.now_playing_title)
        nowPlayingArtist = view.findViewById(R.id.now_playing_artist)
        nowPlayingRaaga = view.findViewById(R.id.now_playing_raaga)
        radioToggle = view.findViewById(R.id.radio_toggle)
    }

    private fun setupChannelSpinner() {
        val modes = listOf("Prahara", "Artist", "Instrument", "Raaga")
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            modes
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        channelSpinner.setOnClickListener {
            val currentMode = radioManager.currentMode
            val currentIndex = when (currentMode) {
                RadioManager.RadioMode.PRAHARA -> 0
                RadioManager.RadioMode.ARTIST -> 1
                RadioManager.RadioMode.INSTRUMENT -> 2
                RadioManager.RadioMode.RAAGA -> 3
            }

            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle("Select Radio Mode")
            builder.setSingleChoiceItems(modes.toTypedArray(), currentIndex) { dialog, which ->
                val mode = when (which) {
                    0 -> RadioManager.RadioMode.PRAHARA
                    1 -> RadioManager.RadioMode.ARTIST
                    2 -> RadioManager.RadioMode.INSTRUMENT
                    3 -> RadioManager.RadioMode.RAAGA
                    else -> RadioManager.RadioMode.PRAHARA
                }
                channelSpinner.setText(modes[which])
                radioManager.setChannel(mode)
                updateUIForMode(mode)
                dialog.dismiss()
            }
            builder.show()
        }

        // Set initial value
        channelSpinner.setText("Prahara")
    }

    private fun updateUIForMode(mode: RadioManager.RadioMode) {
        when (mode) {
            RadioManager.RadioMode.PRAHARA -> {
                praharaCard.visibility = View.VISIBLE
                filterCard.visibility = View.GONE
                updatePraharaInfo()
            }
            RadioManager.RadioMode.ARTIST -> {
                praharaCard.visibility = View.GONE
                filterCard.visibility = View.VISIBLE
                currentFilterType = "artist"
                selectFiltersButton.text = "Select Artists"
                loadFilterOptions("artist")
            }
            RadioManager.RadioMode.INSTRUMENT -> {
                praharaCard.visibility = View.GONE
                filterCard.visibility = View.VISIBLE
                currentFilterType = "instrument"
                selectFiltersButton.text = "Select Instruments"
                loadFilterOptions("instrument")
            }
            RadioManager.RadioMode.RAAGA -> {
                praharaCard.visibility = View.GONE
                filterCard.visibility = View.VISIBLE
                currentFilterType = "raaga"
                selectFiltersButton.text = "Select Raagas"
                loadFilterOptions("raaga")
            }
        }
    }

    private fun updatePraharaInfo() {
        val prahara = radioManager.getCurrentPrahara()
        val window = radioManager.getPraharaWindow(prahara)
        praharaNumber.text = "Prahara $prahara"
        praharaWindow.text = window

        scope.launch {
            val ragas = withContext(Dispatchers.IO) {
                radioManager.getRagasForCurrentTime()
            }
            if (ragas.isNotEmpty()) {
                praharaRagasLabel.visibility = View.VISIBLE
                praharaRagas.visibility = View.VISIBLE
                praharaRagas.text = ragas.joinToString(", ") { it.name }
            } else {
                praharaRagasLabel.visibility = View.GONE
                praharaRagas.visibility = View.GONE
            }
        }
    }

    private fun loadFilterOptions(type: String) {
        scope.launch {
            val options = withContext(Dispatchers.IO) {
                when (type) {
                    "artist" -> radioManager.getDistinctArtists()
                    "instrument" -> radioManager.getDistinctInstruments()
                    "raaga" -> radioManager.getDistinctRaags()
                    else -> emptyList()
                }
            }
            currentFilterOptions = options
            populateSelectedChips()
        }
    }

    private fun populateSelectedChips() {
        filterChips.removeAllViews()
        val selected = radioManager.currentFilter
        for (item in selected) {
            val chip = Chip(requireContext()).apply {
                text = item
                isCheckable = false
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    val newFilter = radioManager.currentFilter - item
                    radioManager.setChannel(radioManager.currentMode, newFilter)
                    populateSelectedChips()
                }
            }
            filterChips.addView(chip)
        }
    }

    private fun setupFilterButton() {
        selectFiltersButton.setOnClickListener {
            if (currentFilterOptions.isEmpty()) {
                return@setOnClickListener
            }

            val selectedSet = radioManager.currentFilter.toSet()
            val checkedItems = currentFilterOptions.map { it in selectedSet }.toBooleanArray()

            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle(selectFiltersButton.text)
            builder.setMultiChoiceItems(currentFilterOptions.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            builder.setPositiveButton("OK") { _, _ ->
                val newFilter = currentFilterOptions.mapIndexedNotNull { index, name ->
                    if (checkedItems[index]) name else null
                }
                radioManager.setChannel(radioManager.currentMode, newFilter)
                populateSelectedChips()
            }
            builder.setNegativeButton("Cancel", null)
            builder.show()
        }
    }

    private fun setupToggleButton() {
        radioToggle.setOnClickListener {
            if (isRadioPlaying) {
                stopRadio()
            } else {
                startRadio()
            }
        }
    }

    private var playerListener: androidx.media3.common.Player.Listener? = null
    // True when a media-item transition was initiated by the radio itself
    // (start or auto-advance). Used to distinguish radio-driven transitions
    // from the user starting a library song, which must stop the radio.
    private var expectingRadioTransition = false

    private fun startRadio() {
        val controller = (requireActivity() as MainActivity).getPlayer()
        if (controller == null) {
            return
        }

        // Remove any existing listener
        playerListener?.let { controller.removeListener(it) }

        // Add listener for track transitions
        playerListener = object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (!isRadioPlaying) return
                if (expectingRadioTransition) {
                    // This transition was initiated by the radio itself; do not stop.
                    expectingRadioTransition = false
                    return
                }
                // A transition we did not initiate (e.g. user started a library song)
                // means the radio should stop.
                stopRadio()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isRadioPlaying && playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    playNextTrack()
                }
            }
        }
        controller.addListener(playerListener!!)

        scope.launch {
            val track = withContext(Dispatchers.IO) {
                radioManager.getNextTrack()
            }

            if (track != null) {
                // Fetch a second track to pre-load
                val nextTrack = withContext(Dispatchers.IO) {
                    radioManager.getNextTrack()
                }

                val tracks = if (nextTrack != null) listOf(track, nextTrack) else listOf(track)
                expectingRadioTransition = true
                controller.setMediaItems(tracks)
                controller.prepare()
                controller.play()
                isRadioPlaying = true
                radioToggle.text = "Stop Radio"
                updateNowPlaying()
            }
        }
    }

    private fun playNextTrack() {
        if (!isRadioPlaying) return

        val controller = (requireActivity() as MainActivity).getPlayer() ?: return

        scope.launch {
            val track = withContext(Dispatchers.IO) {
                radioManager.getNextTrack()
            }

            if (track != null) {
                val currentIndex = controller.currentMediaItemIndex
                if (controller.mediaItemCount > currentIndex + 1) {
                    controller.removeMediaItem(currentIndex + 1)
                }
                controller.addMediaItem(currentIndex + 1, track)
                expectingRadioTransition = true
                controller.seekToNext()
                updateNowPlaying()
            }
        }
    }

    private fun stopRadio() {
        val controller = (requireActivity() as MainActivity).getPlayer() ?: return
        playerListener?.let { controller.removeListener(it) }
        playerListener = null
        controller.stop()
        isRadioPlaying = false
        radioToggle.text = "Start Radio"
        updateNowPlaying()
    }

    private fun updateNowPlaying() {
        val controller = (requireActivity() as MainActivity).getPlayer()
        val currentMediaItem = controller?.currentMediaItem

        if (currentMediaItem != null && isRadioPlaying) {
            nowPlayingCard.visibility = View.VISIBLE
            nowPlayingTitle.text = currentMediaItem.mediaMetadata.title ?: "Unknown"
            nowPlayingArtist.text = currentMediaItem.mediaMetadata.artist ?: "Unknown artist"
            nowPlayingRaaga.text = "Radio - ${radioManager.currentMode.name}"
        } else {
            nowPlayingCard.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateNowPlaying()
    }
}