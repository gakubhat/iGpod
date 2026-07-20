/*
 *     Copyright (C) 2024 nift4
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

package com.igeeta.igpod.logic.utils

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

interface LifecycleCallbackList<T> {
    fun addCallback(lifecycle: Lifecycle?, callback: T)
    fun removeCallback(callback: T)
}

class LifecycleCallbackListImpl<T>(lifecycle: Lifecycle? = null) : LifecycleCallbackList<T>,
    DefaultLifecycleObserver {
    private val list = hashMapOf<T, CallbackLifecycleObserver?>()

    init {
        lifecycle?.addObserver(this)
    }

    fun toBaseInterface(): LifecycleCallbackList<T> {
        return this
    }

    override fun addCallback(lifecycle: Lifecycle?, callback: T) {
        if (list.containsKey(callback)) throw IllegalArgumentException("cannot add same callback twice")
        list[callback] = lifecycle?.let { CallbackLifecycleObserver(it, callback) }
    }

    override fun removeCallback(callback: T) {
        list.remove(callback)?.release()
    }

    fun dispatch(callback: Disposable.(T) -> Unit) {
        val ds = DisposableImpl()
        list.toList().forEach {
            ds.disposed = false
            ds.callback(it.first)
            if (ds.disposed) removeCallback(it.first)
        }
    }

    fun release() {
        dispatch { dispose() }
    }

    fun iterator(): Iterator<T> {
        return list.keys.iterator()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
    }

    interface Disposable {
        fun dispose()
    }

    class DisposableImpl : Disposable {
        var disposed = false
        override fun dispose() {
            if (disposed) throw IllegalStateException("already disposed")
            disposed = true
        }
    }

    private inner class CallbackLifecycleObserver(
        private val lifecycle: Lifecycle,
        private val callback: T
    ) : DefaultLifecycleObserver {

        init {
            lifecycle.addObserver(this)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            removeCallback(callback)
        }

        fun release() {
            lifecycle.removeObserver(this)
        }
    }
}