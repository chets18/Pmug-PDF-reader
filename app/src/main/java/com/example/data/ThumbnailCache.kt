package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ThumbnailCache {

    private val memoryCache: LruCache<String, Bitmap> by lazy {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    suspend fun getPageThumbnail(
        context: Context,
        pdfFile: File,
        fileHash: String,
        pageIndex: Int = 0,
        targetWidth: Int = 600
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${fileHash}_p${pageIndex}_w$targetWidth"

        // 1. Check memory cache
        memoryCache.get(cacheKey)?.let { return@withContext it }

        // 2. Check disk cache
        val diskCacheDir = File(context.cacheDir, "pmug_thumbnails").apply { mkdirs() }
        val diskFile = File(diskCacheDir, "$cacheKey.png")
        if (diskFile.exists() && diskFile.length() > 0) {
            val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
            if (bitmap != null) {
                memoryCache.put(cacheKey, bitmap)
                return@withContext bitmap
            }
        }

        // 3. Render page using PdfRenderer
        val rendered = renderPageBitmap(pdfFile, pageIndex, targetWidth)
        if (rendered != null) {
            try {
                FileOutputStream(diskFile).use { out ->
                    rendered.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            } catch (_: Exception) {}
            memoryCache.put(cacheKey, rendered)
        }
        return@withContext rendered
    }

    fun getPdfPageCount(pdfFile: File): Int {
        if (!pdfFile.exists() || pdfFile.length() == 0L) return 1
        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount.coerceAtLeast(1)
                }
            }
        } catch (_: Exception) {
            1
        }
    }

    private fun renderPageBitmap(pdfFile: File, pageIndex: Int, targetWidth: Int): Bitmap? {
        if (!pdfFile.exists() || pdfFile.length() == 0L) return null
        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { page ->
                        val aspectRatio = page.height.toFloat() / page.width.toFloat()
                        val targetHeight = (targetWidth * aspectRatio).toInt().coerceAtLeast(100)

                        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        // Fill crisp off-white background so transparent PDF pages render beautifully
                        canvas.drawColor(Color.WHITE)

                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
