package cloud.dopp.haus

import android.app.PendingIntent
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cloud.dopp.haus.widget.WidgetRender
import cloud.dopp.haus.widget.WidgetRender.Load
import cloud.dopp.haus.widget.WidgetRender.Tier
import cloud.dopp.haus.widget.WidgetRender.TinyTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The 1×1 tile, both defects the user hit on v2.31.0:
 *
 * **#94** — a fresh tile has no bound entity, so the only sensible thing a tap can
 * do is open the picker. Before, the name opened Haus's pages and the icon fired a
 * toggle op with nothing behind it. The wiring is now a pure function of the load
 * state, asserted here for all four values.
 *
 * **#95** — the confirm badge is gone from this tier for good. The second half of
 * that is the guard that it did **not** bleed upward: the larger tiers still show
 * it for sensitive devices.
 *
 * **#104** — and the tap has to reach a **child**: v2.32.1 armed `w_root`, the
 * RemoteViews root, which the launcher hosts in a container of its own and does
 * not reliably click through, so the empty tile looked right and did nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TinyTileTest {

    // --- #94: load state → tap wiring ----------------------------------------

    @Test fun unboundTileIsOneSetupButton() {
        assertEquals(TinyTap.SETUP, WidgetRender.tinyTap(Load.UNCONFIGURED))
    }

    /** A bound entity keeps its wiring whatever its state fetch is doing. */
    @Test fun aBoundTileActsWhileItLoadsAndAfterAFailedFetch() {
        for (load in listOf(Load.LOADING, Load.FAILED, Load.LOADED)) {
            assertEquals("load $load", TinyTap.BOUND, WidgetRender.tinyTap(load))
        }
    }

    @Test fun everyLoadStateIsMapped() {
        for (load in Load.entries) assertNotNull(WidgetRender.tinyTap(load))
    }

    /**
     * The whole point of #94, corrected by #104: on an unconfigured tile every
     * visible region opens the picker — and the click lives on **child** views,
     * because the launcher hosts the RemoteViews root in a container of its own
     * and does not reliably deliver a click set there. Arming only `w_root` is
     * exactly what shipped in v2.32.1, where the tile said "einrichten" and a tap
     * did nothing at all.
     */
    @Test fun unconfiguredTileOpensThePickerFromEveryVisibleChild() {
        val ctx = RuntimeEnvironment.getApplication()
        // Robolectric reports no widget options → minW/minH 0 → TINY.
        assertEquals(Tier.TINY, WidgetRender.sizeTier(0, 0))
        val v = WidgetRender.build(ctx, 1, null, "—", "", picker(), Load.UNCONFIGURED)
            .apply(ctx, null)

        assertFalse("the tap must not live on the root alone", WidgetRender.TINY_SETUP_TARGETS.isEmpty())
        assertFalse("w_root is the RemoteViews root, not a target", R.id.w_root in WidgetRender.TINY_SETUP_TARGETS)
        for (id in WidgetRender.TINY_SETUP_TARGETS) {
            assertTrue("target $id is inert", v.findViewById<View>(id).hasOnClickListeners())
        }

        // …and nothing visible is left disarmed, so there is no dead region.
        val body = v.findViewById<ViewGroup>(R.id.w_tiny_body)
        for (i in 0 until body.childCount) {
            val child = body.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            assertTrue("visible child #$i is inert", child.hasOnClickListeners())
        }

        // The body fills the tile edge to edge — no padded border the tap misses.
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, body.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, body.layoutParams.height)
        assertEquals(0, body.paddingLeft + body.paddingTop + body.paddingRight + body.paddingBottom)

        // Say it is empty, and show no state that isn't there.
        assertEquals("einrichten", v.findViewById<TextView>(R.id.w_name).text.toString())
        assertEquals(View.GONE, v.findViewById<View>(R.id.w_bar).visibility)
    }

    /** A bound tile is unchanged: the name opens Haus's page, the icon acts. */
    @Test fun boundTileKeepsItsTwoTapTargets() {
        val ctx = RuntimeEnvironment.getApplication()
        val v = WidgetRender.build(ctx, 2, null, "Flur", "light", picker(), Load.LOADING)
            .apply(ctx, null)

        assertTrue(v.findViewById<View>(R.id.w_name).hasOnClickListeners())
        assertTrue(v.findViewById<View>(R.id.w_tiny_toggle).hasOnClickListeners())
        assertEquals("Flur", v.findViewById<TextView>(R.id.w_name).text.toString())
    }

    // --- #95: the badge is gone from 1×1, and only from 1×1 -------------------

    @Test fun theTinyLayoutHasNoBadgeLeftToShow() {
        val ctx = RuntimeEnvironment.getApplication()
        val tiny = LayoutInflater.from(ctx).inflate(R.layout.widget_device_tiny, null)
        // Removed, not hidden — the width has to be free for the name.
        assertNull(tiny.findViewById<View>(R.id.w_lock))
        assertNotNull(tiny.findViewById<View>(R.id.w_name))
    }

    /** The guard against this removal bleeding upward: the larger tiers keep it. */
    @Test fun theLargerTiersStillCarryTheBadge() {
        val ctx = RuntimeEnvironment.getApplication()
        for (layout in listOf(
            R.layout.widget_device_small,
            R.layout.widget_device_wide,
            R.layout.widget_device_medium,
        )) {
            val v = LayoutInflater.from(ctx).inflate(layout, null)
            assertNotNull("layout $layout", v.findViewById<View>(R.id.w_lock))
        }
        for (tier in listOf(Tier.SMALL, Tier.WIDE, Tier.MEDIUM)) {
            assertTrue("tier $tier", WidgetRender.showsLockBadge(tier, sensitive = true))
            assertFalse("tier $tier", WidgetRender.showsLockBadge(tier, sensitive = false))
        }
    }

    /** No domain-dependent exception: on 1×1 it is gone, full stop. */
    @Test fun theTinyTierNeverShowsTheBadge() {
        assertFalse(WidgetRender.showsLockBadge(Tier.TINY, sensitive = true))
        assertFalse(WidgetRender.showsLockBadge(Tier.TINY, sensitive = false))
    }

    private fun picker(): PendingIntent = PendingIntent.getActivity(
        RuntimeEnvironment.getApplication(), 0, Intent(),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
