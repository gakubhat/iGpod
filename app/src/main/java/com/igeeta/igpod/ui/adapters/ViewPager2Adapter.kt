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

package com.igeeta.igpod.ui.adapters

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.igeeta.igpod.R
import com.igeeta.igpod.ui.MainActivity
import com.igeeta.igpod.ui.fragments.AdapterFragment
import com.igeeta.igpod.ui.fragments.RadioFragment
import com.igeeta.igpod.ui.fragments.SyncTabFragment

/**
 * This is the ViewPager2 adapter.
 */
class ViewPager2Adapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle,
    private val context: Context,
    private val viewPager2: ViewPager2
) : FragmentStateAdapter(fragmentManager, lifecycle),
    DefaultLifecycleObserver {

    // iGpod: fixed tab order. Genres is a category but not a top-level tab.
    private var tabs = listOf(
        Tab.Songs,
        Tab.Radio,
        Tab.Albums,
        Tab.Artists,
        Tab.Playlist,
        Tab.Raagas,
        Tab.Sync,
    )

    init {
        lifecycle.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
    }

    fun getLabelResId(position: Int) = tabs[position].label

    override fun getItemCount() = tabs.size

    override fun createFragment(position: Int): Fragment {
        val tab = tabs[position]
        return when (tab.id) {
            R.id.radio -> RadioFragment()
            R.id.igeeta_sync -> SyncTabFragment()
            else -> AdapterFragment().apply {
                arguments = Bundle().apply {
                    putInt("ID", tab.id)
                }
            }
        }
    }

    override fun getItemId(position: Int): Long {
        return tabs[position].id.toLong()
    }

    override fun containsItem(itemId: Long): Boolean {
        return tabs.any { it.id.toLong() == itemId }
    }

    companion object {
        enum class Tab(val id: Int, val label: Int) {
            // Order of entries is irrelevant; the adapter uses a fixed list above.
            Songs(R.id.songs, R.string.category_songs),
            Albums(R.id.albums, R.string.category_albums),
            Artists(R.id.artists, R.string.category_artists),
            Genres(R.id.genres, R.string.category_genres),
            Dates(R.id.dates, R.string.category_dates),
            Folders(R.id.folders, R.string.folders),
            FileSystem(R.id.detailed_folders, R.string.filesystem),
            Playlist(R.id.playlists, R.string.category_playlists),
            Raagas(R.id.raagas, R.string.category_raagas),
            Radio(R.id.radio, R.string.category_radio),
            Sync(R.id.igeeta_sync, R.string.igeeta_sync)
        }
    }
}
