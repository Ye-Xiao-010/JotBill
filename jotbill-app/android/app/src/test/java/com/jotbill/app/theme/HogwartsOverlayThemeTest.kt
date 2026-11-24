package com.jotbill.app.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HogwartsOverlayThemeTest {

    @Test
    fun `resolves distinct house palettes matching RN colors ts tokens`() {
        val classic = HogwartsOverlayTheme.resolvePalette("classic")
        val gryffindor = HogwartsOverlayTheme.resolvePalette("gryffindor")
        val slytherin = HogwartsOverlayTheme.resolvePalette("slytherin")
        val ravenclaw = HogwartsOverlayTheme.resolvePalette("ravenclaw")
        val hufflepuff = HogwartsOverlayTheme.resolvePalette("hufflepuff")

        assertEquals(0xFF735318.toInt(), classic.primary)
        assertEquals(0xFF821B1F.toInt(), gryffindor.primary)
        assertEquals(0xFF195434.toInt(), slytherin.primary)
        assertEquals(0xFF1D3975.toInt(), ravenclaw.primary)
        assertEquals(0xFF7D510D.toInt(), hufflepuff.primary)

        // 所有学院共享羊皮纸基底与深褐墨色
        val all = HogwartsOverlayTheme.allPalettes()
        assertEquals(5, all.size)
        assertEquals(5, all.map { it.primary }.toSet().size)
        all.forEach { palette ->
            assertEquals(0xFFFCF7EA.toInt(), palette.cardBg)
            assertEquals(0xFF26190D.toInt(), palette.textPrimary)
            assertNotEquals(palette.primary, palette.primaryLight)
        }
    }

    @Test
    fun `falls back to classic palette for unknown, null, or uppercase house values`() {
        assertEquals("classic", HogwartsOverlayTheme.resolvePalette(null).houseKey)
        assertEquals("classic", HogwartsOverlayTheme.resolvePalette("").houseKey)
        assertEquals("classic", HogwartsOverlayTheme.resolvePalette("durmstrang").houseKey)
        assertEquals("gryffindor", HogwartsOverlayTheme.resolvePalette("GRYFFINDOR").houseKey)
        assertEquals("slytherin", HogwartsOverlayTheme.resolvePalette("Slytherin").houseKey)
    }
}
