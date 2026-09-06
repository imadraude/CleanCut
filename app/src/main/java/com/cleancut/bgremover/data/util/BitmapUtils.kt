package com.cleancut.bgremover.data.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

sealed class BackgroundOption {
    object Transparent : BackgroundOption()
    data class SolidColor(val colorArgb: Int) : BackgroundOption()
    data class Image(val backgroundBitmap: Bitmap) : BackgroundOption()
}

object BitmapUtils {

    /**
     * Loads a Bitmap from Uri safely, handling EXIF rotation and downsampling if larger than maxDimension.
     */
    fun loadBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap {
        var rawStream: InputStream? = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Неможливо відкрити зображення за вказаним Uri")

        var inputStream: InputStream? = BufferedInputStream(rawStream, 32 * 1024)

        // 1. Determine dimensions
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream?.close()

        val origWidth = options.outWidth
        val origHeight = options.outHeight

        // Calculate sample size
        var sampleSize = 1
        while ((origWidth / sampleSize) > maxDimension || (origHeight / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        // 2. Decode sampled bitmap
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        rawStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Неможливо повторно відкрити потік зображення")
        inputStream = BufferedInputStream(rawStream, 32 * 1024)
        val sampledBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            ?: throw IllegalStateException("Помилка декодування зображення")
        inputStream.close()

        // 3. Fix EXIF orientation
        val exifStream = context.contentResolver.openInputStream(uri)
        val rotationDegrees = if (exifStream != null) {
            val exif = ExifInterface(exifStream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            exifStream.close()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } else 0f

        return if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            Bitmap.createBitmap(
                sampledBitmap,
                0,
                0,
                sampledBitmap.width,
                sampledBitmap.height,
                matrix,
                true
            )
        } else {
            sampledBitmap
        }
    }

    /**
     * Combines the foreground cutout with selected background (transparent, solid color, or custom image).
     */
    fun compositeWithBackground(foreground: Bitmap, background: BackgroundOption): Bitmap {
        return when (background) {
            is BackgroundOption.Transparent -> foreground
            is BackgroundOption.SolidColor -> {
                val output = Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                canvas.drawColor(background.colorArgb)
                canvas.drawBitmap(foreground, 0f, 0f, null)
                output
            }
            is BackgroundOption.Image -> {
                val output = Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val bg = background.backgroundBitmap

                // Center crop background image into foreground dimensions
                val srcRect = calculateCenterCropRect(bg.width, bg.height, foreground.width, foreground.height)
                val destRect = Rect(0, 0, foreground.width, foreground.height)
                canvas.drawBitmap(bg, srcRect, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.drawBitmap(foreground, 0f, 0f, null)
                output
            }
        }
    }

    private fun calculateCenterCropRect(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Rect {
        val srcRatio = srcW.toFloat() / srcH.toFloat()
        val targetRatio = targetW.toFloat() / targetH.toFloat()

        return if (srcRatio > targetRatio) {
            val newWidth = (srcH * targetRatio).toInt()
            val left = (srcW - newWidth) / 2
            Rect(left, 0, left + newWidth, srcH)
        } else {
            val newHeight = (srcW / targetRatio).toInt()
            val top = (srcH - newHeight) / 2
            Rect(0, top, srcW, top + newHeight)
        }
    }

    /**
     * Saves bitmap as PNG to the user's Gallery (Pictures/CleanCut).
     */
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Result<Uri> {
        return try {
            val filename = "CleanCut_${System.currentTimeMillis()}.png"
            val resolver = context.contentResolver

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CleanCut")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("Не вдалося створити запис у сховищі MediaStore")

            resolver.openOutputStream(imageUri)?.let { os ->
                BufferedOutputStream(os, 32 * 1024).use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IllegalStateException("Помилка запису PNG у потік даних")
                    }
                }
            } ?: throw IllegalStateException("Помилка відкриття вихідного потоку MediaStore")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }

            Result.success(imageUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Saves bitmap to app cache for sharing via Android Sharesheet.
     */
    fun saveBitmapForSharing(context: Context, bitmap: Bitmap): Uri {
        val imagesFolder = File(context.cacheDir, "images").apply { mkdirs() }
        val shareFile = File(imagesFolder, "cleancut_share_${System.currentTimeMillis()}.png")

        BufferedOutputStream(FileOutputStream(shareFile), 32 * 1024).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shareFile
        )
    }
}
