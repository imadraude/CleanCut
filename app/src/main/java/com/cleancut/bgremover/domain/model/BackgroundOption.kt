package com.cleancut.bgremover.domain.model

import android.graphics.Bitmap

/**
 * Visual background choice for compositing and preview.
 */
sealed class BackgroundOption {
    data object Transparent : BackgroundOption()
    data class SolidColor(val colorArgb: Int) : BackgroundOption()
    data class Image(val backgroundBitmap: Bitmap) : BackgroundOption()
}
