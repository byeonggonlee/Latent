package com.byeonggon.latent.ui.common

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's icons are one family (UI guide §7): outlines with round ends and corners, none of them filled. */
class IconFamilyTest {
    private val drawables = File(System.getProperty("user.dir"), "src/main/res/drawable")

    @Test
    fun everyIconIsAnOutlineWithRoundEnds() {
        val icons = drawables.listFiles { file -> file.name.startsWith("ic_") && file.name.endsWith(".xml") }!!
            .filter { it.nameWithoutExtension !in DRAWN_OTHERWISE }
        assertTrue(icons.size >= 25)
        val wrong = icons.flatMap { icon ->
            PATH.findAll(icon.readText()).map { it.value }.filter { path ->
                "android:fillColor" in path || "android:strokeLineCap=\"round\"" !in path ||
                    "android:strokeLineJoin=\"round\"" !in path || STROKE.find(path)?.groupValues?.get(1) !in WIDTHS
            }.map { icon.name }
        }
        assertEquals(emptyList<String>(), wrong.distinct())
    }

    @Test
    fun noMaterialIconIsDrawnInTheirPlace() {
        val sources = File(System.getProperty("user.dir"), "src/main/java").walk().filter { it.extension == "kt" }
        assertEquals(emptyList<String>(), sources.filter { "androidx.compose.material.icons" in it.readText() }.map { it.name }.toList())
    }

    private companion object {
        val PATH = Regex("""<path\b[^>]*>""")
        val STROKE = Regex("""android:strokeWidth="([^"]+)"""")
        /** 1.7 as the guide asks; 2 for the small badges, which are shown at 16 dp. */
        val WIDTHS = setOf("1.7", "2")
        /**
         * The launcher and notification icons follow Android's own rules, dots are filled discs, a flag or star
         * that says "chosen" is filled on purpose, and before/after fills its after half.
         */
        val DRAWN_OTHERWISE = setOf(
            "ic_launcher_foreground", "ic_launcher_monochrome", "ic_stat_latent",
            "ic_more_horiz", "ic_more_vert", "ic_flag", "ic_star_filled", "ic_compare",
        )
    }
}
