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
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class ProcessedResult(
    val bytes: ByteArray,
    val previewBitmap: Bitmap,
    val finalSizeKb: Float,
    val width: Int,
    val height: Int,
    val faceDetected: Boolean = false
)

object ExactImageProcessor {

    fun createCameraCaptureUri(context: Context): Uri {
        val captureDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(captureDir, "camera_raw_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * 1. ML Kit Face Detection: auto-crops source around the detected face so head & shoulders
     *    match official passport framing (~75% coverage) before final resize.
     */
    suspend fun autoCenterFace(source: Bitmap): Pair<Bitmap, Boolean> = withContext(Dispatchers.Default) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
        val detector = FaceDetection.getClient(options)
        val inputImage = InputImage.fromBitmap(source, 0)

        val faces = runCatching { detector.process(inputImage).await() }.getOrDefault(emptyList())
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return@withContext (source to false)

        val box = face.boundingBox
        val faceCenterX = box.exactCenterX()
        // Shift slightly below eye/nose center to include collar/shoulders naturally
        val passportCenterY = box.exactCenterY() + (box.height() * 0.14f)

        // Target crop height = ~2.1x face height so face fills ~50-75% of passport frame
        val desiredCropH = (box.height() * 2.15f).coerceIn(box.height().toFloat(), source.height.toFloat())
        val desiredCropW = desiredCropH * 0.85f

        val halfW = desiredCropW / 2f
        val halfH = desiredCropH / 2f

        val left = (faceCenterX - halfW).coerceIn(0f, max(0f, source.width - desiredCropW)).roundToInt()
        val top = (passportCenterY - halfH).coerceIn(0f, max(0f, source.height - desiredCropH)).roundToInt()
        val cropW = desiredCropW.roundToInt().coerceIn(1, source.width - left)
        val cropH = desiredCropH.roundToInt().coerceIn(1, source.height - top)

        val cropped = Bitmap.createBitmap(source, left, top, cropW, cropH)
        (cropped to true)
    }

    /**
     * 2. AI Studio White Background Replacement using on-device ML Kit Subject Segmentation
     */
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

    /**
     * 3. Signature High-Contrast Shadow Cleaner
     */
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
     * 4. Framed Crop & Scale with interactive Zoom & Vertical Alignment
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

    /**
     * 5. Candidate Name & Date of Photo (DOP) Strip Stamper
     */
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

    /**
     * 6. Patches JFIF APP0 header to report exact 300 DPI (required by PAN / Passport portals)
     */
    private fun setJpegDpi(jpegBytes: ByteArray, dpi: Int = 300): ByteArray {
        if (jpegBytes.size > 18 &&
            jpegBytes[0] == 0xFF.toByte() && jpegBytes[1] == 0xD8.toByte() &&
            jpegBytes[2] == 0xFF.toByte() && jpegBytes[3] == 0xE0.toByte() &&
            jpegBytes[6] == 'J'.code.toByte() && jpegBytes[7] == 'F'.code.toByte()
        ) {
            val copy = jpegBytes.copyOf()
            copy[13] = 1 // Units: dots per inch
            copy[14] = ((dpi shr 8) and 0xFF).toByte()
            copy[15] = (dpi and 0xFF).toByte()
            copy[16] = ((dpi shr 8) and 0xFF).toByte()
            copy[17] = (dpi and 0xFF).toByte()
            return copy
        }
        return jpegBytes
    }

    /**
     * 7. Binary-Search Exact KB Compressor + 300 DPI Header Stamp
     */
    fun compressToExactRange(
        bitmap: Bitmap,
        minKb: Int,
        maxKb: Int,
        faceDetected: Boolean = false
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

        finalBytes = setJpegDpi(finalBytes, 300)

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
            height = bitmap.height,
            faceDetected = faceDetected
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