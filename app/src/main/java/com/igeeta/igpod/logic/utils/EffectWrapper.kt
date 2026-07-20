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

package com.igeeta.igpod.logic.utils

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.StrictMode
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log
import com.igeeta.igpod.hificore.ReflectionAudioEffect

abstract class EffectWrapper<T> {
    var created = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    maybeCreate()
                } else {
                    destroy()
                }
            }
        }
    abstract val hasControl: Boolean
    var hasControlListener: ((Boolean) -> Unit)? = null
    abstract val effect: T?
    var audioSessionId: Int = 0
        set(value) {
            if (field != value) {
                if (field != 0) {
                    destroy()
                }
                field = value
                if (value != 0) {
                    maybeCreate()
                }
            }
        }
    protected abstract fun maybeCreate()
    protected abstract fun destroy()
    fun releaseSafe() {
        try {
            created = false
        } catch (e: Throwable) {
            Log.e("EffectWrapper", "failed to release $this", e)
        }
    }
}

class VolumeEffectWrapper(private val priority: Int) : EffectWrapper<Volume>() {
    companion object {
        private const val TAG = "VolumeWrapper"
    }
    override var effect: Volume? = null
        private set
    override val hasControl: Boolean
        get() = effect?.hasControl() ?: false

    override fun maybeCreate() {
        if (audioSessionId != 0 && created) {
            try {
                effect = Volume(priority, audioSessionId)
                effect!!.setControlStatusListener { effect, control ->
                    if (effect != this.effect) {
                        Log.e("VolumeWrapper", "stale control event: $control")
                        try {
                            effect.release()
                        } catch (_: Throwable) {
                        }
                        return@setControlStatusListener
                    }
                    Log.i(TAG, "volume control state is now: $control")
                    hasControlListener?.invoke(control)
                }
                val control = effect!!.hasControl()
                hasControlListener?.invoke(control)
                Log.i(TAG, "init volume, control state is: $control")
            } catch (e: Throwable) {
                Log.e(TAG, "failed to init Volume effect", e)
                try {
                    effect?.release()
                } catch (_: Throwable) {
                }
                effect = null
            }
        }
    }

