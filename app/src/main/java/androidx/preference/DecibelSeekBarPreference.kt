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

package androidx.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import com.igeeta.igpod.R

open class DecibelSeekBarPreference(
    context: Context, attrs: AttributeSet?
) : SeekBarPreference(context, attrs) {
    override fun updateLabelValue(value: Int) {
        (textViewField.get(this) as TextView?)?.text = getText(value)
    }

    private val textViewField by lazy {
        SeekBarPreference::class.java.getDeclaredField("mSeekBarValueTextView").apply {
            isAccessible = true
        }
    }

    protected open fun getText(value: Int): String {
        return context.getString(R.string.d_db, value)
    }
}

class Minus15SeekBarPreference(
    context: Context, attrs: AttributeSet?
) : DecibelSeekBarPreference(context, attrs) {
    override fun getText(value: Int): String {
        return super.getText(value - 15)
    }
}