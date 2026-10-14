package cloud.dopp.haus

import android.app.PendingIntent
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.widget.ActionProbe
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.WidgetCache
import cloud.dopp.haus.widget.WidgetRender
import cloud.dopp.haus.widget.WidgetRender.Load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #120 — **the last known state survives a lost connection.**
 *
 * Reported in flight mode: a lamp tile was correctly marked as unreachable (the
 * notice showed, the icon made room for it) but rendered *grey* — the tile no
 * longer said "an seit heute Mittag", it said "aus". Nobody had measured that.
 *
 * The same rule this repo has now learned three times: a value whose truth is
 * unknown is **unknown**, never `false` (#84 — `unknown` must not look
 * *abgeschlossen*; #111 — mark, don't replace). A tile that paints the absence
 * of data as a state does not merely fail to inform, it misinforms.
 *
 * Two mechanisms carry it, both pure and both asserted here:
 * [DeviceWidgetProvider.drawn] (a failed fetch keeps drawing the cached card)
 * and [WidgetRender.accentFor] (no card → neutral; stale → keeps its colour,
 * except the lock).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LastKnownStateTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun picker(): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun lamp(on: Boolean = true, brightness: Int? = 200) =
        Card("light.wohnzimmer", "Stehlampe", "light", null, if (on) "on" else "off", null, brightness)

    private fun lock(state: String = "locked") =
        Card("lock.haustuer", "Haustür", "lock", null, state, null)

    /** The tint the tile actually painted on its icon. */
    private fun tint(view: View): Any? = view.findViewById<ImageView>(R.id.w_tiny_toggle).colorFilter

    private fun filter(color: Int): Any = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)

    // --- which card a finished refresh draws (the provider's half) ------------

    /** A card that arrived is the card that is shown. Nothing surprising here. */
    @Test fun aSuccessfulFetchDrawsTheNewState() {
        val fresh = lamp(on = true)
        val drawn = DeviceWidgetProvider.drawn(fresh, lamp(on = false))
        assertEquals(fresh, drawn.card)
        assertEquals(Load.LOADED, drawn.load)
    }

    /**
     * The heart of the ticket: the fetch failed, so we learned **nothing** about
     * the device — and a tile that knows nothing new keeps showing what it knew.
     */
    @Test fun aFailedFetchKeepsTheLastKnownCard() {
        val known = lamp(on = true)
        val drawn = DeviceWidgetProvider.drawn(null, known)
        assertEquals(known, drawn.card)
        // Not FAILED: there is a value on the tile, and the staleness mark — not
        // the load state — is what says how much to trust it (#111).
        assertEquals(Load.LOADED, drawn.load)
    }

    /** Never fetched, and the fetch failed: the state is genuinely unknown. */
    @Test fun aTileThatNeverLoadedSaysSoInsteadOfGuessing() {
        val drawn = DeviceWidgetProvider.drawn(null, null)
        assertEquals(null, drawn.card)
        assertEquals(Load.FAILED, drawn.load)
    }

    // --- what that card is painted with (the renderer's half) ----------------

    /**
     * No card is not "aus". Every domain, because every domain had the defect —
     * and on the lock it would be the worst of them.
     */
    @Test fun aMissingCardIsNeverPaintedAsOff() {
        for (domain in listOf("light", "switch", "cover", "climate", "lock", "sensor")) {
            val missing = WidgetRender.accentFor(domain, null, on = false)
            val measuredOff = WidgetRender.accentFor(domain, offCard(domain), on = false)
            assertNotEquals("$domain: missing data wears the off colour", measuredOff, missing)
            // …and it certainly does not borrow the *on* colour either.
            assertNotEquals(
                "$domain: missing data wears the on colour",
                WidgetRender.accentFor(domain, offCard(domain), on = true), missing,
            )
        }
        // One neutral for all of them: absence of data has no domain.
        val neutral = WidgetRender.accentFor("light", null, on = false)
        for (domain in listOf("switch", "cover", "climate", "lock", "sensor")) {
            assertEquals(domain, neutral, WidgetRender.accentFor(domain, null, on = false))
        }
    }

    private fun offCard(domain: String) =
        Card("$domain.x", "X", domain, null, if (domain == "lock") "unlocked" else "off", null)

    /**
     * Mark, don't replace — now including the colour (#120 over #111). A lamp
     * that was on stays yellow while the tile says it has not heard from the
     * server; dropping the colour is what made the report read "aus".
     */
    @Test fun aStaleTileKeepsTheColourOfTheStateItLastKnew() {
        for (domain in listOf("light", "switch", "cover", "climate")) {
            val card = Card("$domain.x", "X", domain, null, "on", null)
            assertEquals(
                "$domain lost its colour when it went stale",
                WidgetRender.accentFor(domain, card, on = true, stale = false),
                WidgetRender.accentFor(domain, card, on = true, stale = true),
            )
        }
    }

    /**
     * The exception, unchanged since #84: a stale `abgeschlossen` may not wear
     * the calm green — it would assert a security state nobody verified. It
     * drops to the same neutral a card-less tile gets, which claims nothing.
     */
    @Test fun aStaleLockGivesUpItsColour() {
        val fresh = WidgetRender.accentFor("lock", lock(), on = false, stale = false)
        val stale = WidgetRender.accentFor("lock", lock(), on = false, stale = true)
        assertNotEquals(fresh, stale)
        assertEquals(WidgetRender.accentFor("lock", null, on = false), stale)
        assertEquals(WidgetRender.lockAccent("locked"), fresh)
    }

    // --- and the same three cases end-to-end on a rendered tile ---------------

    /**
     * The device report, rebuilt: a lamp that is on, a tap that never reached the
     * server, and the tile afterwards. It says "nicht erreichbar" **and** it is
     * still the yellow of a lit lamp, with its value intact.
     */
    @Test fun anUnreachableLampStaysLit() {
        val card = lamp(on = true)
        WidgetCache.clear(ctx, 61)
        WidgetCache.putCard(ctx, 61, card)
        val lit = tint(WidgetRender.build(ctx, 61, card, "Stehlampe", "light", picker(), Load.LOADED).apply(ctx, null))

        ActionProbe.record(ctx, 61, ActionProbe.Reach.UNREACHABLE)
        val v = WidgetRender.build(ctx, 61, card, "Stehlampe", "light", picker(), Load.LOADED).apply(ctx, null)

        val mark = v.findViewById<TextView>(R.id.w_stale)
        assertEquals(View.VISIBLE, mark.visibility)
        assertEquals("nicht erreichbar", mark.text.toString())
        assertEquals("the marked lamp lost its colour", lit, tint(v))
        assertEquals("Stehlampe", v.findViewById<TextView>(R.id.w_name).text.toString())
    }

    /** …and it is not the tint an actually-off lamp would have. */
    @Test fun anUnreachableLampIsNotTheTintOfAnOffOne() {
        WidgetCache.clear(ctx, 62)
        WidgetCache.putCard(ctx, 62, lamp(on = false))
        val off = tint(WidgetRender.build(ctx, 62, lamp(on = false), "Stehlampe", "light", picker(), Load.LOADED).apply(ctx, null))

        WidgetCache.clear(ctx, 63)
        WidgetCache.putCard(ctx, 63, lamp(on = true))
        ActionProbe.record(ctx, 63, ActionProbe.Reach.UNREACHABLE)
        val v = WidgetRender.build(ctx, 63, lamp(on = true), "Stehlampe", "light", picker(), Load.LOADED).apply(ctx, null)
        assertNotEquals(off, tint(v))
    }

    /**
     * A tile that never loaded and could not load: neutral. Whatever it shows,
     * it may not show a state — nobody has read one.
     */
    @Test fun aNeverLoadedTileRendersNeutral() {
        WidgetCache.clear(ctx, 64)
        val v = WidgetRender.build(ctx, 64, null, "Stehlampe", "light", picker(), Load.FAILED).apply(ctx, null)
        assertEquals(filter(WidgetRender.accentFor("light", null, on = false)), tint(v))
        assertNotEquals(
            filter(WidgetRender.accentFor("light", offCard("light"), on = false)), tint(v),
        )
        // Nothing is dated either: there is no value to be old (#111).
        assertEquals(View.GONE, v.findViewById<View>(R.id.w_stale).visibility)
    }
}
