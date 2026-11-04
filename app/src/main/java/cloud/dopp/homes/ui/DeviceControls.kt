package cloud.dopp.homes.ui

import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.SENSITIVE_COVER_CLASSES
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A favorite's fine controls on the start screen (owner, 2026-11-03: „Hoch,
 * runter, heller, dunkler, …“), the ones Homes' devices page offers
 * (mdopp/homes `internal/pages/raeume.go`, `regler.go`, `medien.go`): a lamp
 * that is on its brightness, a cover Hoch, Stopp, Runter and its position, a
 * heating kälter and wärmer, a player Pause or Weiter, Leiser and Lauter. A
 * lock has none here: its tap opens its chooser.
 *
 * A level ([Slider]) is never a slider on the row (owner, 2026-11-03: a
 * scroll moved them, homes#230): the row says it („an · 60 %“), the „60 %“
 * opens [LevelSheet] with the large slider and its [Slider.steps]. A lamp
 * that is off says „aus“ as its level ([Slider.off], owner 2026-11-04), so
 * its sheet opens too.
 *
 * Every control is one `/napi/ha/call` on the card's own entity; Homes' rule
 * (`schalter.Do`) still decides each. Like the page, a cover offers its
 * buttons and position only when its class is named and is not a garage, a
 * door or a gate; such a cover keeps its tap and the one confirmation dialog.
 * Pure: JVM-testable.
 */
object DeviceControls {

    /** One button: [label], the dotted [service], its [data] (null: none). */
    data class Button(val label: String, val service: String, val data: Map<String, Any>? = null)

    /** A quick level in the sheet: „50 %“, „Zu“. */
    data class Step(val label: String, val value: Int)

    /**
     * A 0–100 level at [value]; a pick of `v` calls [service] with [key] = v,
     * 0 calls [zero] when set. [steps] are the sheet's quick levels, each one call.
     * [off] is what the row and the sheet say at 0 („aus“), instead of „0 %“.
     */
    data class Slider(
        val label: String,
        val value: Int,
        val service: String,
        val key: String,
        val zero: String? = null,
        val steps: List<Step> = emptyList(),
        val off: String? = null,
    ) {
        /** The level [v] as the row and the sheet say it: „60 %“, or [off] at 0. */
        fun says(v: Int = value): String = if (v <= 0 && off != null) off else StartDevices.percent(v)

        /** The call a pick of [v] makes: (service, data). */
        fun call(v: Int): Pair<String, Map<String, Any>?> {
            val at = v.coerceIn(0, 100)
            return if (at == 0 && zero != null) zero to null else service to mapOf(key to at)
        }
    }

    data class Controls(val buttons: List<Button> = emptyList(), val slider: Slider? = null) {
        val none: Boolean get() = buttons.isEmpty() && slider == null
    }

    // HA's supported_features bits (CoverEntityFeature, MediaPlayerEntityFeature)
    private const val COVER_OPEN = 1
    private const val COVER_CLOSE = 2
    private const val COVER_POSITION = 4
    private const val COVER_STOP = 8
    private const val MEDIA_PAUSE = 1
    private const val MEDIA_VOLUME_SET = 4
    private const val MEDIA_VOLUME_STEP = 1024
    private const val MEDIA_PLAY = 16384

    fun of(card: Card): Controls {
        val state = card.state?.trim()?.lowercase(Locale.ROOT)
        if (state == null || state == "unavailable" || state == "unknown") return Controls()
        return when (card.domain) {
            "light" -> light(card, state)
            "cover" -> cover(card)
            "climate" -> climate(card, state)
            "media_player" -> media(card, state)
            else -> Controls()
        }
    }

    /** A lamp's quick levels, as on Homes' sheet. */
    private val LIGHT_STEPS = listOf(Step("25 %", 25), Step("50 %", 50), Step("75 %", 75), Step("100 %", 100))

    /** A cover's quick levels, as on Homes' sheet. */
    private val COVER_STEPS = listOf(Step("Auf", 100), Step("½", 50), Step("Zu", 0))

    /**
     * A lamp HA can dim (a brightness, or a colour mode other than on/off):
     * its brightness, 0 turns it off. Off, its level says „aus“ (owner,
     * 2026-11-04: at 0 % there was nothing to open the sheet with), and a
     * pick turns it on at that level in one call.
     */
    private fun light(card: Card, state: String): Controls {
        val dims = card.brightness != null || card.colorModes.any { it != "onoff" && it != "unknown" }
        if (!dims || state != "on" && state != "off") return Controls()
        val value = if (state == "on") card.brightnessPct ?: 0 else 0
        val off = "aus".takeIf { state == "off" }
        return Controls(slider = Slider("Helligkeit", value, "light.turn_on", "brightness_pct", zero = "light.turn_off", steps = LIGHT_STEPS, off = off))
    }

    /** A cover Homes opens freely: what HA says it can, and its position when it reports one. */
    private fun coverFree(card: Card): Boolean {
        val dc = card.deviceClass?.trim()?.lowercase(Locale.ROOT)
        return !dc.isNullOrEmpty() && dc !in SENSITIVE_COVER_CLASSES
    }

    private fun cover(card: Card): Controls {
        if (!coverFree(card)) return Controls()
        val f = card.supportedFeatures ?: (COVER_OPEN or COVER_CLOSE or COVER_POSITION or COVER_STOP)
        val buttons = listOf(
            Triple(COVER_OPEN, "Hoch", "cover.open_cover"),
            Triple(COVER_STOP, "Stopp", "cover.stop_cover"),
            Triple(COVER_CLOSE, "Runter", "cover.close_cover"),
        ).filter { f and it.first != 0 }.map { Button(it.second, it.third) }
        val slider = card.position?.takeIf { f and COVER_POSITION != 0 }
            ?.let { Slider("Position", it.coerceIn(0, 100), "cover.set_cover_position", "position", steps = COVER_STEPS) }
        return Controls(buttons, slider)
    }

    /** A heating that is on with a setpoint: kälter and wärmer by its step, within its bounds. */
    private fun climate(card: Card, state: String): Controls {
        val now = card.setpoint ?: return Controls()
        if (state == "off") return Controls()
        val step = card.tempStep ?: 0.5
        val lo = card.minTemp ?: Double.NEGATIVE_INFINITY
        val hi = card.maxTemp ?: Double.POSITIVE_INFINITY
        fun to(v: Double) = mapOf<String, Any>("temperature" to (v / step).roundToInt() * step)
        val out = mutableListOf<Button>()
        if (now - step >= lo) out += Button("Kälter", "climate.set_temperature", to(now - step))
        if (now + step <= hi) out += Button("Wärmer", "climate.set_temperature", to(now + step))
        return Controls(out)
    }

    /** A playing or paused player: Pause or Weiter, then Leiser and Lauter, each as HA says it can. */
    private fun media(card: Card, state: String): Controls {
        if (state != "playing" && state != "paused") return Controls()
        val f = card.supportedFeatures ?: 0
        val out = mutableListOf<Button>()
        if (state == "playing" && f and MEDIA_PAUSE != 0) out += Button("Pause", "media_player.media_pause")
        if (state == "paused" && f and MEDIA_PLAY != 0) out += Button("Weiter", "media_player.media_play")
        if (f and (MEDIA_VOLUME_SET or MEDIA_VOLUME_STEP) != 0) {
            out += Button("Leiser", "media_player.volume_down")
            out += Button("Lauter", "media_player.volume_up")
        }
        return Controls(out)
    }
}
