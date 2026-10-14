package cloud.dopp.haus

import cloud.dopp.haus.widget.Staleness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staleness decision behind #111 — the outage of 29./30.08.
 *, where every `/napi/` call answered 500 for over a day while
 * the tiles kept showing unchanged, entirely plausible values.
 *
 * The decision is pure on purpose: it is the piece that must be right before any
 * pixel is drawn, and it is the piece a device cannot be asked about cheaply.
 * Three things are asserted here, and each of them was a way to get it wrong:
 *
 * 1. **Which clock.** The input is the *fetch* time — when we last heard from
 *    the server — not `Card.updatedAtMs`, HA's state time. A lamp untouched for a
 *    week is not stale.
 * 2. **When to keep quiet.** No fetch time on record is not staleness, and a
 *    short blip is not either: the threshold clears two full 30-min update
 *    periods, because a homescreen that flashes at every network handover gets
 *    ignored and then fails to help during the real thing.
 * 3. **What a stale lock may claim.** Same rule as #84: `unknown` must never
 *    look like *abgeschlossen* — and neither may a day-old *abgeschlossen*.
 */
class StalenessTest {

    private val now = 1_756_000_000_000L // fixed "jetzt"; the clock is an input here
    private val min = 60_000L
    private val hour = 60 * min

    // --- the boundary ---------------------------------------------------------

    /** Exactly the threshold is still fresh; one millisecond more is not. */
    @Test fun theThresholdBoundaryIsExact() {
        val t = Staleness.THRESHOLD_MS
        assertEquals(Staleness.State.FRESH, Staleness.state(now - t, now))
        assertEquals(Staleness.State.FRESH, Staleness.state(now - t + 1, now))
        assertEquals(Staleness.State.STALE, Staleness.state(now - t - 1, now))
        assertFalse(Staleness.isStale(now - t, now))
        assertTrue(Staleness.isStale(now - t - 1, now))
    }

    /** The threshold is passed in for tests; the default is the one that ships. */
    @Test fun anExplicitThresholdIsHonoured() {
        assertTrue(Staleness.isStale(now - 11 * min, now, thresholdMs = 10 * min))
        assertFalse(Staleness.isStale(now - 9 * min, now, thresholdMs = 10 * min))
    }

    // --- do not cry wolf ------------------------------------------------------

    /**
     * A dropped connection, a network handover, one missed host update: none of
     * these may mark a tile. `device_widget_info.xml` asks for an update every
     * 30 min and Android may stretch that, so the threshold has to clear two
     * whole periods — otherwise the mark shows up on a healthy phone and is
     * learned away long before the outage that needs it.
     */
    @Test fun aShortInterruptionDoesNotTripTheMark() {
        for (age in listOf(0L, 30 * 1000L, 5 * min, 29 * min, 31 * min, 60 * min)) {
            assertFalse("age ${age / min} min", Staleness.isStale(now - age, now))
        }
        assertTrue("two update periods must fit", Staleness.THRESHOLD_MS >= 2 * 30 * min)
    }

    /** A clock that jumped backwards (time zone, NTP) is not an outage. */
    @Test fun aBackwardsClockIsNotStale() {
        assertEquals(Staleness.State.FRESH, Staleness.state(now + 5 * hour, now))
        assertFalse(Staleness.isStale(now + 5 * hour, now))
        assertNull(Staleness.ageLabel(now + 5 * hour, now))
    }

    // --- nothing to date ------------------------------------------------------

    /**
     * No successful fetch on record — a freshly bound tile, or a cache written
     * before #111 tracked the time. We cannot date what we never timed, so the
     * tile is neither marked nor called fresh.
     */
    @Test fun aMissingFetchTimeIsNeverStale() {
        for (t in listOf(null, 0L, -1L)) {
            assertEquals("fetch $t", Staleness.State.UNKNOWN, Staleness.state(t, now))
            assertFalse("fetch $t", Staleness.isStale(t, now))
            assertNull("fetch $t", Staleness.ageLabel(t, now))
            assertNull("fetch $t", Staleness.mark(Staleness.ageLabel(t, now)))
        }
    }

    /**
     * A tile bound a second ago has no history at all — and must look like an
     * ordinary tile, not like a broken one, from its very first render.
     */
    @Test fun aFreshlyBoundTileWithNoHistoryIsNotMarked() {
        assertEquals(Staleness.State.UNKNOWN, Staleness.state(null, now))
        assertNull(Staleness.mark(Staleness.ageLabel(null, now)))
        // …and once its first fetch lands, it is fresh, not "unknown".
        assertEquals(Staleness.State.FRESH, Staleness.state(now, now))
    }

    // --- the outage itself ----------------------------------------------------

    /** A day of 500s: marked, and the mark says how old the value is. */
    @Test fun aDayOldValueIsMarkedWithItsAge() {
        val fetched = now - 26 * hour
        assertTrue(Staleness.isStale(fetched, now))
        assertEquals("vor 26 Std.", Staleness.ageLabel(fetched, now))
        assertEquals("veraltet · vor 26 Std.", Staleness.mark(Staleness.ageLabel(fetched, now)))
        // 1×1 has room for the age alone.
        assertEquals("vor 26 Std.", Staleness.mark(Staleness.ageLabel(fetched, now), compact = true))
    }

