package com.anas.secretary.logic

/** Whole-word match for Latin words, plain "contains" for Arabic etc. Input should be lowercase. */
fun has(text: String, word: String): Boolean =
    if (word.first() in 'a'..'z') Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(text)
    else text.contains(word)

fun hasAny(text: String, words: List<String>): Boolean = words.any { has(text, it) }

fun oneLine(s: String, max: Int = 120): String {
    val flat = s.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1) + "…"
}
