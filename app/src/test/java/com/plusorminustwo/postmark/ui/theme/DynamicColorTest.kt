package com.plusorminustwo.postmark.ui.theme

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicColorTest {

    @Test
    fun `disabled never uses dynamic color regardless of SDK level`() {
        assertFalse(shouldUseDynamicColor(enabled = false, sdkInt = Build.VERSION_CODES.S))
        assertFalse(shouldUseDynamicColor(enabled = false, sdkInt = Int.MAX_VALUE))
    }

    @Test
    fun `enabled below API 31 does not use dynamic color`() {
        assertFalse(shouldUseDynamicColor(enabled = true, sdkInt = Build.VERSION_CODES.S - 1))
    }

    @Test
    fun `enabled at API 31 uses dynamic color`() {
        assertTrue(shouldUseDynamicColor(enabled = true, sdkInt = Build.VERSION_CODES.S))
    }

    @Test
    fun `enabled above API 31 uses dynamic color`() {
        assertTrue(shouldUseDynamicColor(enabled = true, sdkInt = Build.VERSION_CODES.S + 5))
    }
}
