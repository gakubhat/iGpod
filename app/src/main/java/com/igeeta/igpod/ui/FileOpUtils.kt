package com.igeeta.igpod.ui

import com.igeeta.igpod.R
import com.igeeta.igpod.ui.adapters.AlbumAdapter
import com.igeeta.igpod.ui.adapters.ArtistAdapter
import com.igeeta.igpod.ui.adapters.DateAdapter
import com.igeeta.igpod.ui.adapters.DetailedFolderAdapter
import com.igeeta.igpod.ui.adapters.GenreAdapter
import com.igeeta.igpod.ui.adapters.PlaylistAdapter
import com.igeeta.igpod.ui.adapters.RaagasAdapter
import com.igeeta.igpod.ui.adapters.SongAdapter
import com.igeeta.igpod.ui.fragments.AdapterFragment

fun getAdapterType(adapter: AdapterFragment.BaseInterface<*>) =
    when {
        adapter is AlbumAdapter && adapter.isSubFragment == null -> {
            0
        }

        adapter is ArtistAdapter -> {
            1
        }

        adapter is DateAdapter -> {
            2
        }

        adapter is GenreAdapter -> {
            3
        }

        adapter is RaagasAdapter -> {
            18
        }

        adapter is PlaylistAdapter -> {
            4
        }

        adapter is SongAdapter && !adapter.folder && adapter.isSubFragment == null -> {
            5
        }

        adapter is SongAdapter && adapter.folder -> {
            6
        }

        adapter is SongAdapter && adapter.isSubFragment == R.id.search -> {
            7
        }

        adapter is SongAdapter && adapter.isSubFragment == R.id.playlist -> {
            8
        }

        adapter is SongAdapter && adapter.isSubFragment == R.id.genre -> {
            9
        }

        adapter is SongAdapter && adapter.isSubFragment == R.id.date -> {
            10
        }

        adapter is SongAdapter && adapter.isSubFragment == R.id.raagas -> {
            19
        }

        adapter is SongAdapter && (adapter.isSubFragment == R.id.artist
                || adapter.isSubFragment == R.id.album_artist) -> {
            11
        }

        adapter is SongAdapter && adapter.isSubFragment == R.id.album -> {
            12
        }

        adapter is AlbumAdapter && (adapter.isSubFragment == R.id.artist
                || adapter.isSubFragment == R.id.album_artist) -> {
            13
        }

        adapter is DetailedFolderAdapter && !adapter.isDetailed -> {
            14
        }

        adapter is DetailedFolderAdapter && adapter.isDetailed -> {
            15
        }

        adapter is SongAdapter && adapter.isSubFragment == R.id.edit -> {
            16
        }

        adapter is SongAdapter && adapter.isSubFragment == R.id.songs -> {
            17
        }

        else -> {
            throw IllegalStateException()
        }
    }