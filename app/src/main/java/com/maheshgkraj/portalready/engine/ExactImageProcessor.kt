package com.maheshgkraj.portalready.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

data class ProcessedResult(
    val bytes: ByteArray,
    val previewBitmap: Bitmap,
    val finalSizeKb: Float,
    val width: Int,
    val height: Int
)

object ExactImageProcessor {

    suspend fun applyStudioWhiteBackground(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        val inputImage = InputImage.fromBitmap(source, 0)

        val result = segmenter.process(inputImage).await()
        val foreground = result.foregroundBitmap ?: return@withContext source

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(foreground, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        output
    }

    fun cleanSignature(source: Bitmap, threshold: Int = 145): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val luma = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()
            pixels[i] = if (luma < threshold) {
                val shade = (luma * 0.4).toInt().coerceIn(0, 60)
                Color.rgb(shade, shade, shade)
            } else {
                Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Crop and scale with interactive zoom (1.0f..2.2f) and vertical alignment (-1.0f..1.0f)
     */
    fun framedCropAndScale(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        zoom: Float = 1.0f,
        verticalBias: Float = 0.0f
    ): Bitmap {
        val baseScale = max(
            targetWidth.toFloat() / source.width,
            targetHeight.toFloat() / source.height
        )
        val finalScale = baseScale * zoom.coerceIn(1.0f, 2.5f)
        val scaledWidth = finalScale * source.width
        val scaledHeight = finalScale * source.height

        val left = (targetWidth - scaledWidth) / 2f
        val maxShiftY = max(0f, (scaledHeight - targetHeight) / 2f)
        val centerTop = (targetHeight - scaledHeight) / 2f
        val top = centerTop + (verticalBias.coerceIn(-1f, 1f) * maxShiftY)

        val destRect = RectF(left, top, left + scaledWidth, top + scaledHeight)
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return output
    }

    fun stampNameAndDate(photo: Bitmap, candidateName: String, dateText: String): Bitmap {
        if (candidateName.isBlank() && dateText.isBlank()) return photo

        val stripHeight = (photo.height * 0.18f).toInt().coerceAtLeast(36)
        val photoAreaHeight = photo.height - stripHeight

        val result = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val topRect = Rect(0, 0, photo.width, photoAreaHeight)
        canvas.drawBitmap(photo, null, topRect, Paint(Paint.FILTER_BITMAP_FLAG))

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.5f
        }
        canvas.drawLine(0f, photoAreaHeight.toFloat(), photo.width.toFloat(), photoAreaHeight.toFloat(), linePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (stripHeight * 0.36f).coerceIn(11f, 24f)
        }

        val centerX = photo.width / 2f
        val line1Y = photoAreaHeight + (stripHeight * 0.44f)
        val line2Y = photoAreaHeight + (stripHeight * 0.84f)

        if (candidateName.isNotBlank()) {
            canvas.drawText(candidateName.uppercase(), centerX, line1Y, textPaint)
        }
        if (dateText.isNotBlank()) {
            canvas.drawText("DOP: $dateText", centerX, line2Y, textPaint)
        }
        return result
    }

    fun compressToExactRange(
        bitmap: Bitmap,
        minKb: Int,
        maxKb: Int
    ): ProcessedResult {
        var low = 5
        var high = 100
        var bestBytes: ByteArray? = null

        while (low <= high) {
            val mid = (low + high) / 2
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, mid, out)
            val bytes = out.toByteArray()
            val kb = bytes.size / 1024f

            if (kb <= maxKb) {
                bestBytes = bytes
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        var finalBytes = bestBytes ?: ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 5, it)
        }.toByteArray()

        val minBytes = minKb * 1024
        if (finalBytes.size < minBytes) {
            val padNeeded = (minBytes - finalBytes.size) + 512
            val padded = ByteArray(finalBytes.size + padNeeded)
            System.arraycopy(finalBytes, 0, padded, 0, finalBytes.size)
            finalBytes = padded
        }

        val decoded = BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size) ?: bitmap
        return ProcessedResult(
            bytes = finalBytes,
            previewBitmap = decoded,
            finalSizeKb = finalBytes.size / 1024f,
            width = bitmap.width,
            height = bitmap.height
        )
    }

    fun saveToGallery(context: Context, bytes: ByteArray, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PortalReady")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        return uri
    }

    fun shareImage(context: Context, bytes: ByteArray, fileName: String) {
        val shareDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(shareDir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PortalReady Image"))
    }
}