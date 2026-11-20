package com.anas.secretary.edge

import com.anas.secretary.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** Where and how one app glows. pos/len are fractions of the edge (0..1). */
data class EdgeApp(val pkg: String, val color: Int, val edge: String, val pos: Float, val len: Float, val thick: Int)

object EdgeConfig {
    val palette = listOf(
        0xFF25D366, 0xFFEA4335, 0xFF3E91FF, 0xFF7F77DD, 0xFFF2A516, 0xFFFF6B9A, 0xFF00BCD4, 0xFFFFFFFF
    ).map { it.toInt() }
    val edges = listOf("right" to "Right", "left" to "Left", "top" to "Top", "bottom" to "Bottom")
    val anims = listOf("breathe" to "Breathe", "pulse" to "Pulse", "comet" to "Comet", "wave" to "Wave", "aurora" to "Aurora")

    fun all(): List<EdgeApp> = try {
        val a = JSONArray(Prefs.edgeApps)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            EdgeApp(
                o.getString("pkg"), o.getInt("color"), o.optString("edge", "right"),
                o.optDouble("pos", 0.2).toFloat(), o.optDouble("len", 0.12).toFloat(), o.optInt("thick", 6)
            )
        }
    } catch (_: Exception) { emptyList() }

    fun save(list: List<EdgeApp>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("pkg", it.pkg).put("color", it.color).put("edge", it.edge)
                .put("pos", it.pos.toDouble()).put("len", it.len.toDouble()).put("thick", it.thick))
        }
        Prefs.edgeApps = a.toString()
    }

    fun forPkg(pkg: String): EdgeApp? = all().firstOrNull { it.pkg == pkg }

    /** A sensible starting spot for the next app added. */
    fun newFor(pkg: String, existing: List<EdgeApp>): EdgeApp {
        val i = existing.size
        return EdgeApp(
            pkg, palette[i % palette.size], if (i % 2 == 0) "right" else "left",
            (0.15f + 0.22f * (i / 2)).coerceAtMost(0.8f), 0.12f, 6
        )
    }
}
