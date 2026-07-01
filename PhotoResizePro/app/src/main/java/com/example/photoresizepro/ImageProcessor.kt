package com.example.photoresizepro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Core image processing engine for Photo Resize Pro.
 * Handles memory-safe decoding, aspect-ratio aware resizing,
 * white-background flattening and iterative size-bounded compression.
 */
object ImageProcessor {

    data class ProcessResult(
        val bitmap: Bitmap,
        val bytes: ByteArray,
        val quality: Int,
        val format: Bitmap.CompressFormat
    )

    data class ImageInfo(
        val width: Int,
        val height: Int,
        val sizeBytes: Long
    )

    /**
     * Reads width/height and file size of the source image without loading full pixels.
     */
    fun readImageInfo(context: Context, uri: Uri): ImageInfo? {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, options)
            }
            val size = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() }
                ?: getFileSizeFallback(context, uri)
            ImageInfo(options.outWidth, options.outHeight, size)
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileSizeFallback(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Efficiently decode a (possibly huge) image from Uri, downsampled close to
     * the requested target dimensions to avoid OOM on large photos.
     */
    fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val boundsOptions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, boundsOptions)
        } ?: return null

        val sampleSize = calculateInSampleSize(boundsOptions, reqWidth, reqHeight)

        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { stream: InputStream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        return applyExifRotation(context, uri, decoded)
    }

    private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val exifStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(exifStream)
            exifStream.close()
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * Resizes [src] to exactly [targetW] x [targetH].
     * If [maintainAspect] is true, the source is scaled to fit within the target box
     * (letterboxed) and centered on a background canvas (white or transparent).
     * If false, the source is stretched to fill the exact target dimensions.
     */
    fun resizeWithAspectRatio(
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        maintainAspect: Boolean,
        whiteBackground: Boolean
    ): Bitmap {
        val safeW = targetW.coerceAtLeast(1)
        val safeH = targetH.coerceAtLeast(1)

        val output = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Fill background
        if (whiteBackground) {
            canvas.drawColor(Color.WHITE)
        } else {
            canvas.drawColor(Color.TRANSPARENT)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

        if (maintainAspect) {
            val srcRatio = src.width.toFloat() / src.height.toFloat()
            val targetRatio = safeW.toFloat() / safeH.toFloat()

            var drawW = safeW
            var drawH = safeH
            if (srcRatio > targetRatio) {
                // source is wider -> fit width
                drawW = safeW
                drawH = (safeW / srcRatio).toInt().coerceAtLeast(1)
            } else {
                // source is taller -> fit height
                drawH = safeH
                drawW = (safeH * srcRatio).toInt().coerceAtLeast(1)
            }

            val scaled = Bitmap.createScaledBitmap(src, drawW, drawH, true)
            val left = (safeW - drawW) / 2f
            val top = (safeH - drawH) / 2f
            canvas.drawBitmap(scaled, left, top, paint)
            if (scaled != src) scaled.recycle()
        } else {
            // Stretch to exactly fill target box (high quality scaling via matrix)
            val matrix = Matrix()
            matrix.setScale(safeW.toFloat() / src.width, safeH.toFloat() / src.height)
            canvas.drawBitmap(src, matrix, paint)
        }

        return output
    }

    /**
     * If the bitmap has transparency and whiteBackground flattening is requested,
     * composite it onto a pure white canvas of the same size (no resizing).
     */
    fun flattenToWhite(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return output
    }

    /**
     * Compresses [bitmap] iteratively, reducing quality (and if necessary, dimensions)
     * until the encoded byte size is <= [maxBytes], or a safety floor is reached.
     * Returns the final bytes and the quality level used.
     * Only meaningful for lossy formats (JPEG/WEBP); PNG ignores the quality loop
     * beyond a single pass since PNG is lossless (quality param affects compression
     * effort only, not visual quality).
     */
    fun compressToTargetSize(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        maxBytes: Int,
        initialQuality: Int
    ): Pair<ByteArray, Int> {
        // PNG is lossless: quality has no effect on visual fidelity, so the only lever
        // available to hit a byte budget is progressive downscaling.
        if (format == Bitmap.CompressFormat.PNG) {
            var pngBytes = encode(bitmap, Bitmap.CompressFormat.PNG, 100)
            if (pngBytes.size <= maxBytes) return Pair(pngBytes, 100)

            var workingBitmap = bitmap
            var scale = 0.9f
            var attempts = 0
            while (pngBytes.size > maxBytes && attempts < 15) {
                val newW = (bitmap.width * scale).toInt().coerceAtLeast(16)
                val newH = (bitmap.height * scale).toInt().coerceAtLeast(16)
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                pngBytes = encode(scaledBitmap, Bitmap.CompressFormat.PNG, 100)
                if (workingBitmap != bitmap) workingBitmap.recycle()
                workingBitmap = scaledBitmap
                attempts++
                scale *= 0.9f
            }
            return Pair(pngBytes, 100)
        }

        var quality = initialQuality.coerceIn(1, 100)
        var bytes = encode(bitmap, format, quality)

        // Phase 1: binary-search-ish descent on quality
        var low = 1
        var high = quality
        var bestBytes = bytes
        var bestQuality = quality

        if (bytes.size <= maxBytes) {
            // Already within budget at requested quality — try to push quality up a bit
            // is unnecessary per spec (spec asks to reduce, not increase), so just return.
            return Pair(bytes, quality)
        }

        // Binary search for the highest quality that fits within maxBytes
        while (low <= high) {
            val mid = (low + high) / 2
            val encoded = encode(bitmap, format, mid)
            if (encoded.size <= maxBytes) {
                bestBytes = encoded
                bestQuality = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        if (bestBytes.size <= maxBytes) {
            return Pair(bestBytes, bestQuality)
        }

        // Phase 2: even minimum quality (1) doesn't fit -> progressively downscale
        var workingBitmap = bitmap
        var scale = 0.9f
        var attempts = 0
        while (bestBytes.size > maxBytes && attempts < 12) {
            val newW = (workingBitmap.width * scale).toInt().coerceAtLeast(16)
            val newH = (workingBitmap.height * scale).toInt().coerceAtLeast(16)
            val scaledBitmap = Bitmap.createScaledBitmap(workingBitmap, newW, newH, true)
            val encoded = encode(scaledBitmap, format, 1)
            bestBytes = encoded
            bestQuality = 1
            if (workingBitmap != bitmap) workingBitmap.recycle()
            workingBitmap = scaledBitmap
            attempts++
            if (bestBytes.size <= maxBytes) break
            scale *= 0.9f
        }

        return Pair(bestBytes, bestQuality)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
