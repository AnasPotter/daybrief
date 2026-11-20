package com.anas.secretary.logic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.anas.secretary.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Loads a page in an invisible browser and reads its title and picture. */
object WebPreview {
    class Result(val title: String?, val image: String?, val screenshot: String?)

    private const val JS = """(function(){
      function m(p){var e=document.querySelector('meta[property="'+p+'"],meta[name="'+p+'"]');return e?e.content:null}
      var v=document.querySelector('video');
      var img=m('og:image')||m('twitter:image')||(v&&v.poster)||null;
      if(!img){var all=[].slice.call(document.images).filter(function(i){return i.naturalWidth>300&&i.naturalHeight>200&&/^https?:/.test(i.src)});
        all.sort(function(a,b){return b.naturalWidth*b.naturalHeight-a.naturalWidth*a.naturalHeight});if(all.length)img=all[0].src}
      return JSON.stringify({t:m('og:title')||m('twitter:title')||m('og:description')||document.title||null,i:img});
    })()"""

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(ctx: Context, url: String, log: StringBuilder): Result? = withContext(Dispatchers.Main) {
        val r = withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine<Result?> { cont ->
                val wv = WebView(ctx.applicationContext)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.loadsImagesAutomatically = true
                wv.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                val w = 1080; val h = 1600
                wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                wv.layout(0, 0, w, h)
                var finished = false
                fun finish(res: Result?) {
                    if (finished) return
                    finished = true
                    try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(res)
                }
                // The WebView is never on screen, so view.postDelayed() would never run: use the main thread instead
                val main = Handler(Looper.getMainLooper())
                var polling = false
                // TikTok/Instagram build the page with scripts: check every 1.5 s until a picture shows up
                fun poll(view: WebView, triesLeft: Int) {
                    if (finished) return
                    view.evaluateJavascript(JS) { raw ->
                        try {
                            val json = JSONObject(JSONTokener(raw).nextValue() as String)
                            val t = json.optString("t").ifBlank { null }?.takeUnless { it == "null" }
                            val i = json.optString("i").ifBlank { null }?.takeUnless { it == "null" }
                            if (i == null && triesLeft > 0) {
                                main.postDelayed({ poll(view, triesLeft - 1) }, 1500)
                                return@evaluateJavascript
                            }
                            val shot = if (i == null) snapshot(ctx, view, w, h) else null
                            log.append("browser: title=").append(t != null).append(" image=").append(i != null).append("; ")
                            finish(Result(t, i, shot))
                        } catch (e: Exception) {
                            log.append("browser read: ").append(e.message).append("; ")
                            finish(null)
                        }
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        if (polling || finished) return
                        polling = true
                        main.postDelayed({ poll(view, 10) }, 1500)
                    }
                }
                cont.invokeOnCancellation { finished = true; try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {} }
                wv.loadUrl(url)
            }
        }
        if (r == null) log.append("browser: timed out; ")
        r
    }

    /** A picture of the page itself, used only if the page has no preview image. Blank pages are skipped. */
    private fun snapshot(ctx: Context, view: WebView, w: Int, h: Int): String? = try {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val first = bmp.getPixel(w / 2, h / 4)
        val varied = listOf(w / 4 to h / 3, w / 2 to h / 2, 3 * w / 4 to 2 * h / 3, w / 3 to 3 * h / 4).any { (x, y) -> bmp.getPixel(x, y) != first }
        if (!varied) null else {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Images.saveBytes(ctx, out.toByteArray(), 720)
        }
    } catch (_: Exception) { null }
}
