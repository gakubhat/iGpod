/*
 *     Copyright (C) 2026 nift4
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

package com.igeeta.igpod.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.igeeta.igpod.R
import com.igeeta.igpod.logic.enableEdgeToEdgePaddingListener
import com.igeeta.igpod.logic.enableEdgeToEdgeProperly
import com.igeeta.igpod.logic.gramophoneApplication
import com.igeeta.igpod.logic.hasAudioPermission
import com.igeeta.igpod.logic.hasScopedStorageV2
import com.igeeta.igpod.logic.hasScopedStorageWithMediaTypes
import com.igeeta.igpod.logic.ui.BaseActivity
import com.igeeta.igpod.logic.ui.MyRecyclerView
import com.igeeta.igpod.ui.adapters.BaseAdapter

abstract class PickerActivity<T : Any> : BaseActivity() {
    companion object {
        private const val PERMISSION_READ_MEDIA_AUDIO = 100
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdgeProperly()
        setContentView(R.layout.fragment_general_sub)
        val topAppBar = findViewById<MaterialToolbar>(R.id.topAppBar)
        val collapsingToolbarLayout =
            findViewById<CollapsingToolbarLayout>(R.id.collapsingtoolbar)
        val recyclerView = findViewById<MyRecyclerView>(R.id.recyclerview)
        val appBarLayout = findViewById<AppBarLayout>(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()

        // Show title text.
        collapsingToolbarLayout.title = getTitleStr()

        val songAdapter = makeAdapter()

        recyclerView.enableEdgeToEdgePaddingListener()
        recyclerView.setAppBar(appBarLayout)
        recyclerView.adapter = songAdapter.concatAdapter

        // Build FastScroller.
        recyclerView.fastScroll(songAdapter, songAdapter.itemHeightHelper)

        topAppBar.setNavigationOnClickListener {
            finish()
        }

        // Check all permissions.
        if (!hasAudioPermission()) {
            // Ask if was denied.
            ActivityCompat.requestPermissions(
                this,
                if (hasScopedStorageWithMediaTypes())
                    arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
                else if (hasScopedStorageV2())
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                else
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                PERMISSION_READ_MEDIA_AUDIO,
            )
        } else {
            // If all permissions are granted, we can update library now.
            if (!gramophoneApplication.reader.hadFirstRefresh) {
                CoroutineScope(Dispatchers.Default).launch {
                    gramophoneApplication.reader.refresh()
                }
            }
        }
    }

    protected abstract fun makeAdapter(): BaseAdapter<T>
    protected abstract fun getTitleStr(): String

    /**
     * onRequestPermissionResult:
     *   Update library after permission is granted.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_READ_MEDIA_AUDIO) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                CoroutineScope(Dispatchers.Default).launch {
                    gramophoneApplication.reader.refresh()
                }
            } else {
                Toast.makeText(this, getString(R.string.grant_audio), Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.setData("package:$packageName".toUri())
                startActivity(intent)
                finish()
            }
        }
    }
}