package com.maheshgkraj.portalready.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

data class PdfSourcePage(
    val id: String,
    val label: String,
    val bitmap: Bitmap
)

data class MergedPdfResult(
    val bytes: ByteArray,
    val pageCount: Int,
    val finalSizeKb: Float,
    val withinCap: Boolean
)

object SizeCappedPdfMerger {

    /**
     * Expands any selected URIs (Images or multi-page PDFs) into high-res preview Bitmaps
     */
    suspend fun extractPagesFromUris(context: Context, uris: List<Uri>): List<PdfSourcePage> = withContext(Dispatchers.IO) {
        val pages = mutableListOf<PdfSourcePage>()
        val resolver = context.contentResolver

        uris.forEachIndexed { uriIndex, uri ->
            val mime = resolver.getType(uri) ?: ""
            if (mime == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")) {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        for (pageIdx in 0 until renderer.pageCount) {
                            renderer.openPage(pageIdx).use { page ->
                                // Render at ~150 DPI (A4 ~ 1240x1754)
                                val scale = 2.0f
                                val w = (page.width * scale).roundToInt().coerceAtMost(1654)
                                val h = (page.height * scale).roundToInt().coerceAtMost(2339)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                Canvas(bmp).drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pages.add(
                                    PdfSourcePage(
                                        id = "${uriIndex}_p${pageIdx}_${System.nanoTime()}",
                                        label = "PDF Doc ${uriIndex + 1} • Page ${pageIdx + 1}",
                                        bitmap = bmp
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                val rawBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(resolver, uri)
                }
                pages.add(
                    PdfSourcePage(
                        id = "${uriIndex}_img_${System.nanoTime()}",
                        label = "Document Image ${uriIndex + 1}",
                        bitmap = rawBmp
                    )
                )
            }
        }
        pages
    }

    /**
     * Builds an A4 multi-page PDF strictly under [maxTargetKb] using adaptive DPI + JPEG quality search
     */
    suspend fun buildCappedPdf(
        pages: List<PdfSourcePage>,
        maxTargetKb: Int,
        enhanceReadability: Boolean = true
    ): MergedPdfResult = withContext(Dispatchers.Default) {
        val maxBytes = maxTargetKb * 1024L
        // Try progressively smaller A4 pixel resolutions if page count is very high
        val dpiDimensions = listOf(
            1240 to 1754, // ~150 DPI A4
            992 to 1403,  // ~120 DPI A4
            794 to 1123,  // ~96 DPI A4
            620 to 877    // ~75 DPI A4 (handles 15-20 pages under 1MB)
        )

        var bestBytes: ByteArray? = null

        for ((a4Width, a4Height) in dpiDimensions) {
            var lowQ = 22
            var highQ = 88
            var tierBest: ByteArray? = null

            while (lowQ <= highQ) {
                val midQ = (lowQ + highQ) / 2
                val candidateBytes = renderPdfAtQuality(pages, a4Width, a4Height, midQ, enhanceReadability)
                if (candidateBytes.size <= maxBytes) {
                    tierBest = candidateBytes
                    lowQ = midQ + 1 // Push quality higher while staying under cap
                } else {
                    highQ = midQ - 1
                }
            }

            if (tierBest != null) {
                bestBytes = tierBest
                break
            }
            // Keep lowest possible fallback from smallest tier if even 75 DPI is slightly over
            if (a4Width == dpiDimensions.last().first) {
                bestBytes = renderPdfAtQuality(pages, a4Width, a4Height, 20, enhanceReadability)
            }
        }

        val finalBytes = bestBytes ?: ByteArray(0)
        MergedPdfResult(
            bytes = finalBytes,
            pageCount = pages.size,
            finalSizeKb = finalBytes.size / 1024f,
            withinCap = finalBytes.size <= maxBytes
        )
    }

    private fun renderPdfAtQuality(
        pages: List<PdfSourcePage>,
        pageWidth: Int,
        pageHeight: Int,
        jpegQuality: Int,
        enhanceContrast: Boolean
    ): ByteArray {
        val pdfDocument = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (enhanceContrast) {
            // Subtle contrast & brightness boost so scanned marks cards stay crisp at lower JPEG quality
            val cm = ColorMatrix(
                floatArrayOf(
                    1.12f, 0f, 0f, 0f, -10f,
                    0f, 1.12f, 0f, 0f, -10f,
                    0f, 0f, 1.12f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            paint.colorFilter = ColorMatrixColorFilter(cm)
        }

        pages.forEachIndexed { index, pageItem ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
            val pdfPage = pdfDocument.startPage(pageInfo)
            val canvas = pdfPage.canvas
            canvas.drawColor(Color.WHITE)

            // Fit source image inside A4 with a clean 24px margin
            val margin = (pageWidth * 0.02f).roundToInt()
            val availW = pageWidth - (margin * 2)
            val availH = pageHeight - (margin * 2)

            val src = pageItem.bitmap
            val scale = min(availW.toFloat() / src.width, availH.toFloat() / src.height)
            val drawW = (src.width * scale).roundToInt().coerceAtLeast(1)
            val drawH = (src.height * scale).roundToInt().coerceAtLeast(1)

            // Re-compress page through JPEG buffer so PdfDocument embeds compact DCT stream
            val scaledBmp = Bitmap.createScaledBitmap(src, drawW, drawH, true)
            val jpegStream = ByteArrayOutputStream()
            scaledBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegStream)
            val compressedBytes = jpegStream.toByteArray()
            val compactBmp = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size) ?: scaledBmp

            val left = (pageWidth - drawW) / 2f
            val top = (pageHeight - drawH) / 2f
            canvas.drawBitmap(compactBmp, left, top, paint)

            pdfDocument.finishPage(pdfPage)
        }

        val outStream = ByteArrayOutputStream()
        pdfDocument.writeTo(outStream)
        pdfDocument.close()
        return outStream.toByteArray()
    }

    fun savePdfToDocuments(context: Context, bytes: ByteArray, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/PortalReady")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        return uri
    }
}