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

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.igeeta.igpod.BuildConfig
import com.igeeta.igpod.R
import com.igeeta.igpod.logic.utils.ColorUtils
import com.igeeta.igpod.logic.utils.data.Contributors
import com.igeeta.igpod.ui.fragments.BasePreferenceFragment
import com.igeeta.igpod.ui.fragments.BaseSettingsActivity

class AboutSettingsActivity : BaseSettingsActivity(
    R.string.settings_about_app,
    { AboutSettingsFragment() })

class AboutSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        val versionPrefs = findPreference<Preference>("app_version")
        val releaseType = findPreference<Preference>("package_type")
        val contributorsPref = findPreference<Preference>("contributors")
        versionPrefs!!.summary = BuildConfig.MY_VERSION_NAME
        releaseType!!.summary = BuildConfig.RELEASE_TYPE
        contributorsPref!!.summary =
            requireContext().getString(
                R.string.settings_contributors_click,
                Contributors.LIST.subList(0, 5).joinToString { it.login })
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "app_name") {
            val processColor = ColorUtils.getColor(
                MaterialColors.getColor(
                    requireView(),
                    android.R.attr.colorBackground
                ),
                ColorUtils.ColorType.COLOR_BACKGROUND,
                requireContext()
            )
            val drawable = GradientDrawable()
            drawable.color =
                ColorStateList.valueOf(processColor)
            drawable.cornerRadius = 64f
            val rootView = MaterialAlertDialogBuilder(requireContext())
                .setBackground(drawable)
                .setView(R.layout.dialog_about)
                .show()
            val versionTextView = rootView.findViewById<TextView>(R.id.version)!!
            versionTextView.text =
                BuildConfig.VERSION_NAME
            val aboutTextView = rootView.findViewById<TextView>(R.id.about_text)!!
            aboutTextView.text = requireContext()
                .getString(R.string.opensource_info, "© 2023-2026 AkaneTan, nift4 and contributors")
            // does not render correctly on old versions for mysterious reasons
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                rootView.findViewById<View>(R.id.iconCard)!!.visibility = View.GONE
        } else if (preference.key == "contributors") {
            startActivity(ContributorsSettingsActivity::class.java)
        } else if (preference.key == "package_type") {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_package_type)
                .setMessage(R.string.package_type_explainer)
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
        } else if (preference.key == "open_source_licenses") {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_open_source_licenses)
                .setMessage(
                    """
                        Copyright (C) 2023-2024 AkaneTan
                        Copyright (C) 2023-2026 nift4
                        Copyright (C) 2024 The Gramophone authors

                        This program is free software: you can redistribute it and/or modify
                        it under the terms of the GNU General Public License as published by
                        the Free Software Foundation, either version 3 of the License, or
                        (at your option) any later version.

                        This program is distributed in the hope that it will be useful,
                        but WITHOUT ANY WARRANTY; without even the implied warranty of
                        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
                        GNU General Public License for more details.

                        You should have received a copy of the GNU General Public License
                        along with this program.  If not, see <https://www.gnu.org/licenses/>.
                """.trimIndent()
                )
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (Settings.System.getString(
                            requireContext().contentResolver,
                            "firebase.test.lab"
                        ) != "true"
                    ) {
                        startActivity(OssLicensesSettingsActivity::class.java)
                    }
                }
                .show()
        } else return super.onPreferenceTreeClick(preference)
        return true
    }
}
