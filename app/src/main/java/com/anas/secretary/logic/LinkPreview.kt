package com.anas.secretary.logic

import android.content.Context
import android.net.Uri
import android.text.Html
import com.anas.secretary.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Preview(val title: String?, val site: String?, val imagePath: String?)

/** Builds a link preview (title + thumbnail) the way chat apps do. */
object LinkPreview {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val generic = setOf(
        "tiktok - make your day", "tiktok", "instagram", "login • instagram", "instagram • log in",
        "youtube", "facebook", "log in or sign up to view", "x", "twitter", "snapchat", "pinterest"
    )

    /** Titles like "TikTok - Make Your Day" say nothing about the post. */
    fun isGeneric(title: String?): Boolean = title == null || title.trim().lowercase() in generic

    private val reMeta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val reAttr = Regex("([a-zA-Z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val reTitle = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)

    /** What happened during the last preview attempt (shown when you tap "Retry preview"). */
    @Volatile var lastLog: String = ""

    fun domain(url: String?): String =
        url?.let { Uri.parse(it).host }?.removePrefix("www.")?.removePrefix("m.").orEmpty()

    suspend fun fetch(ctx: Context, url: String): Preview? = withContext(Dispatchers.IO) {
        var title: String? = null
        var imageUrl: String? = null
        var site: String? = null
        val log = StringBuilder()

        // Short links (vt.tiktok.com, youtu.be…) → the real address
        val real = resolveAny(url, log)
        val realHost = domain(real)
        log.append("open: ").append(realHost).append("; ")

        // Page the hidden browser opens if nothing else works (Instagram: the embed page needs no login)
        var browserUrl = real

        // 1) Official preview APIs (most reliable)
        when {
            realHost.contains("tiktok.com") -> {
                val clean = real.substringBefore('?')
                var id = Regex("""/(?:video|v|photo)/(\d{8,})""").find(clean)?.groupValues?.get(1)
                if (id == null) {
                    // Some short links load a page instead of redirecting: find the video number inside it
                    try {
                        val page = String(get(real, 900_000, DESKTOP_UA))
                        id = Regex("""(?:video|item)/(\d{15,21})""").find(page)?.groupValues?.get(1)
                            ?: Regex(""""(?:itemId|id)"\s*:\s*"(\d{15,21})"""").find(page)?.groupValues?.get(1)
                        log.append("id from page: ").append(id != null).append("; ")
                    } catch (e: Exception) { log.append("page: ").append(e.message).append("; ") }
                }
                val user = Regex("""/(@[^/?]+)/""").find(clean)?.groupValues?.get(1) ?: "@tiktok"
                val candidates = LinkedHashSet<String>().apply {
                    if (clean.contains("/video/") || clean.contains("/photo/")) add(clean)
                    if (id != null) {
                        add("https://www.tiktok.com/$user/video/$id")
                        add("https://www.tiktok.com/@tiktok/video/$id")
                    }
                    add(url.substringBefore('?')) // the short link itself
                }
                for (c in candidates) {
                    try {
                        val json = JSONObject(String(get("https://www.tiktok.com/oembed?url=" + enc(c), 200_000)))
                        title = json.optString("title").ifBlank { null } ?: json.optString("author_name").ifBlank { null }
                        imageUrl = json.optString("thumbnail_url").ifBlank { null }
                        site = "TikTok"
                        log.append("oembed ok; ")
                        if (imageUrl != null) break
                    } catch (e: Exception) { log.append("oembed: ").append(e.message).append("; ") }
                }
            }
            realHost.contains("youtube.com") || realHost.contains("youtu.be") -> {
                try {
                    val json = JSONObject(String(get("https://www.youtube.com/oembed?format=json&url=" + enc(real), 200_000)))
                    title = json.optString("title").ifBlank { null }
                    imageUrl = json.optString("thumbnail_url").ifBlank { null }
                    site = "YouTube"
                } catch (_: Exception) {}
            }
            realHost.contains("instagram.com") -> {
                // Instagram's public embed page still shows the picture without logging in
                val code = Regex("""/(p|reel|reels|tv)/([A-Za-z0-9_-]+)""").find(real)
                if (code == null) log.append("instagram: no post code; ")
                if (code != null) {
                    val kind = if (code.groupValues[1] == "reels") "reel" else code.groupValues[1]
                    val embed = "https://www.instagram.com/$kind/${code.groupValues[2]}/embed/captioned/"
                    browserUrl = embed
                    try {
                        val html = String(get(embed, 800_000))
                        imageUrl = Regex("""class="EmbeddedMediaImage"[^>]*src="([^"]+)"""").find(html)?.groupValues?.get(1)
                            ?: Regex("""<img[^>]+src="([^"]+)"[^>]*class="EmbeddedMediaImage"""").find(html)?.groupValues?.get(1)
                        val caption = Regex("""class="Caption"[^>]*>([\s\S]*?)</div>""").find(html)?.groupValues?.get(1)
                            ?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
                        title = caption?.ifBlank { null }?.take(160)
                        site = "Instagram"
                        log.append("embed: image=").append(imageUrl != null).append("; ")
                    } catch (e: Exception) { log.append("embed: ").append(e.message).append("; ") }
                }
            }
        }
        if (isGeneric(title)) title = null

        // 2) Page tags (og:title / og:image) for everything else
        if (title == null || imageUrl == null) {
            try {
                val html = String(get(real, 600_000))
                val metas = metas(html)
                title = title ?: metas["og:title"] ?: metas["twitter:title"]
                    ?: reTitle.find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }
                imageUrl = imageUrl ?: metas["og:image"] ?: metas["og:image:url"] ?: metas["twitter:image"]
                site = site ?: metas["og:site_name"]
                if (isGeneric(title)) title = null
            } catch (_: Exception) {}
        }

        var path = imageUrl?.let { img ->
            try {
                val bytes = get(absolute(real, decode(img)), 6_000_000, DESKTOP_UA, "https://$realHost/")
                Images.saveBytes(ctx, bytes).also { log.append(if (it != null) "image ok" else "image: can't decode").append("; ") }
            } catch (e: Exception) { log.append("image: ").append(e.message).append("; "); null }
        } ?: run { log.append("no image found; "); null }
        lastLog = log.toString()
        var shot: String? = null
        // 3) Still nothing? Open the page in a hidden browser (like Chrome would) and read it there.
        if (path == null) {
            val w = WebPreview.read(ctx, browserUrl, log)
            if (w != null) {
                if (title == null || isGeneric(title)) title = w.title
                val img = w.image
                if (img != null) {
                    path = try { Images.saveBytes(ctx, get(absolute(real, decode(img)), 6_000_000, DESKTOP_UA, "https://$realHost/")) } catch (e: Exception) {
                        log.append("web image: ").append(e.message).append("; "); null
                    }
                }
                if (path == null) shot = w.screenshot
                if (path == null && shot != null) { path = shot; log.append("using page snapshot; ") }
            }
        }
        lastLog = log.toString()
        val cleanTitle = title?.let { decode(it) }?.take(200)?.ifBlank { null }?.takeUnless { isGeneric(it) }
        if (cleanTitle == null && path == null) null
        else Preview(cleanTitle, site?.let { decode(it) } ?: realHost, path)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun decode(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun absolute(page: String, img: String): String = when {
        img.startsWith("http") -> img
        img.startsWith("//") -> "https:$img"
        img.startsWith("/") -> Uri.parse(page).let { "${it.scheme}://${it.host}$img" }
        else -> img
    }

    private fun metas(html: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (tag in reMeta.findAll(html)) {
            val attrs = HashMap<String, String>()
            for (a in reAttr.findAll(tag.value)) {
                attrs[a.groupValues[1].lowercase()] = a.groupValues[3].ifEmpty { a.groupValues[4] }
            }
            val key = (attrs["property"] ?: attrs["name"])?.lowercase() ?: continue
            val content = attrs["content"] ?: continue
            if (key !in out && content.isNotBlank()) out[key] = content
        }
        return out
    }

    /** Tries a few browser identities, because some sites redirect only "real" browsers. */
    private fun resolveAny(url: String, log: StringBuilder): String {
        for (ua in listOf(DESKTOP_UA, UA, "curl/8.4.0")) {
            try {
                val r = resolve(url, ua)
                if (r != url) { log.append("redirect ok; "); return r }
            } catch (e: Exception) { log.append("redirect: ").append(e.message).append("; ") }
        }
        return url
    }

    /** Follows redirects of short links and returns the final address. */
    private fun resolve(start: String, ua: String): String {
        var url = start
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", ua)
            try {
                val code = c.responseCode
                if (code !in 300..399) return url
                val loc = c.getHeaderField("Location") ?: return url
                url = URL(URL(url), loc).toString()
            } finally {
                c.disconnect()
            }
        }
        return url
    }

    /** Simple GET that follows redirects (including http → https) and caps the size. */
    private fun get(start: String, maxBytes: Int, ua: String = UA, referer: String? = null): ByteArray {
        var url = start
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept-Language", "en")
            if (referer != null) c.setRequestProperty("Referer", referer)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Bad redirect")
                    url = URL(URL(url), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total >= maxBytes) break
                    }
                    return out.toByteArray()
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}