    override fun destroy() {
        // This could be async if it has to.
        if (effect != null) {
            Log.i(TAG, "release volume")
            effect?.release()
            effect = null
            hasControlListener?.invoke(false)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.P)
class DynamicsProcessingEffectWrapper(
    private val handler: Handler,
    private val factory: (Int) -> DynamicsProcessing,
) : EffectWrapper<DynamicsProcessing>() {
    companion object {
        private const val TAG = "DPEWrapper"
    }

    override var effect: DynamicsProcessing? = null
        private set
    private var dpeCanary: ReflectionAudioEffect? = null
    private var failureCounter = 0
    override var hasControl: Boolean = false
        private set

    override fun maybeCreate() {
        if (audioSessionId != 0 && created) {
            createDpeEffect()
        }
    }

    override fun destroy() {
        if (effect != null) {
            Log.i(TAG, "release DPE")
            // DPE must be released synchronously to avoid getting the old effect instance
            // again, with its old inUse values.
            effect?.release()
            effect = null
            failureCounter = 0
            hasControl = false
        }
    }

    private fun createDpeEffect() {
        // DPE has this behaviour which I can really only call a bug, where inUse values are carried
        // over from other apps and the only way to reset it is to entirely release all instances
        // of the effect in ALL apps in this session ID at the same time. Also, sometimes if the
        // effect is busy the constructor randomly throws because it doesn't support priority well
        // - it always tries to set values even if we don't have control. Amazing work, Google. For
        // the DynamicsProcessing DSP to be the best thing ever, the parameter implementation is so
        // stupid I can't even put it into words.
        try {
            try {
                effect = factory(audioSessionId)
            } catch (t: Throwable) {
                // DynamicsProcessing does not release() the instance if illegal arguments are
                // passed to the constructor. We have to rely on finalize to avoid conflicts with
                // ourselves, hence do a GC. This API is so broken...
                val policy = StrictMode.getThreadPolicy()
                try {
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
                    System.gc()
                } finally {
                    StrictMode.setThreadPolicy(policy)
                }
                throw t
            }
            effect!!.setControlStatusListener { effect, control ->
                if (effect != this.effect) {
                    Log.e(TAG, "stale control event: $control")
                    try {
                        effect.release()
                    } catch (_: Throwable) {
                    }
                    return@setControlStatusListener
                }
                Log.i(TAG, "dpe control state is now: $control")
                try {
                    effect.release()
                } catch (_: Throwable) {
                }
                // delete the old DPE and make a new one (we should still have control then)
                // note this would not make a new instance if there is someone with lower priority
                // that would just mean we have to give up cleaning the inUse
                this.effect = null
                // create new DPE effect without contamination of inUse parameters from other apps
                if (control) createDpeEffect()
                else { // user enabled external equalizer
                    hasControl = false
                    createDpeCanary()
                }
            }
            // wow, we got here. very good.
            if (dpeCanary != null) {
                Log.i(TAG, "release dpe canary because we got real dpe")
                try {
                    dpeCanary!!.release()
                } catch (e: Throwable) {
                    Log.e(TAG, "failed to release DPE canary", e)
                }
                dpeCanary = null
            }
            hasControl = effect!!.hasControl()
            Log.i(TAG, "init dpe, control state is: $hasControl")
            failureCounter = 0
        } catch (e: Throwable) {
            if (e is UnsupportedOperationException)
                Log.w(TAG, "failed to init DPE effect: $e")
            else
                Log.e(TAG, "failed to init DPE effect", e)
            try {
                effect?.release()
            } catch (_: Throwable) {
            }
            effect = null
            hasControl = false
            failureCounter++
            createDpeCanary()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createDpeCanary() {
        // The bug where constructor sets parameters (which crashes, and hence we can't register a
        // control listener) can be worked around by using AudioEffect class raw via reflection to
        // check when we regain control. (Set lower prio to avoid conflicting with ourselves.)
        if (dpeCanary == null) {
            try {
                dpeCanary = ReflectionAudioEffect(
                    AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING,
                    ReflectionAudioEffect.EFFECT_TYPE_NULL, Int.MIN_VALUE, audioSessionId
                )
                dpeCanary!!.setControlStatusListener { _, controlGranted ->
                    Log.i(TAG, "dpe canary control state is now: $controlGranted")
                    if (controlGranted) {
                        try {
                            dpeCanary!!.release()
                        } catch (e: Throwable) {
                            Log.e(TAG, "failed to release DPE canary", e)
                        }
                        dpeCanary = null
                        createDpeEffect()
                    } else {
                        Log.e(TAG, "DPE canary lost control, but why did it ever have it?")
                        // an invalid state transition occurred, ensure we are using setVolume()
                        hasControlListener?.invoke(false)
                    }
                }
                Log.i(TAG, "init dpe canary")
                if (dpeCanary!!.hasControl()) {
                    if (failureCounter >= 3) {
                        // This would happen if and only if someone else has DPE with Int minimum
                        // priority open all the time (ie, does not use the same logic as otherwise
                        // the other effect would be released in control gained listener). In this
                        // case we have to wait it out, there's no other way.
                        Log.w(TAG, "release dpe canary and wait 60s before trying again")
                        try {
                            dpeCanary!!.release()
                        } catch (e: Throwable) {
                            Log.e(TAG, "failed to release DPE canary", e)
                        }
                        dpeCanary = null
                        handler.postDelayed(this::tryRecreateIfFailed, 60000)
                    } else {
                        Log.w(TAG, "release dpe canary because we suddenly have control")
                        try {
                            dpeCanary!!.release()
                        } catch (e: Throwable) {
                            Log.e(TAG, "failed to release DPE canary", e)
                        }
                        dpeCanary = null
                        createDpeEffect()
                    }
                } else {
                    failureCounter = 0
                }
            } catch (e: Throwable) {
                Log.e(TAG, "failed to init DPE canary", e)
                try {
                    dpeCanary?.release()
                } catch (_: Throwable) {
                }
                dpeCanary = null
                // whatever, I give up.
            }
        }
    }

    fun tryRecreateIfFailed() {
        if (audioSessionId != 0 && created && dpeCanary == null && effect == null) {
            createDpeEffect()
        }
    }
}