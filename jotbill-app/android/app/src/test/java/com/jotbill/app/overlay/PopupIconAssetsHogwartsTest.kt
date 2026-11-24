package com.jotbill.app.overlay

import com.jotbill.app.R
import com.jotbill.app.theme.MagicSoundPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupIconAssetsHogwartsTest {

    private val allCategoryKeys = listOf(
        "utensils", "bus", "shopping-bag", "gamepad-2", "pill",
        "book-open", "house", "circle-ellipsis", "banknote", "trophy",
        "chart-line", "laptop", "circle-dollar-sign", "smartphone", "shopping-basket",
        "party-popper", "plane", "paw-print", "package", "briefcase",
        "trending-up", "gift", "hand-coins", "rotate-ccw", "receipt"
    )

    @Test
    fun `all 25 category keys map to hogwarts drawables when hogwarts mode enabled`() {
        for (key in allCategoryKeys) {
            val lucideRes = PopupIconAssets.forCategory(key, useHogwarts = false)
            val hogwartsRes = PopupIconAssets.forCategory(key, useHogwarts = true)
            assertNotNull("Missing lucide icon for $key", lucideRes)
            assertNotNull("Missing hogwarts icon for $key", hogwartsRes)
            assertNotEquals("Hogwarts icon should differ from lucide icon for $key", lucideRes, hogwartsRes)
        }
        assertEquals(R.drawable.ic_hp_utensils, PopupIconAssets.forCategory("utensils", useHogwarts = true))
        assertEquals(R.drawable.ic_lucide_utensils, PopupIconAssets.forCategory("utensils"))
        assertNull(PopupIconAssets.forCategory("🍜", useHogwarts = true))
    }

    @Test
    fun `magic sound player exposes all 4 trimmed hogwarts sound keys`() {
        val keys = MagicSoundPlayer.supportedKeys()
        assertTrue(keys.containsAll(listOf("coin_drop", "coin_clink", "quill", "chime")))
    }
}
