package cloud.dopp.haus

import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.widget.LightColors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Light colour on the device tile (#87). `card_spec` has been sending
 * `rgb_color` / `supported_color_modes` / `color_mode` / `color_temp` all along —
 * the client simply dropped them, so a colour lamp showed only −/+ while the web
 * card had a swatch. These are the parse and capability rules that decide whether
 * the tile offers a colour at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardColorTest {

    private fun card(json: String) = ApiClient.parseCardJson(JSONObject(json))

    @Test fun readsTheColourFieldsWhenPresent() {
        val c = card(
            """
            {"entity_id": "light.wohnzimmer", "name": "Wohnzimmer", "domain": "light",
             "state": "on", "brightness": 204,
             "rgb_color": [255, 160, 32], "supported_color_modes": ["rgb", "color_temp"],
             "color_mode": "rgb", "color_temp": 366}
            """.trimIndent(),
        )
        assertEquals(0xFFA020, c.rgbColor)
        assertEquals(0xFFFFA020.toInt(), c.colorArgb)
        assertEquals(listOf("rgb", "color_temp"), c.supportedColorModes)
        assertEquals("rgb", c.colorMode)
        assertEquals(366, c.colorTemp)
        assertTrue(c.isColorCapable)
    }

    /** A plain white bulb must come out exactly as before — no colour, no swatch. */
    @Test fun missingColourFieldsStayEmpty() {
        val c = card(
            """{"entity_id": "light.flur", "name": "Flur", "domain": "light", "state": "on", "brightness": 128}""",
        )
        assertNull(c.rgbColor)
        assertNull(c.colorArgb)
        assertNull(c.colorMode)
        assertNull(c.colorTemp)
        assertEquals(emptyList<String>(), c.supportedColorModes)
        assertFalse(c.isColorCapable)
        // The pre-existing fields are untouched by the addition.
        assertEquals(128, c.brightness)
        assertEquals(50, c.brightnessPct)
    }

    @Test fun aBrokenColourIsNoColour() {
        // Short, out-of-range or non-numeric triples must not become a wrong colour.
        for (raw in listOf("[255, 0]", "[300, 0, 0]", "[]", "[\"a\", \"b\", \"c\"]")) {
            val c = card(
                """{"entity_id": "light.x", "domain": "light", "state": "on", "rgb_color": $raw}""",
            )
            assertNull("rgb_color $raw", c.rgbColor)
        }
        // Black is a colour, not a missing one.
        assertEquals(
            0,
            card("""{"entity_id": "light.x", "domain": "light", "rgb_color": [0, 0, 0]}""").rgbColor,
        )
    }

    @Test fun onlyColourCapableModesOfferTheSwatch() {
        for (mode in listOf("rgb", "hs", "xy", "rgbw", "rgbww", "color_temp")) {
            val c = card(
                """{"entity_id": "light.x", "domain": "light", "supported_color_modes": ["$mode"]}""",
            )
            assertTrue("mode $mode", c.isColorCapable)
        }
        for (mode in listOf("onoff", "brightness", "white")) {
            val c = card(
                """{"entity_id": "light.x", "domain": "light", "supported_color_modes": ["$mode"]}""",
            )
            assertFalse("mode $mode", c.isColorCapable)
        }
        // Colour belongs to lights: a switch reporting modes stays a switch.
        assertFalse(
            card("""{"entity_id": "switch.x", "domain": "switch", "supported_color_modes": ["rgb"]}""")
                .isColorCapable,
        )
    }

    /** Each palette entry becomes the same call the web card makes. */
    @Test fun aSwatchBecomesAnRgbTurnOnPayload() {
        val red = LightColors.PALETTE.first { it.rgb == 0xFF3B30 }
        val data = LightColors.data(red)
        assertEquals("""{"rgb_color":[255,59,48]}""", data.toString())
        // Every entry is a real 24-bit colour with its own label.
        assertEquals(LightColors.PALETTE.size, LightColors.PALETTE.map { it.rgb }.distinct().size)
        assertEquals(LightColors.PALETTE.size, LightColors.PALETTE.map { it.labelRes }.distinct().size)
        for (s in LightColors.PALETTE) assertTrue(s.rgb in 0..0xFFFFFF)
    }
}
