package com.anas.secretary.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test
    fun traceKeepsClassesAndLinesButNeverMessages() {
        val e = IllegalStateException("Sara: meeting at 10", RuntimeException("https://private.link/abc"))
        val t = CrashLog.trace(e)
        assertTrue(t.startsWith("java.lang.IllegalStateException"))
        assertTrue(t.contains("Caused by: java.lang.RuntimeException"))
        assertTrue(t.contains("  at "))
        assertFalse(t.contains("Sara"))
        assertFalse(t.contains("private.link"))
    }

    @Test
    fun traceStaysShort() {
        fun deep(n: Int): Nothing = if (n == 0) throw IllegalArgumentException("x") else deep(n - 1)
        val t = try { deep(200) } catch (e: Exception) { CrashLog.trace(e) }
        assertTrue(t.lines().size <= 40)
    }
}
