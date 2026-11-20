package com.anas.secretary.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Background work that should outlive a screen (e.g. finishing a link preview after Save). */
object Bg {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Stores small JPEG copies of previews inside the app's private storage. */
object Images {
    private fun dir(ctx: Context) = File(ctx.filesDir, "previews").apply { mkdirs() }

    private fun sampleSize(width: Int, maxW: Int): Int {
        var s = 1
        while (width / (s * 2) >= maxW) s *= 2
        return s
    }

    /** Decodes JPEG/PNG/WebP, and HEIC/AVIF on newer phones, scaled down to about maxW wide. */
    fun decode(bytes: ByteArray, maxW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0) {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, maxW) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            return try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.setTargetSampleSize(sampleSize(info.size.width, maxW))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) { null }
        }
        return null
    }

    fun saveBytes(ctx: Context, bytes: ByteArray, maxW: Int = 800): String? {
        val bmp = decode(bytes, maxW) ?: return null
        val file = File(dir(ctx), UUID.randomUUID().toString() + ".jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return file.absolutePath
    }

    /** Copies a screenshot/shared image into app storage, so it survives if the original is deleted. */
    fun saveUri(ctx: Context, uri: Uri, maxW: Int = 900): String? {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            saveBytes(ctx, bytes, maxW)
        } catch (e: Exception) {
            null
        }
    }

    /** Small bitmap of a picture on the phone (for the notification preview). */
    fun thumbnail(ctx: Context, uri: Uri, maxW: Int = 540): Bitmap? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { decode(it, maxW) }
    } catch (_: Exception) { null }

    fun delete(path: String?) {
        if (path == null) return
        try { File(path).delete() } catch (_: Exception) {}
    }
}
