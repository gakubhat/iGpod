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

package com.igeeta.igpod.ui.fragments.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.igeeta.igpod.R
import com.igeeta.igpod.logic.hasImagePermission
import com.igeeta.igpod.logic.hasScopedStorageWithMediaTypes
import com.igeeta.igpod.logic.utils.Flags
import com.igeeta.igpod.ui.fragments.BasePreferenceFragment
import com.igeeta.igpod.ui.fragments.BaseSettingsActivity


class BehaviorSettingsActivity : BaseSettingsActivity(
    R.string.settings_category_behavior,
    { BehaviorSettingsFragment() })

class BehaviorSettingsFragment : BasePreferenceFragment() {

    override fun onResume() {
        super.onResume()
        if (hasScopedStorageWithMediaTypes()) {
            val preference = findPreference<SwitchPreferenceCompat>("album_covers")!!
            preference.isPersistent = false
            if (!Flags.REMOVE_IMAGE_PERMISSION)
                preference.isChecked = requireContext().hasImagePermission()
            else
                preference.isVisible = false
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "blacklist") {
            startActivity(BlacklistSettingsActivity::class.java)
        }
        // Prior to Android 13, this changes a setting which changes MediaStoreUtils behaviour
        // Android 13 and later, this displays state of images permission granted/denied
        if (hasScopedStorageWithMediaTypes() && !Flags.REMOVE_IMAGE_PERMISSION &&
            preference.key == "album_covers") {
            Toast.makeText(
                requireActivity(), if (requireContext().hasImagePermission())
                    R.string.deny_images else R.string.grant_images, Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.setData("package:${requireContext().packageName}".toUri())
            startActivity(intent)
        }
        return super.onPreferenceTreeClick(preference)
    }
}
