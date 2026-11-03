package cloud.dopp.homes.widget

import cloud.dopp.homes.data.Energy
import cloud.dopp.homes.data.EnergyFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The power now as Homes' Energie page draws it (internal/pages/energie_fluss.go,
 * flussJetzt and kanten): Solar, Netz, Akku and Haus as nodes, each with its
 * number and word, and the ways the power takes between them. The widget's
 * picture ([EnergyPicture]) is drawn from this. Pure → JVM-testable.
 */
class EnergyFluss private constructor(
    /** The nodes the house has, by key: solar, haus, netz, akku. */
    val da: Set<String>,
    /** The nodes whose sensor said a number now. */
    val known: Set<String>,
    val solar: Double,
    val haus: Double,
    val bezug: Double,
    val einsp: Double,
    val laden: Double,
    val entladen: Double,
    /** How full the battery is, in percent, or null. */
    val charge: Double?,
) {
    /** One way the power takes: from, to, its colour's name (eigen, einsp, bezug, akku), W. */
    data class Weg(val von: String, val nach: String, val farbe: String, val w: Double)

    /** Every way the house could have, the active ones with their power; empty while a node it has is not known. */
    val wege: List<Weg> by lazy { kanten() }

    /** The ways that carry at least a watt now. */
    val aktiv: List<Weg> get() = wege.filter { it.w >= 1 && it.von in da && it.nach in da }

    private fun kanten(): List<Weg> {
        if ("haus" !in da || da.any { it !in known }) return emptyList()
        var rest = solar
        val solarHaus = min(rest, haus); rest -= solarHaus
        val solarAkku = min(rest, laden); rest -= solarAkku
        val solarNetz = min(rest, einsp)
        var need = haus - solarHaus
        val akkuHaus = min(need, entladen); need -= akkuHaus
        val netzHaus = min(need, bezug)
        val netzAkku = max(0.0, min(laden - solarAkku, bezug - netzHaus))
        return listOf(
            Weg("solar", "haus", "eigen", solarHaus), Weg("solar", "akku", "eigen", solarAkku),
            Weg("solar", "netz", "einsp", solarNetz), Weg("akku", "haus", "akku", akkuHaus),
            Weg("netz", "haus", "bezug", netzHaus), Weg("netz", "akku", "bezug", netzAkku),
        )
    }

    /** The share of what came into the house that did not come from the grid; null without the ways. */
    val autarkie: Double?
        get() {
            if (wege.isEmpty()) return null
            fun w(von: String, nach: String) = wege.first { it.von == von && it.nach == nach }.w
            val inHaus = w("solar", "haus") + w("akku", "haus") + w("netz", "haus")
            if (inHaus < 1) return null
            return (w("solar", "haus") + w("akku", "haus")) / inHaus
        }

    /** A node's number and word, as the page says them („191 W“, „entlädt“). */
    fun text(key: String): Pair<String, String> {
        if (key !in known) return "–" to "unbekannt"
        return when (key) {
            "solar" -> watts(solar) to "erzeugt"
            "haus" -> watts(haus) to "verbraucht"
            "netz" -> when {
                bezug > 0 -> watts(bezug) to "aus dem Netz"
                einsp > 0 -> watts(einsp) to "ins Netz"
                else -> watts(0.0) to "ruht"
            }
            "akku" -> when {
                laden > 0 -> watts(laden) to "lädt"
                entladen > 0 -> watts(entladen) to "entlädt"
                else -> watts(0.0) to "ruht"
            }
            else -> "" to ""
        }
    }

    /** A node's name, the battery's with how full it is: „Akku 83 %“. */
    fun name(key: String): String = when (key) {
        "solar" -> "Solar"
        "haus" -> "Haus"
        "netz" -> "Netz"
        "akku" -> charge?.let { "Akku " + percent(it) } ?: "Akku"
        else -> key
    }

    /** The picture in words, for a screen reader: each node, then each way. */
    fun words(): String {
        val nodes = KEYS.filter { it in da }.joinToString(", ") { k ->
            val (n, w) = text(k)
            when {
                k !in known -> "${name(k)} $w"
                w.endsWith("Netz") -> "$n $w" // „4 W ins Netz“
                else -> "${name(k)} $w $n"
            }
        }
        val ways = aktiv.joinToString(", ") { "${name(it.von).substringBefore(' ')} → ${name(it.nach).substringBefore(' ')} ${watts(it.w)}" }
        return listOf(nodes, ways).filter { it.isNotEmpty() }.joinToString(". ") + "."
    }

    companion object {
        /** The nodes in the order the page names them. */
        val KEYS = listOf("solar", "haus", "netz", "akku")

        private val NODE = mapOf("supply" to "solar", "draw" to "haus", "grid" to "netz", "battery" to "akku")

        /** The flow now from Homes' legs: grid +W drawn −W fed in, battery +W charging −W discharging; under a watt is resting. */
        fun of(e: Energy): EnergyFluss {
            val da = mutableSetOf<String>()
            val known = mutableSetOf<String>()
            var solar = 0.0; var haus = 0.0; var bezug = 0.0; var einsp = 0.0; var laden = 0.0; var entladen = 0.0
            for (l in e.flow) {
                val k = NODE[l.sense] ?: continue
                da += k
                val w = inWatts(l) ?: continue
                known += k
                val signed = if (abs(w) < 1) 0.0 else w
                when (k) {
                    "solar" -> solar = max(0.0, signed)
                    "haus" -> haus = max(0.0, signed)
                    "netz" -> if (signed > 0) bezug = signed else einsp = -signed
                    "akku" -> if (signed > 0) laden = signed else entladen = -signed
                }
            }
            return EnergyFluss(da, known, solar, haus, bezug, einsp, laden, entladen, e.charge.takeIf { "akku" in da })
        }

        /** A leg's reading in W, whatever unit its sensor says. */
        fun inWatts(l: EnergyFlow): Double? {
            val v = l.watts ?: return null
            if (v.isNaN() || v.isInfinite()) return null
            return when (l.unit.trim()) {
                "kW" -> v * 1e3
                "MW" -> v * 1e6
                else -> v
            }
        }

        /** Power the page's way: „191 W“, „3,2 kW“. */
        fun watts(w: Double): String =
            if (w.roundToInt() < 1000) "${w.roundToInt()} W" else String.format(Locale.GERMANY, "%.1f kW", w / 1000)

        /** A percent as a whole number: „83 %“. */
        fun percent(v: Double): String = "${v.roundToInt()} %"
    }
}
