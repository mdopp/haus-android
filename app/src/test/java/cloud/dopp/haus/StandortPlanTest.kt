package cloud.dopp.haus

import cloud.dopp.haus.standort.Genauigkeit
import cloud.dopp.haus.standort.Lage
import cloud.dopp.haus.standort.Modus
import cloud.dopp.haus.standort.Plan
import cloud.dopp.haus.standort.Plan.MINUTE
import cloud.dopp.haus.standort.Plan.STUNDE
import cloud.dopp.haus.standort.Senden
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Standortverlauf's decisions (owner 2026-11-02): how often the app asks
 * where the phone is, and when a batch goes. The owner: „Wenn WLAN, bewegt
 * sich selten“ — resting on any Wi-Fi, the rate drops to very rarely; it
 * comes back as soon as the phone moves or the Wi-Fi is gone. Resting is
 * told from the fixes (StandortRuheTest); unknown is never resting.
 */
class StandortPlanTest {

    @Test
    fun restingOnAnyWifiIsItsOwnSituationMovingNeverIs() {
        assertEquals(Lage.WLAN, Plan.lage(ruht = true, wlan = true))
        assertEquals(Lage.RUHT, Plan.lage(ruht = true, wlan = false))
        // moving, on Wi-Fi or not: the mode's own rate
        assertEquals(Lage.UNTERWEGS, Plan.lage(ruht = false, wlan = true))
        assertEquals(Lage.UNTERWEGS, Plan.lage(ruht = false, wlan = false))
        // not known (no significant-motion sensor): never assumed resting
        assertEquals(Lage.UNTERWEGS, Plan.lage(ruht = null, wlan = true))
    }

    @Test
    fun restingOnWifiAsksFarLessOftenInBothModes() {
        Modus.entries.forEach { m ->
            val weg = Plan.anfrage(m, Lage.UNTERWEGS)
            val ruht = Plan.anfrage(m, Lage.RUHT)
            val wlan = Plan.anfrage(m, Lage.WLAN)
            assertTrue("$m: resting asks less often than moving", ruht.intervallMs > weg.intervallMs)
            assertTrue("$m: resting on Wi-Fi asks less often than resting", wlan.intervallMs > ruht.intervallMs)
            assertTrue("$m: on Wi-Fi at most hourly", wlan.intervallMs >= STUNDE)
            assertTrue("$m: other apps' fixes not more often than every 15 min", wlan.schnellstensMs >= 15 * MINUTE)
            listOf(weg, ruht, wlan).forEach { a ->
                assertTrue("$m $a: the fastest is no faster than allowed", a.schnellstensMs in 1..a.intervallMs)
                assertTrue("$m $a", a.sammelnMs == 0L || a.sammelnMs >= a.intervallMs)
            }
        }
    }

    @Test
    fun theModesDifferWhereItCounts() {
        val detail = Plan.anfrage(Modus.DETAILLIERT, Lage.UNTERWEGS)
        val sparsam = Plan.anfrage(Modus.SPARSAM, Lage.UNTERWEGS)
        assertEquals(Genauigkeit.HOCH, detail.genauigkeit)
        assertEquals(15_000L, detail.intervallMs)
        assertEquals(Genauigkeit.AUSGEWOGEN, sparsam.genauigkeit)
        assertEquals(2 * MINUTE, sparsam.intervallMs)
        // only „Detailliert“ on the move asks for GPS accuracy
        Lage.entries.filter { it != Lage.UNTERWEGS }.forEach { l ->
            Modus.entries.forEach { m -> assertEquals("$m $l", Genauigkeit.AUSGEWOGEN, Plan.anfrage(m, l).genauigkeit) }
        }
        Lage.entries.forEach { l -> assertEquals(Genauigkeit.AUSGEWOGEN, Plan.anfrage(Modus.SPARSAM, l).genauigkeit) }
        // no distance filter: resting is told from fixes that stay put
        Lage.entries.forEach { l -> Modus.entries.forEach { m -> assertEquals(0f, Plan.anfrage(m, l).abstandM) } }
    }

    @Test
    fun theRateComesBackOnceThePhoneMovesOrLeavesTheWifi() {
        val wlan = Plan.anfrage(Modus.SPARSAM, Plan.lage(ruht = true, wlan = true))
        val bewegt = Plan.anfrage(Modus.SPARSAM, Plan.lage(ruht = false, wlan = true))
        val ohneWlan = Plan.anfrage(Modus.SPARSAM, Plan.lage(ruht = true, wlan = false))
        assertTrue(bewegt.intervallMs < wlan.intervallMs)
        assertTrue(ohneWlan.intervallMs < wlan.intervallMs)
    }

    @Test
    fun aBatchGoesWhenEnoughOrOldEnoughAndNeverDuringThePause() {
        val t = 10 * STUNDE
        assertFalse("nothing waits", Senden.faellig(t, Modus.SPARSAM, 0, null, 0, 0))
        assertFalse("fresh and few", Senden.faellig(t, Modus.SPARSAM, 10, t - 5 * MINUTE, 0, 0))
        assertTrue("old enough", Senden.faellig(t, Modus.SPARSAM, 1, t - 30 * MINUTE, 0, 0))
        assertTrue("old enough for Detailliert", Senden.faellig(t, Modus.DETAILLIERT, 1, t - 15 * MINUTE, 0, 0))
        assertFalse("not yet for Sparsam", Senden.faellig(t, Modus.SPARSAM, 1, t - 15 * MINUTE, 0, 0))
        assertTrue("enough", Senden.faellig(t, Modus.SPARSAM, Senden.GENUG, t - MINUTE, 0, 0))
        // after a failure: not before the pause is over
        assertFalse(Senden.faellig(t, Modus.SPARSAM, 500, t - STUNDE, 1, t - 4 * MINUTE))
        assertTrue(Senden.faellig(t, Modus.SPARSAM, 500, t - STUNDE, 1, t - 5 * MINUTE))
        assertFalse(Senden.faellig(t, Modus.SPARSAM, 500, t - STUNDE, 3, t - 19 * MINUTE))
        // the person asked: the pause does not hold, but nothing waiting is still nothing
        assertTrue(Senden.faellig(t, Modus.SPARSAM, 1, t, 9, t, gedraengt = true))
        assertFalse(Senden.faellig(t, Modus.SPARSAM, 0, null, 0, 0, gedraengt = true))
    }

    @Test
    fun thePauseDoublesUpToTwoHours() {
        assertEquals(0L, Senden.pause(0))
        assertEquals(5 * MINUTE, Senden.pause(1))
        assertEquals(10 * MINUTE, Senden.pause(2))
        assertEquals(80 * MINUTE, Senden.pause(5))
        assertEquals(2 * STUNDE, Senden.pause(6))
        assertEquals(2 * STUNDE, Senden.pause(60))
    }
}
