package cloud.dopp.homes.ui

import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.SENSITIVE_COVER_CLASSES
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A favorite's fine controls on the start screen (owner, 2026-11-03: „Hoch,
 * runter, heller, dunkler, …“), the ones Homes' devices page offers
 * (mdopp/homes `internal/pages/raeume.go`, `regler.go`, `medien.go`): a lamp
 * its brightness, a cover Hoch, Stopp, Runter and its position, a heating
 * kälter and wärmer, a player Pause or Weiter, Leiser and Lauter. A lock has
 * none here: its tap opens its chooser.
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

    /** A 0–100 slider at [value]; a pick of `v` calls [service] with [key] = v, 0 calls [zero] when set. */
    data class Slider(val label: String, val value: Int, val service: String, val key: String, val zero: String? = null) {
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

    /** A lamp HA can dim (a brightness, or a colour mode other than on/off): its brightness, 0 turns it off. */
    private fun light(card: Card, state: String): Controls {
        val dims = card.brightness != null || card.colorModes.any { it != "onoff" && it != "unknown" }
        if (!dims || (state != "on" && state != "off")) return Controls()
        val value = if (state == "on") card.brightnessPct ?: 0 else 0
        return Controls(slider = Slider("Helligkeit", value, "light.turn_on", "brightness_pct", zero = "light.turn_off"))
    }

    /** A cover Homes opens freely: what HA says it can, and its position when it reports one. */
    fun coverFree(card: Card): Boolean {
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
            ?.let { Slider("Position", it, "cover.set_cover_position", "position") }
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
