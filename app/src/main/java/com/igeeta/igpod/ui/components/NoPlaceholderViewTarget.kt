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

package com.igeeta.igpod.ui.components

import android.widget.ImageView
import coil3.Image
import coil3.ImageLoader
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import coil3.target.ImageViewTarget

class NoPlaceholderImageViewTarget(view: ImageView) : ImageViewTarget(view) {
    override fun onStart(placeholder: Image?) {
        // do nothing
    }
}

inline fun ImageView.loadNoPlaceholder(
    data: Any?,
    imageLoader: ImageLoader = context.imageLoader,
    builder: ImageRequest.Builder.() -> Unit = {},
): Disposable {
    val request = ImageRequest.Builder(context)
        .data(data)
        .target(NoPlaceholderImageViewTarget(this))
        .apply(builder)
        .build()
    return imageLoader.enqueue(request)
}