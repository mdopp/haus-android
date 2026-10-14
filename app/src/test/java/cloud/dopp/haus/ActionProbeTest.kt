package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.widget.ActionProbe
import cloud.dopp.haus.widget.ActionProbe.Reach
import cloud.dopp.haus.widget.Staleness
import cloud.dopp.haus.widget.WidgetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The **active** half of #111: an executed action as a measurement of the
 * connection.
 *
 * The device test in flight mode found the hole. The tap raised its toast, but
 * the tiles did not change — correctly, because `Staleness.THRESHOLD_MS` is 90
 * minutes and a minute of flight mode cannot reach it, and that threshold is
 * right (a tile refreshes every 30 min; a shorter window cries wolf). So the
 * threshold stays and a second input is added: the action the user just
 * triggered is the one measurement taken exactly while they are looking.
 *
 * Three things have to hold, and each of them is a way to get it wrong:
 *
 * 1. **A failed action marks now**, without waiting out the threshold — the user
 *    has just watched nothing happen; that is not a guess about age.
 * 2. **A successful action clears the mark** and dates the tile, even though no
 *    regular refresh ran — we heard from the server this second.
 * 3. **A refusal is not an outage.** The 403 confirm gate is the server speaking;
 *    marking the tile there would invent the very false alarm the threshold
 *    exists to avoid. Same for an action that never left the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionProbeTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val min = 60_000L
    private val hour = 60 * min

    private fun card() = Card("light.flur", "Flur", "light", null, "on", null)

    /** The mark a tile would render right now, or null when it is fresh. */
    private fun mark(id: Int): Staleness.Mark? = Staleness.markFor(
        WidgetCache.fetchedAt(ctx, id), WidgetCache.unreachableSince(ctx, id),
        System.currentTimeMillis(),
    )

    // --- what an outcome measured -------------------------------------------

    @Test fun aCallThatSucceededReachedTheServer() {
        assertEquals(Reach.REACHED, ActionProbe.ofCall(true))
        assertEquals(Reach.REACHED, ActionProbe.of(Result.success(true)))
    }

    /** `ApiClient.call` returns false on a non-2xx — the outage's 500s came this way. */
    @Test fun aCallThatFailedDidNotReachTheServer() {
        assertEquals(Reach.UNREACHABLE, ActionProbe.ofCall(false))
        assertEquals(Reach.UNREACHABLE, ActionProbe.of(Result.success(false)))
        assertEquals(Reach.UNREACHABLE, ActionProbe.ofError(IOException("no route to host")))
        assertEquals(Reach.UNREACHABLE, ActionProbe.of(Result.failure(IOException("timeout"))))
    }

    /**
     * The confirm gate is a **403 from the server** — it answered, and answering
     * is the opposite of being unreachable. A tile must never be marked because
     * the user was asked "wirklich?".
     */
    @Test fun theConfirmGateIsTheServerSpeaking() {
        assertEquals(Reach.REACHED, ActionProbe.ofError(ApiClient.SensitiveException()))
        assertEquals(Reach.REACHED, ActionProbe.of(Result.failure(ApiClient.SensitiveException())))
    }

    /** Nothing configured / nothing paired: the request never left, so nothing was measured. */
    @Test fun anUnsentCallMeasuresNothing() {
        assertEquals(Reach.NOTHING, ActionProbe.ofError(ApiClient.NotConfiguredException()))
        assertEquals(Reach.NOTHING, ActionProbe.ofError(ApiClient.NotPairedException()))
    }

    // --- the state transitions ------------------------------------------------

    /** fresh → the action fails → marked at once, long before the threshold. */
    @Test fun aFailedActionMarksTheTileImmediately() {
        val id = 41
        WidgetCache.clear(ctx, id)
        WidgetCache.putCard(ctx, id, card()) // a fetch a moment ago
        assertNull("a tile that just fetched is fresh", mark(id))

        ActionProbe.record(ctx, id, Reach.UNREACHABLE)

        val m = mark(id)
        assertNotNull("a failed tap marks the tile at once, not in 90 min", m)
        assertEquals(Staleness.UNREACHABLE, m!!.compact)
        assertTrue(m.line, m.line.startsWith(Staleness.UNREACHABLE))
        // …and it did not have to lie about the age to get there: the value's own
        // timestamp is untouched, so the line can still date it honestly.
        assertTrue(m.line, m.line.contains("gerade eben"))
        assertNotNull(WidgetCache.fetchedAt(ctx, id))
    }

    /** marked → the next action succeeds → fresh again, with no refresh in between. */
    @Test fun aSuccessfulActionClearsTheMark() {
        val id = 42
        WidgetCache.clear(ctx, id)
        WidgetCache.putCard(ctx, id, card())
        ActionProbe.record(ctx, id, Reach.UNREACHABLE)
        assertNotNull(mark(id))

        ActionProbe.record(ctx, id, Reach.REACHED)

        assertNull("hearing from the server ends the mark", mark(id))
        assertNull(WidgetCache.unreachableSince(ctx, id))
        assertNotNull("…and it counts as a fetch", WidgetCache.fetchedAt(ctx, id))
    }

    /**
     * A tile whose value has *also* aged out is still marked after a success —
     * by the threshold, not by the probe. Clearing the failure must not reset the
     * clock on a value we genuinely have not refreshed.
     */
    @Test fun clearingTheMarkDoesNotForgeAFreshValue() {
        val id = 43
        WidgetCache.clear(ctx, id)
        WidgetCache.putCard(ctx, id, card())
        val old = System.currentTimeMillis() - 26 * hour
        WidgetCache.noteFetch(ctx, id, old)
        ActionProbe.record(ctx, id, Reach.UNREACHABLE)
        assertEquals(Staleness.UNREACHABLE, mark(id)!!.compact)

        // The success is recorded as a fetch, so the value *is* current again.
        ActionProbe.record(ctx, id, Reach.REACHED)
        assertNull(mark(id))
        // But a mark cleared by hand, with the old fetch time kept, falls straight
        // back to the age rule — the two reasons stay independent.
        WidgetCache.noteFetch(ctx, id, old)
        assertEquals("veraltet · vor 26 Std.", mark(id)!!.line)
    }

    /** A reached server and an unsent call write nothing that could mark a tile. */
    @Test fun neitherAReachedServerNorAnUnsentCallMarksAnything() {
        val id = 44
        WidgetCache.clear(ctx, id)
        WidgetCache.putCard(ctx, id, card())
        for (reach in listOf(Reach.REACHED, Reach.NOTHING)) {
            ActionProbe.record(ctx, id, reach)
            assertNull("$reach", WidgetCache.unreachableSince(ctx, id))
            assertNull("$reach", mark(id))
        }
    }

    /** An app-icon shortcut for a device with no tile (#100) has nothing to mark. */
    @Test fun aTapWithNoTileWritesNothing() {
        val none = AppWidgetManager.INVALID_APPWIDGET_ID
        ActionProbe.record(ctx, none, Reach.UNREACHABLE)
        assertNull(WidgetCache.unreachableSince(ctx, none))
        assertNull(WidgetCache.fetchedAt(ctx, none))
    }

    /** A re-bound tile does not inherit the previous device's failed tap. */
    @Test fun rebindingDropsTheMark() {
        val id = 45
        WidgetCache.putCard(ctx, id, card())
        ActionProbe.record(ctx, id, Reach.UNREACHABLE)
        assertNotNull(WidgetCache.unreachableSince(ctx, id))
        WidgetCache.clear(ctx, id)
        assertNull(WidgetCache.unreachableSince(ctx, id))
        assertNull(mark(id))
    }

    // --- the passive half is untouched ---------------------------------------

    /**
     * The regular refresh behaves exactly as before: a successful fetch dates the
     * tile, a failed one writes nothing at all (it has no way to), and the 90
     * minutes still decide. The probe is an *addition*, not a shorter threshold —
     * a background refresh that fails on a bad handover must still not mark.
     */
    @Test fun aRegularRefreshStillBehavesAsBefore() {
        val id = 46
        WidgetCache.clear(ctx, id)
        assertNull("nothing bound → nothing to date", WidgetCache.fetchedAt(ctx, id))
        assertNull(mark(id))

        WidgetCache.putCard(ctx, id, card()) // a refresh landed
        assertNull("a fresh fetch is not marked", mark(id))

        // A missed cycle, a handover, an hour of nothing: still silent.
        WidgetCache.noteFetch(ctx, id, System.currentTimeMillis() - 89 * min)
        assertNull("89 min is inside the threshold", mark(id))
        assertNull(WidgetCache.unreachableSince(ctx, id))

        // Only the threshold itself marks — and it is still 90 minutes.
        assertEquals(90L * 60 * 1000, Staleness.THRESHOLD_MS)
        WidgetCache.noteFetch(ctx, id, System.currentTimeMillis() - 3 * hour)
        assertEquals("veraltet · vor 3 Std.", mark(id)!!.line)
        assertEquals("vor 3 Std.", mark(id)!!.compact)
    }
}
