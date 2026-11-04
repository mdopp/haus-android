package cloud.dopp.homes.standort

/**
 * Standortverlauf (owner 2026-11-02): how often the app asks Android where
 * the phone is, and when it sends what it collected. Plain decisions, no
 * Android in here, so the tests hold them.
 *
 * The person picks one of two modes. Within it the rate follows the phone's
 * situation ([Lage]): on the move it is the mode's own; resting it drops;
 * resting on any Wi-Fi it drops to very rarely (the owner, 2026-11-02:
 * „Wenn WLAN, bewegt sich selten“ — any Wi-Fi, not only the home one).
 * Whether the phone rests comes from the fixes themselves ([Ruhe]) and
 * the platform's significant-motion sensor, never a GPS poll and never
 * Google Play services (the owner, 2026-11-02); a phone without the sensor
 * keeps the plain interval.
 *
 * The intervals are chosen, not measured for battery: see [anfrage].
 */
enum class Modus(val wire: String) {
    /** „Detailliert (mehr Akku)“: frequent fixes while moving, good enough for routes. */
    DETAILLIERT("detailliert"),

    /** „Sparsam“: low power, enough for „wo war ich wann“. */
    SPARSAM("sparsam"),
    ;

    companion object {
        fun aus(wire: String?): Modus = entries.firstOrNull { it.wire == wire } ?: SPARSAM
    }
}

/** Where the phone stands, as far as the rate goes. */
enum class Lage {
    /** Moving, or not known to rest. */
    UNTERWEGS,

    /** Resting, without Wi-Fi. */
    RUHT,

    /** Resting, connected to a Wi-Fi (any). */
    WLAN,
}

/** How exact a fix Android is asked for; the fused provider's priorities. */
enum class Genauigkeit { HOCH, AUSGEWOGEN }

/**
 * One request to the fused provider: a fix every [intervallMs], none more
 * often than [schnellstensMs] (fixes other apps asked for are taken up to
 * then), only after [abstandM] metres, and delivered together up to
 * [sammelnMs] late, so the phone wakes once for several.
 */
data class Anfrage(
    val intervallMs: Long,
    val schnellstensMs: Long,
    val genauigkeit: Genauigkeit,
    val abstandM: Float,
    val sammelnMs: Long,
)

object Plan {
    const val SEKUNDE = 1000L
    const val MINUTE = 60 * SEKUNDE
    const val STUNDE = 60 * MINUTE

    /**
     * [ruht]: [Ruhe]'s word (true still, false moving, null when the phone
     * has no significant-motion sensor to wake it again); [wlan]: connected
     * to a Wi-Fi now.
     * Wi-Fi without rest is on the move: leaving a place starts with moving.
     */
    fun lage(ruht: Boolean?, wlan: Boolean): Lage = when {
        ruht != true -> Lage.UNTERWEGS
        wlan -> Lage.WLAN
        else -> Lage.RUHT
    }

    /**
     * The request for [modus] in [lage]. Chosen, not measured:
     * - Detailliert: on the move every 15 s, high accuracy (GPS),
     *   delivered within 2 min (Android 12+ batches); resting every 5 min; resting on Wi-Fi hourly.
     * - Sparsam: on the move every 2 min, balanced (the network provider,
     *   Wi-Fi and cell, ~100 m), delivered within 10 min; resting every 15 min;
     *   resting on Wi-Fi every 2 h.
     * No distance filter: resting is told from fixes that stay put.
     */
    fun anfrage(modus: Modus, lage: Lage): Anfrage = when (modus) {
        Modus.DETAILLIERT -> when (lage) {
            Lage.UNTERWEGS -> Anfrage(15 * SEKUNDE, 5 * SEKUNDE, Genauigkeit.HOCH, 0f, 2 * MINUTE)
            Lage.RUHT -> Anfrage(5 * MINUTE, MINUTE, Genauigkeit.AUSGEWOGEN, 0f, 15 * MINUTE)
            Lage.WLAN -> Anfrage(STUNDE, 15 * MINUTE, Genauigkeit.AUSGEWOGEN, 0f, 0)
        }
        Modus.SPARSAM -> when (lage) {
            Lage.UNTERWEGS -> Anfrage(2 * MINUTE, MINUTE, Genauigkeit.AUSGEWOGEN, 0f, 10 * MINUTE)
            Lage.RUHT -> Anfrage(15 * MINUTE, 5 * MINUTE, Genauigkeit.AUSGEWOGEN, 0f, 30 * MINUTE)
            Lage.WLAN -> Anfrage(2 * STUNDE, 30 * MINUTE, Genauigkeit.AUSGEWOGEN, 0f, 0)
        }
    }
}

/** The platform's location providers, by their Android names (never Google Play services). */
object Anbieter {
    const val GPS = "gps"
    const val NETZ = "network"
    const val FUSED = "fused" // the platform's own, API 31+

    /**
     * The provider for [genauigkeit] among the enabled [da] on API [sdk]:
     * high accuracy is GPS first; balanced is the network provider first,
     * then the platform's fused one (31+), GPS last. Null when none is on.
     */
    fun waehle(genauigkeit: Genauigkeit, da: Set<String>, sdk: Int): String? {
        val fused = if (sdk >= 31) listOf(FUSED) else emptyList()
        val reihe = when (genauigkeit) {
            Genauigkeit.HOCH -> listOf(GPS) + fused + NETZ
            Genauigkeit.AUSGEWOGEN -> listOf(NETZ) + fused + GPS
        }
        return reihe.firstOrNull { it in da }
    }
}

/**
 * When the collected points go to Homes. Not each fix: in batches, when
 * enough came together or the oldest waited long enough, and never while
 * the last failure's pause runs — a box that is away is not asked again at
 * every fix. The points stay on the phone until Homes says Reitti has them.
 */
object Senden {
    /** At most this many points in one request; Homes takes 1000. */
    const val JE_ANFRAGE = 500

    /** This many waiting points go at once, whatever their age. */
    const val GENUG = 200

    /** How long the oldest point may wait in [modus] before the batch goes. */
    private fun hoechstensWarten(modus: Modus): Long = when (modus) {
        Modus.DETAILLIERT -> 15 * Plan.MINUTE
        Modus.SPARSAM -> 30 * Plan.MINUTE
    }

    /** The pause after the [fehlschlaege]-th failure in a row: 5 min, doubled each time, at most 2 h. */
    fun pause(fehlschlaege: Int): Long {
        if (fehlschlaege <= 0) return 0
        val p = 5 * Plan.MINUTE shl minOf(fehlschlaege - 1, 5)
        return minOf(p, 2 * Plan.STUNDE)
    }

    /**
     * Whether to send now: [anzahl] points wait, the oldest since [aeltester]
     * (ms, null for none); [fehlschlaege] failures in a row, the last at
     * [letzterFehlschlag]. [gedraengt]: the person asked („Jetzt senden“),
     * or the screen was opened — then the pause does not hold.
     */
    fun faellig(
        jetzt: Long,
        modus: Modus,
        anzahl: Int,
        aeltester: Long?,
        fehlschlaege: Int,
        letzterFehlschlag: Long,
        gedraengt: Boolean = false,
    ): Boolean {
        if (anzahl <= 0 || aeltester == null) return false
        if (gedraengt) return true
        if (fehlschlaege > 0 && jetzt < letzterFehlschlag + pause(fehlschlaege)) return false
        return anzahl >= GENUG || jetzt - aeltester >= hoechstensWarten(modus)
    }
}
