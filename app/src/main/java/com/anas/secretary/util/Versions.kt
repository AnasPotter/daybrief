package com.anas.secretary.util

/** Compares version names like "1.0.1" and "1.1" part by part (missing parts count as 0). */
object Versions {
    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** true when [candidate] is a later version than [current]. */
    fun newer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
