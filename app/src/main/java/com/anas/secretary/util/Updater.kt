package com.anas.secretary.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Your GitHub access key, locked with a key that never leaves this phone's secure hardware. */
object Secret {
    private const val ALIAS = "secretary_github_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    fun put(value: String) {
        if (value.isBlank()) { Prefs.tokenBlob = ""; return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(value.trim().toByteArray())
        Prefs.tokenBlob = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
    }

    fun get(): String? {
        val blob = Prefs.tokenBlob
        if (blob.isBlank()) return null
        return try {
            val (iv, data) = blob.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data))
        } catch (_: Exception) { null }
    }

    val isSet: Boolean get() = Prefs.tokenBlob.isNotBlank()
}

data class Release(val build: Int, val assetUrl: String, val size: Long)

/** Checks your GitHub repository for a newer build and installs it. */
object Updater {
    /** Set when a newer build is waiting (shown in the Brief). */
    var available by mutableStateOf<Release?>(null)

    fun currentBuild(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
        return (vc - 100).coerceAtLeast(0)
    }

    private fun open(start: String, token: String?, accept: String): HttpURLConnection {
        var url = start
        var auth = token != null
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("User-Agent", "Secretary-App")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (auth) c.setRequestProperty("Authorization", "Bearer $token")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Bad redirect")
                c.disconnect()
                url = URL(URL(url), loc).toString()
                auth = token != null && URL(url).host == "api.github.com" // never send your key to the download server
                return@repeat
            }
            return c
        }
        throw IOException("Too many redirects")
    }

    /** Returns the newer release, null if you're up to date. Throws with a readable message on problems. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        val repo = Prefs.updateRepo.trim().removePrefix("https://github.com/").trim('/')
        // The public app reads a public repository: no key needed
        val token = if (com.anas.secretary.BuildConfig.PUBLIC) null else Secret.get() ?: throw IOException("Add your GitHub access key first")
        if (!repo.contains('/')) throw IOException("Check the repository name (like owner/repository)")
        val c = open("https://api.github.com/repos/$repo/releases/latest", token, "application/vnd.github+json")
        try {
            when (c.responseCode) {
                401, 403 -> throw IOException(if (token == null) "GitHub is busy. Try again in a while." else "GitHub refused the access key. Make a new one and paste it again.")
                404 -> throw IOException(if (token == null) "No published version yet." else "No published build yet.")
            }
            if (c.responseCode !in 200..299) throw IOException("GitHub error ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val build = Regex("\\d+").findAll(json.optString("tag_name")).lastOrNull()?.value?.toIntOrNull() ?: 0
            val assets = json.optJSONArray("assets")
            var asset: JSONObject? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { asset = a; break }
            }
            Prefs.lastUpdateCheck = System.currentTimeMillis()
            val a = asset ?: throw IOException("The latest build has no app file attached")
            val r = Release(build, a.getString("url"), a.optLong("size"))
            val newer = if (build > currentBuild(ctx)) r else null
            withContext(Dispatchers.Main) { available = newer }
            newer
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the new app file, then asks Android to install it over the current one (your data stays). */
    suspend fun install(ctx: Context, r: Release, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val token = if (com.anas.secretary.BuildConfig.PUBLIC) null else Secret.get() ?: throw IOException("Add your GitHub access key first")
        val file = File(ctx.cacheDir, "update.apk")
        val c = open(r.assetUrl, token, "application/octet-stream")
        try {
            if (c.responseCode !in 200..299) throw IOException("Download failed (${c.responseCode})")
            val total = if (r.size > 0) r.size else c.contentLengthLong
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 200_000) {
                            lastReport = done
                            val f = done.toFloat() / total
                            withContext(Dispatchers.Main) { onProgress(f) }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("secretary.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }
}

/** Android reports back here; it shows the "Update this app?" screen. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(it) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.available = null
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"
                Toast.makeText(ctx, "Update didn't install: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
