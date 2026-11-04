package cloud.dopp.homes

import cloud.dopp.homes.einrichtung.Kurz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard for the white-disc regression in the app-icon menu (#99).
 *
 * The widget glyphs are white on transparency because the widget paints them on
 * its own dark field. A launcher draws a shortcut icon on a **light** background
 * under a round adaptive mask, so reusing those glyphs produced exactly one
 * thing: a white circle. A cousin of #88, but the opposite lack — there the mask
 * ate the colour, here the icon had no field of its own at all.
 *
 * Nothing about that fails to compile and no emulator run catches it, so the two
 * rules are asserted here for the four shortcuts (owner, 2026-11-04): each has a
 * badge of its own, never the bare glyph, and each `ic_shortcut_*` really does
 * carry an opaque field with the glyph held inside the mask's safe zone.
 */
class ShortcutIconTest {

    private val mainDir = locate("src/main")

    // --- the mapping -----------------------------------------------------------

    /** Each of the four shortcuts has its own badge, and none is a white glyph the launcher turns into a disc. */
    @Test fun everyShortcutHasItsOwnBadge() {
        val badges = Kurz.values().map { it.bild }
        assertEquals(Kurz.values().size, badges.toSet().size)
        val glyphs = setOf(R.drawable.ic_garage_closed, R.drawable.ic_modul_einkauf, R.drawable.ic_standort, R.drawable.ic_modul_scannen)
        badges.forEach { assertFalse("the glyph is white on transparency (#99): $it", it in glyphs) }
        assertEquals(R.drawable.ic_shortcut_garage, Kurz.GARAGE.bild)
    }

    // --- the drawables themselves ----------------------------------------------

    private val badges = Kurz.values().map { it.id }

    /** On API 26+ the launcher masks it: an opaque field, glyph inside the safe zone. */
    @Test fun everyBadgeIsAnAdaptiveIconWithAnOpaqueField() {
        for (name in badges) {
            val xml = File(mainDir, "res/drawable-v26/ic_shortcut_$name.xml")
            assertTrue("missing res/drawable-v26/ic_shortcut_$name.xml", xml.isFile)
            val text = xml.readText()
            assertTrue("$name must be an adaptive icon, else the launcher wraps it in white",
                text.contains("<adaptive-icon"))
            // The brand blue, not the shell accent: a shortcut badge is drawn in
            // the launcher among foreign icons, so it did not follow the accent
            // to orange when the in-app shell did (#130).
            assertTrue("$name needs an opaque brand-blue background (#99/#130)",
                text.contains("""<background android:drawable="@color/homes_brand_blue""""))
            // The outer third of the canvas is the launcher's to crop.
            val inset = Regex("""android:inset="(\d+)%"""").find(text)
            assertTrue("$name must inset its glyph into the mask's safe zone", inset != null)
            assertTrue(
                "$name insets only ${inset!!.groupValues[1]}% — the glyph reaches the cropped edge",
                inset.groupValues[1].toInt() >= 25,
            )
        }
    }

    /** Below API 26 there is no mask, so the fallback carries the field itself. */
    @Test fun everyBadgeHasAPreOreoFallbackWithTheSameField() {
        for (name in badges) {
            val xml = File(mainDir, "res/drawable/ic_shortcut_$name.xml")
            assertTrue("missing res/drawable/ic_shortcut_$name.xml", xml.isFile)
            val text = xml.readText()
            assertTrue("$name fallback needs the opaque brand-blue field",
                text.contains("""android:color="@color/homes_brand_blue""""))
            assertTrue("$name fallback must inset the glyph, not bleed it", text.contains("android:left="))
        }
    }

    /** Both variants draw the app's own glyph — one shape, not a second drawing of it. */
    @Test fun everyBadgeReusesTheOneGlyph() {
        val glyphs = mapOf(
            "garage" to "ic_garage_closed", "einkauf" to "ic_modul_einkauf",
            "zeitleiste" to "ic_standort", "scannen" to "ic_modul_scannen",
        )
        for ((name, glyph) in glyphs) {
            for (dir in listOf("drawable", "drawable-v26")) {
                val text = File(mainDir, "res/$dir/ic_shortcut_$name.xml").readText()
                assertTrue(
                    "$dir/ic_shortcut_$name should draw @drawable/$glyph",
                    text.contains("@drawable/$glyph\""),
                )
            }
        }
    }

    /** Unit tests run from the module dir, but don't depend on it. */
    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, rel), File(dir, "app/$rel"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $rel from ${File("").absolutePath}")
    }
}
