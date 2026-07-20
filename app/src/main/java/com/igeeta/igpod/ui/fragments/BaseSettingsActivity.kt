/*
 *     Copyright (C) 2025 nift4
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

package com.igeeta.igpod.ui.fragments

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.igeeta.igpod.R
import com.igeeta.igpod.logic.enableEdgeToEdgePaddingListener
import com.igeeta.igpod.logic.ui.BaseActivity

abstract class BaseSettingsActivity(
    private val str: Int,
    private val fragmentCreator: () -> Fragment
) : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.fragment_top_settings)
        val topAppBar = findViewById<MaterialToolbar>(R.id.topAppBar)
        val collapsingToolbar = findViewById<CollapsingToolbarLayout>(R.id.collapsingtoolbar)

        findViewById<AppBarLayout>(R.id.appbarlayout).enableEdgeToEdgePaddingListener()
        collapsingToolbar.title = getString(str)

        topAppBar.setNavigationOnClickListener {
            finish()
        }

        supportFragmentManager
            .beginTransaction()
            .add(R.id.settings, fragmentCreator())
            .commit()
    }
}