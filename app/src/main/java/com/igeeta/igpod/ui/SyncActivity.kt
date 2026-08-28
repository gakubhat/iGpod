package com.igeeta.igpod.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.igeeta.igpod.R
import com.igeeta.igpod.logic.enableEdgeToEdgeProperly
import com.igeeta.igpod.sync.IgeetaApi
import com.igeeta.igpod.sync.SyncConfig
import com.igeeta.igpod.sync.SyncDatabase
import com.igeeta.igpod.sync.SyncService

class SyncActivity : AppCompatActivity() {

    private lateinit var connectionStatus: TextView
    private lateinit var statusDot: View
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var selectAllButton: MaterialButton
    private lateinit var syncButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var cleanButton: MaterialButton
    private lateinit var progressCard: MaterialCardView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var progressText: TextView
    private lateinit var lastSyncTime: TextView
    private lateinit var playlistsSynced: TextView
    private lateinit var serverConfigCard: MaterialCardView
    private lateinit var hostnameInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var portInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var saveConfigButton: MaterialButton
    private lateinit var editConfigButton: MaterialButton

    private val adapter = PlaylistSelectAdapter()
    private var serverPlaylists = listOf<IgeetaApi.ServerPlaylistInfo>()
    private var isSyncing = false

    private val cleanupDeleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> onMediaDeleteResolved(result.resultCode == Activity.RESULT_OK) }

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(SyncService.EXTRA_PROGRESS_TEXT) ?: ""
            val percent = intent.getIntExtra(SyncService.EXTRA_PROGRESS_PERCENT, 0)
            val syncing = intent.getBooleanExtra(SyncService.EXTRA_IS_SYNCING, false)

            runOnUiThread {
                isSyncing = syncing
                updateUIState()

                if (text.isNotEmpty()) {
                    progressText.text = text
                    if (percent > 0) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = percent
                    } else {
                        progressBar.isIndeterminate = true
                    }
                }

                if (!syncing) {
                    loadSyncHistory()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeProperly()
        setContentView(R.layout.activity_sync)

        // Setup toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Find views
        connectionStatus = findViewById(R.id.connectionStatus)
        statusDot = findViewById(R.id.statusDot)
        playlistRecyclerView = findViewById(R.id.playlistRecyclerView)
        selectAllButton = findViewById(R.id.selectAllButton)
        syncButton = findViewById(R.id.syncButton)
        cancelButton = findViewById(R.id.cancelButton)
        cleanButton = findViewById(R.id.cleanButton)
        progressCard = findViewById(R.id.progressCard)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        lastSyncTime = findViewById(R.id.lastSyncTime)
        playlistsSynced = findViewById(R.id.playlistsSynced)
        serverConfigCard = findViewById(R.id.serverConfigCard)
        hostnameInput = findViewById(R.id.hostnameInput)
        portInput = findViewById(R.id.portInput)
        saveConfigButton = findViewById(R.id.saveConfigButton)
        editConfigButton = findViewById(R.id.editConfigButton)

        // Setup RecyclerView
        playlistRecyclerView.layoutManager = LinearLayoutManager(this)
        playlistRecyclerView.adapter = adapter

        // Setup buttons
        selectAllButton.setOnClickListener {
            adapter.selectAll()
            updateSyncButton()
        }

        syncButton.setOnClickListener {
            startSync()
        }

        cancelButton.setOnClickListener {
            cancelSync()
        }

        cleanButton.setOnClickListener {
            cleanCollection()
        }

        saveConfigButton.setOnClickListener {
            saveConfig()
        }

        // Register broadcast receiver
        val filter = IntentFilter(SyncService.BROADCAST_SYNC_PROGRESS)
        ContextCompat.registerReceiver(this, syncReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Load data
        loadPlaylists()
        loadSyncHistory()
        updateUIState()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(syncReceiver)
    }

    override fun onResume() {
        super.onResume()
        loadSyncHistory()
    }

    private fun getConfig(): SyncConfig? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val host = prefs.getString("igeeta_host", "") ?: ""
        val portStr = prefs.getString("igeeta_port", "8000") ?: "8000"
        val port = portStr.toIntOrNull() ?: 8000
        val config = SyncConfig(host, port)
        return if (config.isValid) config else null
    }

    private fun loadPlaylists() {
        val config = getConfig()
        if (config == null) {
            connectionStatus.text = getString(R.string.igeeta_no_server)
            statusDot.setBackgroundResource(android.R.color.holo_red_light)
            serverConfigCard.visibility = View.VISIBLE
            editConfigButton.visibility = View.GONE
            return
        }

        // Pre-fill input fields
        hostnameInput.setText(config.host)
        portInput.setText(config.port.toString())
        serverConfigCard.visibility = View.GONE
        editConfigButton.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val api = IgeetaApi(config)
            try {
                val connected = api.testConnection()
                if (!connected) {
                    withContext(Dispatchers.Main) {
                        connectionStatus.text = getString(R.string.igeeta_test_connection_failed)
                        statusDot.setBackgroundResource(android.R.color.holo_red_light)
                    }
                    return@launch
                }

                val playlists = api.getPlaylists()
                serverPlaylists = playlists.map { IgeetaApi.ServerPlaylistInfo(it.id, it.name, it.description) }

                withContext(Dispatchers.Main) {
                    connectionStatus.text = "Connected to ${config.host}:${config.port}"
                    statusDot.setBackgroundResource(android.R.color.holo_green_light)
                    adapter.setPlaylists(serverPlaylists)
                    updateSyncButton()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectionStatus.text = "Error: ${e.message}"
                    statusDot.setBackgroundResource(android.R.color.holo_red_light)
                }
            } finally {
                api.close()
            }
        }
    }

    private fun saveConfig() {
        val host = hostnameInput.text.toString().trim()
        val portStr = portInput.text.toString().trim()
        val port = portStr.toIntOrNull() ?: 8000

        if (host.isEmpty()) {
            hostnameInput.error = "Required"
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putString("igeeta_host", host)
            .putString("igeeta_port", portStr)
            .apply()

        connectionStatus.text = "Verifying..."
        statusDot.setBackgroundResource(android.R.color.holo_orange_light)
        loadPlaylists()
    }

    private fun loadSyncHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = SyncDatabase.getInstance(this@SyncActivity)
            val log = db.getRecentLogs(1)
            val playlistCount = db.getAllPlaylists().size

            withContext(Dispatchers.Main) {
                val entry = log.firstOrNull()
                if (entry != null) {
                    // Parse timestamp and format nicely
                    val timestamp = entry.timestamp
                    lastSyncTime.text = if (timestamp.length > 10) {
                        timestamp.substring(0, 10) + " " + timestamp.substring(11, 16)
                    } else {
                        timestamp
                    }
                } else {
                    lastSyncTime.text = "Never"
                }
                playlistsSynced.text = playlistCount.toString()
            }
        }
    }

    private fun updateUIState() {
        if (isSyncing) {
            selectAllButton.visibility = View.GONE
            syncButton.visibility = View.GONE
            cleanButton.visibility = View.GONE
            cancelButton.visibility = View.VISIBLE
            progressCard.visibility = View.VISIBLE
            playlistRecyclerView.isEnabled = false
        } else {
            selectAllButton.visibility = View.VISIBLE
            syncButton.visibility = View.VISIBLE
            cleanButton.visibility = View.VISIBLE
            cancelButton.visibility = View.GONE
            playlistRecyclerView.isEnabled = true
            updateSyncButton()
        }
    }

    private fun updateSyncButton() {
        syncButton.isEnabled = adapter.getSelectedIds().isNotEmpty() && !isSyncing
    }

    private fun startSync() {
        val selectedIds = adapter.getSelectedIds()
        if (selectedIds.isEmpty()) return

        isSyncing = true
        updateUIState()
        progressBar.isIndeterminate = true
        progressText.text = "Starting sync..."

        SyncService.start(this, selectedIds)
    }

    private fun cancelSync() {
        SyncService.cancel(this)
    }

    private fun cleanCollection() {
        android.util.Log.d("SyncActivity", "cleanCollection called")
        Toast.makeText(this, "Clean button clicked", Toast.LENGTH_SHORT).show()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Clean Collection")
            .setMessage("Remove all synced tracks, playlists, and database entries? This cannot be undone.")
            .setPositiveButton("Clean") { _, _ ->
                android.util.Log.d("SyncActivity", "Clean button clicked in dialog")
                Toast.makeText(this, "Cleaning...", Toast.LENGTH_SHORT).show()
                performClean()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performClean() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("SyncActivity", "Starting clean operation")
                val leftovers = com.igeeta.igpod.sync.SyncManager.cleanEverything(this@SyncActivity)
                if (leftovers > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val uris = com.igeeta.igpod.sync.SyncManager.queryLeftoverMediaUris(this@SyncActivity)
                    if (uris.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SyncActivity,
                                "Some files need system approval to delete", Toast.LENGTH_SHORT).show()
                            val pis = android.provider.MediaStore.createDeleteRequest(contentResolver, uris)
                            cleanupDeleteLauncher.launch(IntentSenderRequest.Builder(pis.intentSender).build())
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main) {
                    finishClean(leftovers == 0, leftovers)
                }
            } catch (e: Exception) {
                android.util.Log.e("SyncActivity", "Clean failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SyncActivity, "Clean failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onMediaDeleteResolved(approved: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            var remaining = -1
            if (approved) {
                // Give MediaStore a moment to process the batch deletion
                delay(1500)
                remaining = com.igeeta.igpod.sync.SyncManager.queryLeftoverMediaUris(this@SyncActivity).size
            }
            withContext(Dispatchers.Main) { finishClean(approved && remaining == 0, remaining) }
        }
    }

    private fun finishClean(success: Boolean, remaining: Int) {
        val msg = when {
            success -> "Collection cleaned"
            remaining > 0 -> "Database cleaned; $remaining file(s) could not be deleted"
            else -> "File deletion cancelled — database already cleaned"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        loadSyncHistory()
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    inner class PlaylistSelectAdapter : RecyclerView.Adapter<PlaylistSelectAdapter.ViewHolder>() {

        private var playlists = listOf<IgeetaApi.ServerPlaylistInfo>()
        private val selectedIds = mutableSetOf<Int>()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val name: TextView = view.findViewById(R.id.playlistName)
            val description: TextView = view.findViewById(R.id.playlistDescription)
            val trackCount: TextView = view.findViewById(R.id.trackCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sync_playlist, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val playlist = playlists[position]
            holder.name.text = playlist.name
            if (playlist.description.isNotBlank()) {
                holder.description.text = playlist.description
                holder.description.visibility = View.VISIBLE
            } else {
                holder.description.visibility = View.GONE
            }
            holder.trackCount.text = ""

            holder.checkbox.isChecked = playlist.id in selectedIds
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedIds.add(playlist.id) else selectedIds.remove(playlist.id)
                updateSyncButton()
            }
            holder.itemView.setOnClickListener {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
            }
        }

        override fun getItemCount() = playlists.size

        fun setPlaylists(list: List<IgeetaApi.ServerPlaylistInfo>) {
            playlists = list
            notifyDataSetChanged()
        }

        fun selectAll() {
            if (selectedIds.size == playlists.size) {
                selectedIds.clear()
            } else {
                selectedIds.addAll(playlists.map { it.id })
            }
            notifyDataSetChanged()
            updateSyncButton()
        }

        fun getSelectedIds(): List<Int> = selectedIds.toList()
    }
}
