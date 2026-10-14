package cloud.dopp.haus.widget

import cloud.dopp.haus.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * The colour palette a colour-capable light offers on its tile (#87).
 *
 * **Why a fixed palette and not a picker:** `RemoteViews` can only host the
 * handful of widget-safe views — there is no colour wheel, no `SeekBar` callback,
 * no `<input type=color>` like the web card has. The homescreen tile therefore
 * shows a swatch button that hands off to [WidgetActionActivity], and the dialog
 * offers named colours. Each pick becomes exactly the call Haus's pages makes:
 * `POST /napi/ha/call` with `light.turn_on` + `{"rgb_color":[r,g,b]}`.
 *
 * Pure (`org.json` + resource ids only) → JVM-testable without a device.
 */
object LightColors {

    /** One offered colour: its German label and its sRGB value (0xRRGGBB). */
    data class Swatch(val labelRes: Int, val rgb: Int)

    /**
     * Warm→cool whites first (the everyday choice), then the saturated colours.
     * Ten entries: long enough to be useful, short enough for one dialog list
     * without scrolling on a phone.
     */
    val PALETTE: List<Swatch> = listOf(
        Swatch(R.string.color_warm_white, 0xFFB46B),
        Swatch(R.string.color_neutral_white, 0xFFF1E0),
        Swatch(R.string.color_cool_white, 0xCFE4FF),
        Swatch(R.string.color_red, 0xFF3B30),
        Swatch(R.string.color_orange, 0xFF9500),
        Swatch(R.string.color_yellow, 0xFFD60A),
        Swatch(R.string.color_green, 0x34C759),
        Swatch(R.string.color_turquoise, 0x30C6C6),
        Swatch(R.string.color_blue, 0x2F6BFF),
        Swatch(R.string.color_violet, 0xAF52DE),
    )

    /** The `data` payload of the `light.turn_on` call for [swatch]. */
    fun data(swatch: Swatch): JSONObject =
        JSONObject().put("rgb_color", rgbArray(swatch.rgb))

    /** 0xRRGGBB → HA's `[r, g, b]`. */
    fun rgbArray(rgb: Int): JSONArray =
        JSONArray()
            .put((rgb shr 16) and 0xFF)
            .put((rgb shr 8) and 0xFF)
            .put(rgb and 0xFF)
}
