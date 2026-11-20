package com.anas.secretary.service

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.anas.secretary.util.Notifs
import com.anas.secretary.util.Prefs

/** Watches the photo library and asks "add this?" when a new screenshot appears. */
class ScreenshotWatcher(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val seen = LinkedHashSet<Long>()

    private val check = Runnable {
        try {
            checkLatest()
        } catch (e: Exception) {
            Log.w("Secretary", "Screenshot check failed", e)
        }
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // Screenshots are written in steps; wait a moment for the file to settle.
            handler.removeCallbacks(check)
            handler.postDelayed(check, 1500)
        }
    }

    fun start() {
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    fun stop() {
        handler.removeCallbacks(check)
        try { ctx.contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {}
    }

    private fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun checkLatest() {
        if (!hasPermission() || !Prefs.screenshotPrompts) return
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME, pathCol
        )
        val selection = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.IS_PENDING}=0" else null
        val nowSec = System.currentTimeMillis() / 1000

        ctx.contentResolver.query(base, proj, selection, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            var looked = 0
            while (c.moveToNext() && looked < 3) {
                looked++
                val id = c.getLong(0)
                val added = c.getLong(1)
                val name = c.getString(2) ?: ""
                val path = c.getString(3) ?: ""
                if (added < nowSec - 30) break
                if (id in seen) continue
                val isScreenshot = name.contains("screenshot", ignoreCase = true) ||
                    path.contains("screenshot", ignoreCase = true)
                if (!isScreenshot) continue
                seen += id
                if (seen.size > 50) seen.remove(seen.first())
                if (com.anas.secretary.util.Features.saved) Notifs.showScreenshotPrompt(ctx, ContentUris.withAppendedId(base, id))
            }
        }
    }
}
