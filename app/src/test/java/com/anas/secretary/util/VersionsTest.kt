package com.anas.secretary.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun smallAndBigUpdates() {
        assertTrue(Versions.newer("1.0.1", "1.0"))
        assertTrue(Versions.newer("1.1", "1.0.1"))
        assertTrue(Versions.newer("v1.0.10", "1.0.9"))
        assertTrue(Versions.newer("2.0", "1.9.9"))
    }

    @Test
    fun sameOrOlderIsNotAnUpdate() {
        assertFalse(Versions.newer("1.0", "1.0"))
        assertFalse(Versions.newer("1.0.0", "1.0"))
        assertFalse(Versions.newer("1.0.1", "1.1"))
        assertFalse(Versions.newer("1.0", "1.0.1"))
    }
}
