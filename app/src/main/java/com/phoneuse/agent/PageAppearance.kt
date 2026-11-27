package com.phoneuse.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.phoneuse.screen.Bounds
import kotlin.math.abs

/** Local comparison of already permitted pixels. No files, screen capture or model call. */
internal object PageAppearance {
    fun read(bytes: ByteArray, region: Bounds, width: Int, height: Int): IntArray? {
        if (width <= 0 || height <= 0 || region.isEmpty || !Bounds(0, 0, width, height).contains(region)) return null
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            val left = (region.left.toLong() * image.width / width).toInt()
            val top = (region.top.toLong() * image.height / height).toInt()
            val right = (region.right.toLong() * image.width / width).toInt()
            val bottom = (region.bottom.toLong() * image.height / height).toInt()
            if (right <= left || bottom <= top) return null
            val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
            try {
                val scaled = Bitmap.createScaledBitmap(crop, 128, 128, true)
                try { return IntArray(128 * 128).also { scaled.getPixels(it, 0, 128, 0, 0, 128, 128) } }
                finally { if (scaled !== crop) scaled.recycle() }
            } finally { if (crop !== image) crop.recycle() }
        } finally { image.recycle() }
    }

    /** A small JPEG rounding difference is harmless; changed text, layout or a popup is not. */
    fun matches(a: IntArray?, b: IntArray?): Boolean = a != null && b != null && a.isNotEmpty() &&
        a.size == b.size && a.indices.all { i ->
            intArrayOf(0, 8, 16).all { shift -> abs((a[i] ushr shift and 255) - (b[i] ushr shift and 255)) <= 12 }
        }
}
