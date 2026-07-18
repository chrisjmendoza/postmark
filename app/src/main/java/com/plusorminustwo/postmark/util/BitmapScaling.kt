package com.plusorminustwo.postmark.util

import android.graphics.Bitmap

/**
 * Scales [bitmap] down so its longest edge fits within [maxDim] px, preserving aspect
 * ratio. Returns the original bitmap unchanged when it already fits, so callers can use
 * a `scaled !== bitmap` identity check to decide whether to recycle the copy. Each output
 * dimension is coerced to at least 1 px so an extreme aspect ratio never yields a 0-px
 * bitmap. Shared by [com.plusorminustwo.postmark.service.customization.ChatBackgroundImageStore]
 * and [com.plusorminustwo.postmark.service.sms.MmsManagerWrapper].
 */
internal fun scaleToMaxDimension(bitmap: Bitmap, maxDim: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= maxDim && h <= maxDim) return bitmap
    val scale = maxDim.toFloat() / maxOf(w, h)
    return Bitmap.createScaledBitmap(
        bitmap,
        (w * scale).toInt().coerceAtLeast(1),
        (h * scale).toInt().coerceAtLeast(1),
        true
    )
}
