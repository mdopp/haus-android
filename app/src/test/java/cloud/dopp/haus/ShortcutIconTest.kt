package cloud.dopp.haus

import cloud.dopp.haus.widget.DeviceIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * rules are asserted here: the shortcut mapping is its own (never [DeviceIcons
 * .forDomain]), and each `ic_shortcut_*` really does carry an opaque field with
 * the glyph held inside the mask's safe zone.
 */
class ShortcutIconTest {

    private val mainDir = locate("src/main")

    // --- the mapping -----------------------------------------------------------

    @Test fun everySupportedDomainHasItsOwnBadge() {
        assertEquals(R.drawable.ic_shortcut_light, DeviceIcons.forShortcut("light"))
        assertEquals(R.drawable.ic_shortcut_switch, DeviceIcons.forShortcut("switch"))
        assertEquals(R.drawable.ic_shortcut_cover, DeviceIcons.forShortcut("cover"))
        assertEquals(R.drawable.ic_shortcut_climate, DeviceIcons.forShortcut("climate"))
        assertEquals(R.drawable.ic_shortcut_lock, DeviceIcons.forShortcut("lock"))
    }

    /** An unknown domain still gets a badge — never 0, and never the widget glyph. */
    @Test fun anUnknownDomainFallsBackToTheGenericBadge() {
        for (domain in listOf(null, "", "vacuum", "media_player", "Light")) {
            val res = DeviceIcons.forShortcut(domain)
            assertEquals("domain $domain", R.drawable.ic_shortcut_device, res)
            assertNotEquals("domain $domain must not be resource 0", 0, res)
        }
    }

    /** The whole point: a shortcut icon is never one of the widget's glyphs. */
    @Test fun noShortcutBadgeIsAWidgetGlyph() {
        val widgetGlyphs = setOf(
            R.drawable.ic_light, R.drawable.ic_switch, R.drawable.ic_cover,
            R.drawable.ic_climate, R.drawable.ic_door_lock, R.drawable.ic_device,
        )
        for (domain in listOf("light", "switch", "cover", "climate", "lock", "sensor", null)) {
            assertFalse(
                "the widget glyph is white on transparency (#99): $domain",
                DeviceIcons.forShortcut(domain) in widgetGlyphs,
            )
        }
    }

    /** Widget rendering is untouched — forDomain still answers with the glyphs. */
    @Test fun theWidgetMappingIsLeftAlone() {
        assertEquals(R.drawable.ic_light, DeviceIcons.forDomain("light"))
        assertEquals(R.drawable.ic_door_lock, DeviceIcons.forDomain("lock"))
        assertEquals(R.drawable.ic_device, DeviceIcons.forDomain("nonsense"))
    }

    // --- the drawables themselves ----------------------------------------------

    private val badges =
        listOf("light", "switch", "cover", "climate", "lock", "device")

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
                text.contains("""<background android:drawable="@color/haus_brand_blue""""))
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
                text.contains("""android:color="@color/haus_brand_blue""""))
            assertTrue("$name fallback must inset the glyph, not bleed it", text.contains("android:left="))
        }
    }

    /** Both variants draw the widget's glyph — one shape, not a second drawing of it. */
    @Test fun everyBadgeReusesTheOneGlyph() {
        val glyphs = mapOf(
            "light" to "ic_light", "switch" to "ic_switch", "cover" to "ic_cover",
            "climate" to "ic_climate", "lock" to "ic_door_lock", "device" to "ic_device",
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
