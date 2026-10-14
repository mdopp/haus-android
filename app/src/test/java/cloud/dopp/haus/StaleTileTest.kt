package cloud.dopp.haus

import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.widget.TextView
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.widget.ActionProbe
import cloud.dopp.haus.widget.Staleness
import cloud.dopp.haus.widget.WidgetCache
import cloud.dopp.haus.widget.WidgetRender
import cloud.dopp.haus.widget.WidgetRender.Load
import cloud.dopp.haus.widget.WidgetRender.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #111 on the tile itself — the 1×1 tier, where the mark had the least room to
 * fit and the most to prove. Robolectric reports no widget options, so
 * [WidgetRender.build] lands on [Tier.TINY] here (see `TinyTileTest`).
 *
 * What the outage of 29./30.08. looked like: the value stayed on screen, correct
 * to the eye, for over a day. So the two states have to *look* different, and
 * the old value has to survive the difference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StaleTileTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val hour = 60 * 60 * 1000L

    private fun picker(): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun lock(state: String = "locked") =
        Card("lock.haustuer", "Haustür", "lock", null, state, null)

    /** A tile whose last successful fetch has aged out says so — and keeps its value. */
    @Test fun aStaleTileMarksItselfWithoutLosingTheValue() {
        assertEquals(Tier.TINY, WidgetRender.sizeTier(0, 0))
        val card = lock()
        WidgetCache.putCard(ctx, 31, card)
        WidgetCache.noteFetch(ctx, 31, System.currentTimeMillis() - 3 * hour)

        val v = WidgetRender.build(ctx, 31, card, "Haustür", "lock", picker(), Load.LOADED)
            .apply(ctx, null)

        val mark = v.findViewById<TextView>(R.id.w_stale)
        assertEquals(View.VISIBLE, mark.visibility)
        assertEquals("vor 3 Std.", mark.text.toString())
        // Mark, don't replace: the device is still named and still tappable.
        assertEquals("Haustür", v.findViewById<TextView>(R.id.w_name).text.toString())
        assertTrue(v.findViewById<View>(R.id.w_tiny_toggle).hasOnClickListeners())
    }

    /** The same tile, freshly fetched, is exactly the tile it always was. */
    @Test fun aFreshTileCarriesNoMark() {
        val card = lock()
        WidgetCache.putCard(ctx, 32, card) // dates it now
        val v = WidgetRender.build(ctx, 32, card, "Haustür", "lock", picker(), Load.LOADED)
            .apply(ctx, null)
        assertEquals(View.GONE, v.findViewById<View>(R.id.w_stale).visibility)
    }

    /**
     * A brief outage must not mark anything. One missed 30-min host update is a
     * bad minute on the phone, not a dead server — and a mark that shows up on
     * every network handover is a mark nobody reads during the real thing.
     */
    @Test fun aShortInterruptionLeavesTheTileAlone() {
        val card = lock()
        WidgetCache.putCard(ctx, 33, card)
        WidgetCache.noteFetch(ctx, 33, System.currentTimeMillis() - 40 * 60 * 1000L)
        val v = WidgetRender.build(ctx, 33, card, "Haustür", "lock", picker(), Load.LOADED)
            .apply(ctx, null)
        assertEquals(View.GONE, v.findViewById<View>(R.id.w_stale).visibility)
    }

    /**
     * A tile bound a moment ago has no fetch time on record. It must render as an
     * ordinary tile — an unmarked one — not as a suspect.
     */
    @Test fun aFreshlyBoundTileWithNoHistoryIsNotMarked() {
        WidgetCache.clear(ctx, 34)
        val v = WidgetRender.build(ctx, 34, lock(), "Haustür", "lock", picker(), Load.LOADED)
            .apply(ctx, null)
        assertEquals(View.GONE, v.findViewById<View>(R.id.w_stale).visibility)
    }

    /** An empty tile has no value to date, so it says "einrichten" and nothing else. */
    @Test fun anUnconfiguredTileShowsNoAge() {
        WidgetCache.noteFetch(ctx, 35, System.currentTimeMillis() - 30 * hour)
        val v = WidgetRender.build(ctx, 35, null, "—", "", picker(), Load.UNCONFIGURED)
            .apply(ctx, null)
        assertEquals(View.GONE, v.findViewById<View>(R.id.w_stale).visibility)
        assertEquals("einrichten", v.findViewById<TextView>(R.id.w_name).text.toString())
    }

    /**
     * #84's rule, extended to time: a stale `abgeschlossen` claims a security
     * state nobody checked. The wording stops asserting it, and the mark dates it.
     */
    @Test fun aStaleLockIsQuestionedNotAsserted() {
        assertEquals("abgeschlossen?", Staleness.staleValue("abgeschlossen", "lock"))
        val card = lock()
        WidgetCache.putCard(ctx, 36, card)
        WidgetCache.noteFetch(ctx, 36, System.currentTimeMillis() - 30 * hour)
        val v = WidgetRender.build(ctx, 36, card, "Haustür", "lock", picker(), Load.LOADED)
            .apply(ctx, null)
        assertEquals("vor 30 Std.", v.findViewById<TextView>(R.id.w_stale).text.toString())
    }

    /**
     * The mark must exist on every tier the renderer can pick, not only on the
     * one Robolectric happens to report (it reports no widget options, so
     * [WidgetRender.build] lands on 1×1 here). A missing id would silently render
     * a larger tile with no mark at all — exactly the invisible-staleness bug
     * this issue is about.
     */
    @Test fun everyDeviceTierCarriesTheMark() {
        for (layout in listOf(
            R.layout.widget_device_tiny,
            R.layout.widget_device_wide,
            R.layout.widget_device_medium,
        )) {
            val v = android.view.LayoutInflater.from(ctx).inflate(layout, null)
            val mark = v.findViewById<TextView>(R.id.w_stale)
            assertNotNull("layout $layout has no stale mark", mark)
            // …and it is invisible until something is actually stale.
            assertEquals("layout $layout", View.GONE, mark.visibility)
        }
    }

    /**
     * The wording of the larger tiers, where the state has a line of its own: the
     * value stays readable, a lock's reading turns into a question, and the mark
     * names the age in words rather than the 1×1 shorthand.
     */
    @Test fun theLargerTiersKeepTheValueAndNameTheAge() {
        assertEquals("80 %", Staleness.staleValue("80 %", "light"))
        assertEquals("abgeschlossen?", Staleness.staleValue("abgeschlossen", "lock"))
        assertEquals("veraltet · vor 3 Std.", Staleness.mark("vor 3 Std."))
        assertEquals("vor 3 Std.", Staleness.mark("vor 3 Std.", compact = true))
    }

    // --- the tap as the probe -------------------------------------------------

    /**
     * The half the flight-mode test could not reach: a tap that never got through
     * marks the tile **at once**, minutes into an outage, with the 90 minutes
     * untouched. This is what "ich tippe und nichts passiert" now looks like on
     * the homescreen — and the value the user last saw is still there.
     */
    @Test fun aTapThatFailedMarksTheTileAtOnce() {
        val card = lock()
        WidgetCache.clear(ctx, 37)
        WidgetCache.putCard(ctx, 37, card) // fetched a second ago: nothing is old here
        val fresh = WidgetRender.build(ctx, 37, card, "Haustür", "lock", picker(), Load.LOADED)
            .apply(ctx, null)
        assertEquals(View.GONE, fresh.findViewById<View>(R.id.w_stale).visibility)

        ActionProbe.record(ctx, 37, ActionProbe.Reach.UNREACHABLE)

        val v = WidgetRender.build(ctx, 37, card, "Haustür", "lock", picker(), Load.LOADED)
            .apply(ctx, null)
        val mark = v.findViewById<TextView>(R.id.w_stale)
        assertEquals(View.VISIBLE, mark.visibility)
        assertEquals("nicht erreichbar", mark.text.toString())
        // Mark, don't replace — the tile is still the device it was.
        assertEquals("Haustür", v.findViewById<TextView>(R.id.w_name).text.toString())
        assertTrue(v.findViewById<View>(R.id.w_tiny_toggle).hasOnClickListeners())
    }

    /** …and the next tap that gets through takes the mark away again. */
    @Test fun aTapThatWorkedClearsTheMark() {
        val card = lock()
        WidgetCache.clear(ctx, 38)
        WidgetCache.putCard(ctx, 38, card)
        ActionProbe.record(ctx, 38, ActionProbe.Reach.UNREACHABLE)
        ActionProbe.record(ctx, 38, ActionProbe.Reach.REACHED)

        val v = WidgetRender.build(ctx, 38, card, "Haustür", "lock", picker(), Load.LOADED)
            .apply(ctx, null)
        assertEquals(View.GONE, v.findViewById<View>(R.id.w_stale).visibility)
    }
}
