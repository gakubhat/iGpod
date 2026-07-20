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

package com.igeeta.igpod.audiosysfwd;

import android.media.AudioSystem;
import android.media.INativeAudioVolumeGroupCallback;
import android.media.audio.common.AudioVolumeGroupChangeEvent;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Objects;

/* package */ class AudioVolumeGroupCallbackAdapter extends INativeAudioVolumeGroupCallback.Stub {
    private final AudioVolumeGroupCallback delegate;

    public AudioVolumeGroupCallbackAdapter(AudioVolumeGroupCallback delegate) {
        this.delegate = delegate;
    }

    @SuppressWarnings("PrivateApi")
    public static Method getAdd() throws NoSuchMethodException {
        return AudioSystem.class.getDeclaredMethod("registerAudioVolumeGroupCallback",
                INativeAudioVolumeGroupCallback.class);
    }

    @SuppressWarnings("PrivateApi")
    public static Method getRemove() throws NoSuchMethodException {
        return AudioSystem.class.getDeclaredMethod("unregisterAudioVolumeGroupCallback",
                INativeAudioVolumeGroupCallback.class);
    }

    @Override
    public void onAudioVolumeGroupChanged(AudioVolumeGroupChangeEvent volumeChangeEvent) {
        com.igeeta.igpod.audiosysfwd.AudioVolumeGroupChangeEvent event;
        try {
            Class<?> clazz = volumeChangeEvent.getClass();
            event = new com.igeeta.igpod.audiosysfwd.AudioVolumeGroupChangeEvent();
            event.flags = (int) Objects.requireNonNull(clazz.getField("flags").get(volumeChangeEvent));
            event.groupId = (int) Objects.requireNonNull(clazz.getField("groupId").get(volumeChangeEvent));
            event.muted = (boolean) Objects.requireNonNull(clazz.getField("muted").get(volumeChangeEvent));
            event.volumeIndex = (int) Objects.requireNonNull(clazz.getField("volumeIndex").get(volumeChangeEvent));
        } catch (Throwable t) {
            Log.e("AVolumeGroupCAdapter", "failed to convert", t);
            return;
        }
        delegate.onAudioVolumeGroupChanged(event);
    }
}
