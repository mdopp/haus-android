package cloud.dopp.homes

import cloud.dopp.homes.standort.Anbieter
import cloud.dopp.homes.standort.Genauigkeit
import cloud.dopp.homes.standort.Lage
import cloud.dopp.homes.standort.Modus
import cloud.dopp.homes.standort.Plan
import cloud.dopp.homes.standort.Plan.MINUTE
import cloud.dopp.homes.standort.Plan.SEKUNDE
import cloud.dopp.homes.standort.Punkt
import cloud.dopp.homes.standort.Ruhe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Without Google Play services (the owner, 2026-11-02) the app tells a
 * resting phone from its own fixes: they stay within their accuracy of
 * one place for some minutes. A fix farther off ends the rest at once.
 */
class StandortRuheTest {

    private val t0 = 1_800_000_000_000L

    /** A fix [ostM] metres east of a point in Berlin, at [nach] ms after t0. */
    private fun fix(nach: Long, ostM: Double = 0.0, genau: Float = 15f) =
        Punkt(t0 + nach, 52.52, 13.405 + ostM / (111_320.0 * Math.cos(Math.toRadians(52.52))), genau)

    private fun durch(modus: Modus, vararg fixe: Punkt): Ruhe.Stand =
        fixe.fold(Ruhe.Stand.NEU) { s, f -> Ruhe.weiter(s, f, modus) }

    @Test
    fun fixesThatStayPutForLongEnoughMeanResting() {
        val still = (0..12).map { fix(it * 15 * SEKUNDE, ostM = if (it % 2 == 0) 4.0 else -6.0) }
        val s = durch(Modus.DETAILLIERT, *still.toTypedArray())
        assertTrue("3 min within the accuracy: resting", s.ruht)
        assertFalse("2 min are not yet enough", durch(Modus.DETAILLIERT, *still.take(9).toTypedArray()).ruht)
        // Sparsam waits 10 minutes
        assertFalse(durch(Modus.SPARSAM, fix(0), fix(2 * MINUTE), fix(4 * MINUTE), fix(6 * MINUTE), fix(8 * MINUTE)).ruht)
        assertTrue(durch(Modus.SPARSAM, fix(0), fix(2 * MINUTE), fix(4 * MINUTE), fix(6 * MINUTE), fix(8 * MINUTE), fix(10 * MINUTE)).ruht)
    }

    @Test
    fun walkingAwayIsNeverRestingAndEndsARestAtOnce() {
        // 1.4 m/s, a fix every 15 s: 21 m apart, the anchor left behind after two
        val gehen = (0..20).map { fix(it * 15 * SEKUNDE, ostM = it * 21.0, genau = 8f) }
        assertFalse(durch(Modus.DETAILLIERT, *gehen.toTypedArray()).ruht)
        val ruht = durch(Modus.DETAILLIERT, fix(0), fix(MINUTE), fix(2 * MINUTE), fix(3 * MINUTE))
        assertTrue(ruht.ruht)
        val weg = Ruhe.weiter(ruht, fix(63 * MINUTE, ostM = 200.0), Modus.DETAILLIERT)
        assertFalse("a fix 200 m off ends the rest", weg.ruht)
        assertEquals("and is the new anchor", t0 + 63 * MINUTE, weg.anker!!.zeit)
        // an hour later, at home on the same spot: still resting, the anchor kept
        val bleibt = Ruhe.weiter(ruht, fix(63 * MINUTE, ostM = 10.0), Modus.DETAILLIERT)
        assertTrue(bleibt.ruht)
        assertEquals(t0, bleibt.anker!!.zeit)
    }

    @Test
    fun theToleranceIsTheFixesAccuracyAtLeast30Metres() {
        // two network fixes of 100 m each: 150 m apart is the same place
        assertTrue(durch(Modus.SPARSAM, fix(0, genau = 100f), fix(10 * MINUTE, ostM = 150.0, genau = 100f)).ruht)
        assertFalse(durch(Modus.SPARSAM, fix(0, genau = 100f), fix(10 * MINUTE, ostM = 250.0, genau = 100f)).ruht)
        // exact GPS fixes: 25 m is the same place, 40 m is not
        assertTrue(durch(Modus.SPARSAM, fix(0, genau = 3f), fix(10 * MINUTE, ostM = 25.0, genau = 3f)).ruht)
        assertFalse(durch(Modus.SPARSAM, fix(0, genau = 3f), fix(10 * MINUTE, ostM = 40.0, genau = 3f)).ruht)
    }

    @Test
    fun aVagueOrLateFixSaysNothing() {
        val ruht = durch(Modus.DETAILLIERT, fix(0), fix(3 * MINUTE))
        assertTrue(ruht.ruht)
        assertEquals("a 3 km cell fix far off does not end the rest", ruht, Ruhe.weiter(ruht, fix(5 * MINUTE, ostM = 2000.0, genau = 3000f), Modus.DETAILLIERT))
        assertEquals("a late fix from an older batch is left out", ruht, Ruhe.weiter(ruht, fix(-MINUTE, ostM = 500.0), Modus.DETAILLIERT))
        assertNull(Ruhe.weiter(Ruhe.Stand.NEU, fix(0, genau = 900f), Modus.DETAILLIERT).anker)
        // a batch is taken in time order
        assertTrue(Ruhe.weiter(Ruhe.Stand.NEU, listOf(fix(3 * MINUTE), fix(0), fix(MINUTE)), Modus.DETAILLIERT).ruht)
    }

    /** The whole chain: resting fixes on a Wi-Fi lower the rate; the sensor or a far fix bring it back. */
    @Test
    fun restingOnWifiFromTheFixesLowersTheRate() {
        val ruht = durch(Modus.SPARSAM, fix(0), fix(5 * MINUTE), fix(10 * MINUTE))
        val selten = Plan.anfrage(Modus.SPARSAM, Plan.lage(ruht.ruht, wlan = true))
        assertEquals(Lage.WLAN, Plan.lage(ruht.ruht, wlan = true))
        assertTrue(selten.intervallMs >= Plan.STUNDE)
        // the significant-motion sensor fired: Stand.NEU, the plain rate again
        assertEquals(Lage.UNTERWEGS, Plan.lage(Ruhe.Stand.NEU.ruht, wlan = true))
        // a phone without the sensor keeps the plain interval, however still
        assertEquals(Lage.UNTERWEGS, Plan.lage(null, wlan = true))
    }

    @Test
    fun theProviderIsThePlatformsNeverGoogles() {
        val alle = setOf(Anbieter.GPS, Anbieter.NETZ, Anbieter.FUSED, "passive")
        assertEquals(Anbieter.GPS, Anbieter.waehle(Genauigkeit.HOCH, alle, 34))
        assertEquals(Anbieter.NETZ, Anbieter.waehle(Genauigkeit.AUSGEWOGEN, alle, 34))
        // no network provider (a phone without Google's): the platform's fused one on 12+, else GPS
        assertEquals(Anbieter.FUSED, Anbieter.waehle(Genauigkeit.AUSGEWOGEN, setOf(Anbieter.GPS, Anbieter.FUSED), 31))
        assertEquals(Anbieter.GPS, Anbieter.waehle(Genauigkeit.AUSGEWOGEN, setOf(Anbieter.GPS, Anbieter.FUSED), 30))
        assertEquals(Anbieter.NETZ, Anbieter.waehle(Genauigkeit.HOCH, setOf(Anbieter.NETZ), 34))
        assertNull("location off", Anbieter.waehle(Genauigkeit.HOCH, setOf("passive"), 34))
    }
}
