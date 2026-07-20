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

package com.igeeta.igpod.logic.utils.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

enum class Invalidation {
    Required,
    Optional,
    Never
}

class ReplayCacheInvalidationManager(val invalidate: () -> Unit) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ReplayCacheInvalidationManager>
}

suspend fun requireReplayCacheInvalidationManager() =
    currentCoroutineContext()[ReplayCacheInvalidationManager]
        ?: throw IllegalStateException("Replay cache invalidation not available here")

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.provideReplayCacheInvalidationManager(copyDownstream: Invalidation = Invalidation.Never) =
    object : Flow<T> {
        override suspend fun collect(collector: FlowCollector<T>) {
            val sharedFlow = collector as? MutableSharedFlow<T>
                ?: throw IllegalStateException("withReplayCacheInvalidation needs to be used _directly_ before shareIn")
            if (sharedFlow is MutableStateFlow<T>)
                throw IllegalStateException("withReplayCacheInvalidation does not support state flows")
            val downstream = if (copyDownstream != Invalidation.Never)
                currentCoroutineContext()[ReplayCacheInvalidationManager] else null
            if (downstream == null && copyDownstream == Invalidation.Required)
                throw IllegalStateException("Replay cache invalidation not available but copyDownstream is Required")
            withContext(ReplayCacheInvalidationManager(if (downstream != null) object :
                Function0<Unit> {
                override fun invoke() {
                    sharedFlow.resetReplayCache()
                    downstream.invalidate()
                }
            } else sharedFlow::resetReplayCache)) {
                return@withContext this@provideReplayCacheInvalidationManager.collect(collector)
            }
        }
    }