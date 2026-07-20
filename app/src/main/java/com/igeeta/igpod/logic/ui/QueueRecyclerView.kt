/*
 *     Copyright (C) 2026 Michael Zh
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

package com.igeeta.igpod.logic.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent

class QueueRecyclerView : MyRecyclerView {
    constructor(context: Context, attributeSet: AttributeSet?, defStyleAttr: Int)
            : super(context, attributeSet, defStyleAttr)

    constructor(context: Context, attributeSet: AttributeSet?) : super(context, attributeSet, 0)

    constructor(context: Context) : super(context, null)

    private var lastYPos = 0f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        // tell the parents to piss off *unless* we are at the top of the list AND this is a scroll
        // up gesture (a.k.a user wants to dismiss the bottom sheet)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                lastYPos = e.y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(canScrollVertically(-1) || e.y < lastYPos)
                lastYPos = e.y
            }
        }
        return super.onInterceptTouchEvent(e)
    }
}