package com.jotbill.app.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconManagerTest {

    @Test
    fun `resolves default icon when theme is not hogwarts or dynamic icon disabled`() {
        assertEquals(
            LauncherIconManager.ICON_DEFAULT,
            LauncherIconManager.resolveDesiredIconKey("blocks", "classic", true)
        )
        assertEquals(
            LauncherIconManager.ICON_DEFAULT,
            LauncherIconManager.resolveDesiredIconKey("mint", "gryffindor", true)
        )
        assertEquals(
            LauncherIconManager.ICON_DEFAULT,
            LauncherIconManager.resolveDesiredIconKey("hogwarts", "gryffindor", false)
        )
    }

    @Test
    fun `resolves matching wizard cat and 4 house icons when hogwarts and dynamic icon enabled`() {
        assertEquals(
            LauncherIconManager.ICON_HP_CLASSIC,
            LauncherIconManager.resolveDesiredIconKey("hogwarts", "classic", true)
        )
        assertEquals(
            LauncherIconManager.ICON_HP_GRYFFINDOR,
            LauncherIconManager.resolveDesiredIconKey("hogwarts", "gryffindor", true)
        )
        assertEquals(
            LauncherIconManager.ICON_HP_SLYTHERIN,
            LauncherIconManager.resolveDesiredIconKey("hogwarts", "slytherin", true)
        )
        assertEquals(
            LauncherIconManager.ICON_HP_RAVENCLAW,
            LauncherIconManager.resolveDesiredIconKey("hogwarts", "ravenclaw", true)
        )
        assertEquals(
            LauncherIconManager.ICON_HP_HUFFLEPUFF,
            LauncherIconManager.resolveDesiredIconKey("hogwarts", "hufflepuff", true)
        )
        // 未知学院值回退到 classic 招财巫师猫
        assertEquals(
            LauncherIconManager.ICON_HP_CLASSIC,
            LauncherIconManager.resolveDesiredIconKey("hogwarts", "unknown", true)
        )
    }

    @Test
    fun `all 6 launcher alias class names are unique and match manifest declarations`() {
        val aliases = LauncherIconManager.allAliasClassNames()
        assertEquals(6, aliases.size)
        assertEquals(6, aliases.toSet().size)
        assertTrue(aliases.contains("com.jotbill.app.LauncherDefault"))
        assertTrue(aliases.contains("com.jotbill.app.LauncherHogwartsClassic"))
        assertTrue(aliases.contains("com.jotbill.app.LauncherHogwartsGryffindor"))
        assertTrue(aliases.contains("com.jotbill.app.LauncherHogwartsSlytherin"))
        assertTrue(aliases.contains("com.jotbill.app.LauncherHogwartsRavenclaw"))
        assertTrue(aliases.contains("com.jotbill.app.LauncherHogwartsHufflepuff"))
    }
}