    @Test fun theAgeReadsInGerman() {
        assertEquals("gerade eben", Staleness.ageLabel(now - 30 * 1000L, now))
        assertEquals("vor 1 Min.", Staleness.ageLabel(now - min, now))
        assertEquals("vor 59 Min.", Staleness.ageLabel(now - 59 * min, now))
        assertEquals("vor 2 Std.", Staleness.ageLabel(now - 2 * hour, now))
        assertEquals("vor 47 Std.", Staleness.ageLabel(now - 47 * hour, now))
        assertEquals("vor 2 Tagen", Staleness.ageLabel(now - 48 * hour, now))
        assertEquals("vor 3 Tagen", Staleness.ageLabel(now - 80 * hour, now))
    }

    // --- mark, don't replace --------------------------------------------------

    /**
     * The last known value survives the mark: "vor zwei Stunden war zu" is worth
     * something, an empty error tile is not — which is why [WidgetFallback] is the
     * wrong pattern here and is deliberately not used.
     */
    @Test fun theLastKnownValueIsKeptVerbatim() {
        for (domain in listOf("light", "switch", "cover", "climate", "sensor", "")) {
            assertEquals("domain $domain", "80 %", Staleness.staleValue("80 %", domain))
            assertEquals("domain $domain", "offen", Staleness.staleValue("offen", domain))
        }
    }

    // --- the action as a probe ------------------------------------------------

    /**
     * A tap that never reached the server marks the tile **now**. The threshold
     * answers "has anything arrived lately?", which a minute of flight mode
     * cannot make interesting; this answers "did the thing I just did work?",
     * which it can. No waiting, and no shorter threshold either.
     */
    @Test fun aFailedActionMarksWithoutWaitingOutTheThreshold() {
        val fetched = now - 2 * min // as fresh as a tile ever is
        assertFalse(Staleness.isStale(fetched, now))
        assertNull("no probe, no mark", Staleness.markFor(fetched, null, now))

        val m = Staleness.markFor(fetched, failedAtMs = now, nowMs = now)!!
        assertEquals("nicht erreichbar", m.compact)
        assertEquals("nicht erreichbar · vor 2 Min.", m.line)
        // The wording is not "veraltet": the value is two minutes old, the
        // connection is what is broken, and saying the wrong one is a lie.
        assertTrue(Staleness.unreachable(fetched, now))
    }

    /**
     * The other direction: an action that got through is a fresh `fetchedAt`, so
     * a mark set by an earlier failure is gone — even with no refresh in between.
     */
    @Test fun aLaterSuccessEndsTheMark() {
        val failed = now - 10 * min
        assertTrue(Staleness.unreachable(now - 20 * min, failed))
        // …then something arrived after it.
        assertFalse(Staleness.unreachable(now - min, failed))
        assertNull(Staleness.markFor(now - min, failed, now))
    }

    /** Never asked, never failed: the probe adds nothing on its own. */
    @Test fun noProbeMeansNoOpinion() {
        for (failed in listOf(null, 0L, -1L)) {
            assertFalse("failed $failed", Staleness.unreachable(now - min, failed))
            assertNull("failed $failed", Staleness.markFor(now - min, failed, now))
        }
    }

    /**
     * A tile that has never fetched has no age to show — but a tap that just
     * failed is still worth saying, and the line has to stand without one.
     */
    @Test fun aFailedActionSpeaksEvenWithNothingToDate() {
        val m = Staleness.markFor(null, now, now)!!
        assertEquals("nicht erreichbar", m.line)
        assertEquals("nicht erreichbar", m.compact)
    }

    /**
     * Both reasons at once: the probe wins the wording — it is the newer and the
     * stronger statement — and carries the age along for the tier that has room.
     */
    @Test fun theProbeOutranksTheAge() {
        val fetched = now - 26 * hour
        assertEquals("veraltet · vor 26 Std.", Staleness.markFor(fetched, null, now)!!.line)
        val m = Staleness.markFor(fetched, now, now)!!
        assertEquals("nicht erreichbar · vor 26 Std.", m.line)
        assertEquals("nicht erreichbar", m.compact)
    }

    /** The passive rule is untouched: 90 minutes, and the boundary where it was. */
    @Test fun theProbeDoesNotMoveTheThreshold() {
        assertEquals(90L * 60 * 1000, Staleness.THRESHOLD_MS)
        val t = Staleness.THRESHOLD_MS
        assertNull(Staleness.markFor(now - t, null, now))
        assertEquals("veraltet · vor 1 Std.", Staleness.markFor(now - t - 1, null, now)!!.line)
        for (age in listOf(0L, 5 * min, 29 * min, 31 * min, 60 * min, 89 * min)) {
            assertNull("age ${age / min} min", Staleness.markFor(now - age, null, now))
        }
    }

    /**
     * The one exception, and the reason #84 exists: a stale `abgeschlossen`
     * asserts a security state nobody has verified. It stays readable — it just
     * stops claiming to be current.
     */
    @Test fun aStaleLockNeverAssertsSecurity() {
        val marked = Staleness.staleValue("abgeschlossen", "lock")
        assertNotEquals("abgeschlossen", marked)
        assertTrue(marked, marked.startsWith("abgeschlossen"))
        assertTrue(marked, marked.endsWith("?"))
        // Case/spacing of the domain must not smuggle a lock past the rule.
        assertEquals(marked, Staleness.staleValue("abgeschlossen", " Lock "))
        // Every other lock reading is questioned the same way — no direction is
        // singled out, so nothing is learned as "the ? means locked".
        assertEquals("aufgeschlossen?", Staleness.staleValue("aufgeschlossen", "lock"))
        assertEquals("unbekannt?", Staleness.staleValue("unbekannt", "lock"))
        // …but a value that already asks, or is mid-motion, is not double-marked.
        assertEquals("schließt ab …", Staleness.staleValue("schließt ab …", "lock"))
        assertEquals("klemmt?", Staleness.staleValue("klemmt", "lock"))
    }
}
