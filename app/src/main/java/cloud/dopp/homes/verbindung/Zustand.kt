package cloud.dopp.homes.verbindung

import cloud.dopp.homes.R
import cloud.dopp.homes.data.ScreenGate

/**
 * What the app says about its line to Homes, the one answer the live
 * notification, the start screen and the widgets show (the owner, 2026-11-04:
 * an unreachable box read „Pausiert, Bildschirm aus“). [alt]: the values on
 * screen are the last ones known, not live — the widgets grey them and say
 * since when ([Verbindung.stand]).
 */
enum class Zustand(val text: Int, val alt: Boolean = false) {
    LIVE(R.string.live_on),

    /** The screen is off: nothing goes to Homes (docs/CONTRACT.md (c)). */
    PAUSE_BILDSCHIRM(R.string.live_paused),

    /** No live widget placed or the switch off: the stream is not wanted. */
    PAUSE_AUS(R.string.verbindung_pause_aus),

    /** Opening the stream, or again after it ended while Homes was there. */
    VERBINDET(R.string.live_connecting),

    /** The phone has a network, Homes does not answer: refused, timed out, or the proxy's 502/504. */
    BOX_WEG(R.string.verbindung_box_weg, alt = true),
    KEIN_NETZ(R.string.verbindung_kein_netz, alt = true),

    /** Homes refuses the device token (401): the device was removed there; pair again. */
    NICHT_GEKOPPELT(R.string.verbindung_nicht_gekoppelt, alt = true),
    ;

    companion object {
        /** The proxy in front of Homes answers these when Homes itself does not. */
        private val PROXY_OHNE_HOMES = setOf(502, 504)

        /**
         * What a failed request says about the line: [t] the exception, [code]
         * the HTTP status when an answer came, [netz] whether the phone has a
         * network. Null when it says nothing: the screen gate held it back,
         * or Homes answered (any other status, or a stream it ended itself).
         */
        fun ausFehler(t: Throwable?, code: Int?, netz: Boolean): Zustand? = when {
            code == HTTP_UNAUTHORIZED -> NICHT_GEKOPPELT
            t is ScreenGate.ScreenOffException -> null
            code != null && code !in PROXY_OHNE_HOMES -> null
            t == null && code == null -> null
            !netz -> KEIN_NETZ
            else -> BOX_WEG
        }

        /**
         * The one answer from the facts: [gekoppelt] a token stored, [bildschirm]
         * the screen on, [gewollt] the live stream wanted ([cloud.dopp.homes.realtime.Live.wanted]),
         * [live] the stream open, [fehler] what the last request said ([ausFehler]).
         * A failure outranks the screen: whether the values are old does not
         * change when the screen goes off, and on again it is the last thing known.
         */
        fun aus(gekoppelt: Boolean, bildschirm: Boolean, gewollt: Boolean, live: Boolean, fehler: Zustand?): Zustand = when {
            !gekoppelt -> NICHT_GEKOPPELT
            fehler != null -> fehler
            !bildschirm -> PAUSE_BILDSCHIRM
            !gewollt -> PAUSE_AUS
            live -> LIVE
            else -> VERBINDET
        }

        private const val HTTP_UNAUTHORIZED = 401
    }
}
