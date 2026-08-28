package com.igeeta.igpod.ui.fragments.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.igeeta.igpod.R
import com.igeeta.igpod.sync.IgeetaApi
import com.igeeta.igpod.sync.SyncConfig
import com.igeeta.igpod.sync.SyncDatabase
import com.igeeta.igpod.ui.SyncActivity
import com.igeeta.igpod.ui.fragments.BasePreferenceFragment
import com.igeeta.igpod.ui.fragments.BaseSettingsActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncSettingsActivity : BaseSettingsActivity(
    R.string.igeeta_sync,
    { SyncSettingsFragment() })

class SyncSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_sync, rootKey)

        // Configure host preference
        findPreference<EditTextPreference>("igeeta_host")?.setOnBindEditTextListener { editText ->
            editText.hint = "e.g. 192.168.1.100"
        }

        // Configure port preference
        findPreference<EditTextPreference>("igeeta_port")?.setOnBindEditTextListener { editText ->
            editText.hint = "8000"
        }

        // Test connection
        findPreference<Preference>("igeeta_test_connection")?.setOnPreferenceClickListener {
            testConnection()
            true
        }

        // Open sync page
        findPreference<Preference>("igeeta_open_sync")?.setOnPreferenceClickListener {
            startActivity(Intent(requireActivity(), SyncActivity::class.java))
            true
        }

        updateSyncStatus()
    }

    private fun getConfig(): SyncConfig? {
        val prefs = preferenceScreen.sharedPreferences ?: return null
        val host = prefs.getString("igeeta_host", "") ?: ""
        val portStr = prefs.getString("igeeta_port", "8000") ?: "8000"
        val port = portStr.toIntOrNull() ?: 8000
        val config = SyncConfig(host, port)
        return if (config.isValid) config else null
    }

    private fun testConnection() {
        val config = getConfig()
        if (config == null) {
            Toast.makeText(requireContext(), R.string.igeeta_no_server, Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val api = IgeetaApi(config)
            val success = try {
                api.testConnection()
            } catch (_: Exception) {
                false
            } finally {
                api.close()
            }

            withContext(Dispatchers.Main) {
                val msg = if (success) {
                    R.string.igeeta_test_connection_success
                } else {
                    R.string.igeeta_test_connection_failed
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateSyncStatus() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = SyncDatabase.getInstance(requireContext())
            val playlists = db.getAllPlaylists()
            val count = playlists.size
            val log = db.getRecentLogs(1)

            withContext(Dispatchers.Main) {
                // Last sync
                val lastSyncPref = findPreference<Preference>("igeeta_last_sync")
                val lastSyncEntry = log.firstOrNull()
                if (lastSyncEntry != null) {
                    lastSyncPref?.summary = lastSyncEntry.detail
                } else {
                    lastSyncPref?.summary = getString(R.string.igeeta_last_sync_never)
                }

                // Synced count
                findPreference<Preference>("igeeta_synced_count")?.summary =
                    count.toString()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSyncStatus()
    }
}
