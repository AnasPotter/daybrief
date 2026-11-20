package com.anas.secretary.util

import android.content.Context
import android.net.Uri
import com.anas.secretary.data.DB_NAME
import com.anas.secretary.data.Repo
import com.anas.secretary.reminders.Reminders
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted backup. Everything is locked with YOUR password (AES-256-GCM) on the phone
 * before it is written, so Google Drive / any cloud only ever stores unreadable data.
 * Without the password nobody — including Google, Samsung or me — can open it.
 */
object Backup {
    private val MAGIC = "SECBAK1".toByteArray()
    private const val ITERATIONS = 210_000

    class WrongPassword : Exception()

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun export(ctx: Context, password: CharArray, target: Uri) {
        Repo.checkpoint()
        // 1) Pack database + preview images into a zip (in memory)
        val zipBytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zip ->
                val dbFile = ctx.getDatabasePath(DB_NAME)
                zip.putNextEntry(ZipEntry("db/$DB_NAME"))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                File(ctx.filesDir, "previews").listFiles()?.forEach { f ->
                    zip.putNextEntry(ZipEntry("previews/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        // 2) Encrypt
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(zipBytes)

        // 3) Write: MAGIC | salt | iv | data
        ctx.contentResolver.openOutputStream(target, "wt")?.use { out ->
            out.write(MAGIC); out.write(salt); out.write(iv); out.write(encrypted)
        } ?: throw IllegalStateException("Couldn't write the backup file")
        Prefs.lastBackup = System.currentTimeMillis()
    }

    fun restore(ctx: Context, password: CharArray, source: Uri) {
        val all = ctx.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: throw IllegalStateException("Couldn't read the backup file")
        if (all.size < MAGIC.size + 28 || !all.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IllegalArgumentException("This isn't a Daybrief backup file")
        }
        var pos = MAGIC.size
        val salt = all.copyOfRange(pos, pos + 16); pos += 16
        val iv = all.copyOfRange(pos, pos + 12); pos += 12
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val zipBytes = try {
            cipher.doFinal(all, pos, all.size - pos)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        }

        // Replace current data
        Repo.close()
        val dbFile = ctx.getDatabasePath(DB_NAME)
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        val previews = File(ctx.filesDir, "previews").apply { mkdirs() }
        previews.listFiles()?.forEach { it.delete() }

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (name.isBlank() || name.contains("..")) continue
                val out = when {
                    e.name.startsWith("db/") -> dbFile
                    e.name.startsWith("previews/") -> File(previews, name)
                    else -> null
                } ?: continue
                out.outputStream().use { zip.copyTo(it) }
            }
        }
        Repo.reopen()
        Repo.upcomingReminders().forEach { t -> t.due?.let { Reminders.schedule(ctx, t.id, t.title, it) } }
    }
}
