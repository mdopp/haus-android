package cloud.dopp.haus.standort

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a resting phone was first seen: the fix a still phone stays near. */
data class Anker(val zeit: Long, val breite: Double, val laenge: Double, val genauigkeit: Float)

/**
 * Whether the phone rests, told from its own fixes (the owner, 2026-11-02:
 * no Google Play services, so no activity recognition): a phone whose
 * fixes stay within their accuracy of one place for [ruheNach] rests. A fix
 * farther away ends it at once; so does the significant-motion sensor
 * ([Aufzeichnung]), which resets [Stand] to [Stand.NEU]. No fix is asked
 * for to find out: the decision rides on the fixes the plan asks for anyway.
 */
object Ruhe {
    /** Less than this apart is the same place, however exact the fixes claim to be. */
    const val MIN_TOLERANZ_M = 30f

    /** A fix less exact than this says nothing about moving or resting. */
    const val UNBRAUCHBAR_M = 500f

    /** How long fixes must stay put before the phone counts as resting. Chosen, not measured. */
    fun ruheNach(modus: Modus): Long = when (modus) {
        Modus.DETAILLIERT -> 3 * Plan.MINUTE // some 12 fixes at 15 s
        Modus.SPARSAM -> 10 * Plan.MINUTE // some 5 fixes at 2 min
    }

    /** The decision so far: the anchor, and whether the phone rests. */
    data class Stand(val anker: Anker?, val ruht: Boolean) {
        companion object {
            val NEU = Stand(null, false)
        }
    }

    /** [stand] after [fix]. */
    fun weiter(stand: Stand, fix: Punkt, modus: Modus): Stand {
        if (fix.genauigkeit > UNBRAUCHBAR_M || fix.genauigkeit.isNaN()) return stand
        val a = stand.anker ?: return Stand(anker(fix), false)
        if (fix.zeit < a.zeit) return stand // late, from an older batch
        val toleranz = max(MIN_TOLERANZ_M, a.genauigkeit + fix.genauigkeit)
        if (abstand(a.breite, a.laenge, fix.breite, fix.laenge) > toleranz) return Stand(anker(fix), false)
        return Stand(a, stand.ruht || fix.zeit - a.zeit >= ruheNach(modus))
    }

    /** [stand] after [fixe], in order. */
    fun weiter(stand: Stand, fixe: List<Punkt>, modus: Modus): Stand =
        fixe.sortedBy { it.zeit }.fold(stand) { s, f -> weiter(s, f, modus) }

    private fun anker(p: Punkt) = Anker(p.zeit, p.breite, p.laenge, p.genauigkeit)

    /** Metres between two places on earth (haversine). */
    fun abstand(b1: Double, l1: Double, b2: Double, l2: Double): Double {
        val r = 6_371_000.0
        val db = Math.toRadians(b2 - b1)
        val dl = Math.toRadians(l2 - l1)
        val h = sin(db / 2) * sin(db / 2) + cos(Math.toRadians(b1)) * cos(Math.toRadians(b2)) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(h))
    }
}
