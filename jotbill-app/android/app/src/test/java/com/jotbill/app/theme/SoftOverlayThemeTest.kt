package com.jotbill.app.theme

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftOverlayThemeTest {
    @Test
    fun `主题名称仅在 soft 时启用柔光悬浮窗`() {
        assertTrue(ThemePreferences.isSoftThemeName("soft"))
        assertTrue(ThemePreferences.isSoftThemeName(" SOFT "))
        assertFalse(ThemePreferences.isSoftThemeName("blocks"))
        assertFalse(ThemePreferences.isSoftThemeName("mint"))
        assertFalse(ThemePreferences.isSoftThemeName(""))
        assertFalse(ThemePreferences.isSoftThemeName(null))
    }

    @Test
    fun `浅色柔光悬浮窗无粗描边且主按钮配白字`() {
        val p = SoftOverlayTheme.paletteFor(isDark = false)
        assertFalse(p.hasCardBorder)
        assertEquals(0xFFFFFFFF.toInt(), p.surface)
        assertEquals(0xFF4F46E5.toInt(), p.primary)
        assertEquals(0xFFFFFFFF.toInt(), p.onPrimary)
        assertEquals(0xFFDC2626.toInt(), p.expense)
    }

    @Test
    fun `深色柔光悬浮窗启用发丝边且主按钮配深炭字保证对比度`() {
        val p = SoftOverlayTheme.paletteFor(isDark = true)
        assertTrue(p.hasCardBorder)
        assertEquals(0xFF171A21.toInt(), p.surface)
        assertEquals(0xFF8B8DFB.toInt(), p.primary)
        assertEquals(0xFF0E1014.toInt(), p.onPrimary)
        assertEquals(0xFFF87171.toInt(), p.expense)
    }

    @Test
    fun `触感常量按 API 版本与强度正确映射`() {
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            ThemeModule.resolveHapticConstant("light", sdkInt = 33),
        )
        assertEquals(
            HapticFeedbackConstants.KEYBOARD_TAP,
            ThemeModule.resolveHapticConstant("medium", sdkInt = 33),
        )
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            ThemeModule.resolveHapticConstant("success", sdkInt = 30),
        )
        assertEquals(
            HapticFeedbackConstants.KEYBOARD_TAP,
            ThemeModule.resolveHapticConstant("success", sdkInt = 29),
        )
    }
}
