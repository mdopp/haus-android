package cloud.dopp.haus

import cloud.dopp.haus.realtime.RealtimeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM coverage for the live stream's protocol helpers (#48, #176): the
 * `card_state` frame parse and the exponential reconnect backoff. Robolectric only for `org.json`
 * (no Android components touched).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealtimeProtocolTest {

    @Test
    fun parsesCardStateFrame() {
        val data = """
            {"entity_id": "light.kitchen",
             "card": {"entity_id": "light.kitchen", "name": "Küche",
                      "domain": "light", "state": "on", "brightness": 128}}
        """.trimIndent()
        val ev = RealtimeProtocol.parseCardState(data)!!
        assertEquals("light.kitchen", ev.entityId)
        assertEquals("Küche", ev.card.name)
        assertEquals("light", ev.card.domain)
        assertTrue(ev.card.isOn)
        assertEquals(50, ev.card.brightnessPct) // 128/255 ≈ 50%
    }

    @Test
    fun fallsBackToCardEntityIdWhenTopLevelMissing() {
        val data = """{"card": {"entity_id": "cover.garage", "domain": "cover", "state": "open"}}"""
        val ev = RealtimeProtocol.parseCardState(data)!!
        assertEquals("cover.garage", ev.entityId)
        assertTrue(ev.card.isOn)
    }

    @Test
    fun returnsNullForMalformedOrEmpty() {
        assertNull(RealtimeProtocol.parseCardState(null))
        assertNull(RealtimeProtocol.parseCardState(""))
        assertNull(RealtimeProtocol.parseCardState("not json"))
        // No card object → nothing to render.
        assertNull(RealtimeProtocol.parseCardState("""{"entity_id": "light.x"}"""))
        // No entity_id anywhere → can't route it.
        assertNull(RealtimeProtocol.parseCardState("""{"card": {"domain": "light"}}"""))
    }

    @Test
    fun backoffGrowsExponentiallyThenCaps() {
        assertEquals(2_000L, RealtimeProtocol.backoffMillis(0))
        assertEquals(4_000L, RealtimeProtocol.backoffMillis(1))
        assertEquals(8_000L, RealtimeProtocol.backoffMillis(2))
        assertEquals(16_000L, RealtimeProtocol.backoffMillis(3))
        assertEquals(32_000L, RealtimeProtocol.backoffMillis(4))
        // Caps at 60s and never overflows for large/absurd attempt counts.
        assertEquals(60_000L, RealtimeProtocol.backoffMillis(5))
        assertEquals(60_000L, RealtimeProtocol.backoffMillis(20))
        assertEquals(60_000L, RealtimeProtocol.backoffMillis(999))
    }

    @Test
    fun healthySessionOnlyAfterStableThreshold() {
        val open = 1_000_000L
        // Never opened (0) is never healthy, whatever "now" is.
        org.junit.Assert.assertFalse(RealtimeProtocol.healthySession(0L, open + 999_999L))
        // A flap that closes before the threshold is NOT healthy → backoff keeps climbing.
        org.junit.Assert.assertFalse(
            RealtimeProtocol.healthySession(open, open + RealtimeProtocol.STABLE_SESSION_MS - 1),
        )
        // At/after the threshold the session counts as healthy → backoff may reset.
        assertTrue(RealtimeProtocol.healthySession(open, open + RealtimeProtocol.STABLE_SESSION_MS))
        assertTrue(RealtimeProtocol.healthySession(open, open + 5 * RealtimeProtocol.STABLE_SESSION_MS))
    }

    /**
     * Haus pings an open stream every 90 s (live.go:28). A proxy that cuts idle
     * streams before that ends every session early; such a session must not
     * count as healthy, or the backoff resets and the app reconnects — and
     * re-reads every widget — about once a minute.
     */
    @Test
    fun aHealthySessionOutlastsHaussPing() {
        val haussPing = 90_000L
        assertTrue(RealtimeProtocol.STABLE_SESSION_MS > haussPing)
        val open = 1_000_000L
        org.junit.Assert.assertFalse(RealtimeProtocol.healthySession(open, open + haussPing))
    }

    /**
     * `POST /napi/portal/watch` answers 503 while Home Assistant is down
     * (live.go:92-95). Without its watch set the open stream carries no frames,
     * so the post is tried again, backing off, and gives up after a bound; every
     * other answer is final.
     */
    @Test
    fun aWatchPostHausCannotTakeYetIsRetriedWithBackoff() {
        val rp = "cloud.dopp.haus.realtime.RealtimeProtocol"
        val delays = (0 until 12).map { Reach.call(rp, "watchRetryMs", 503, it) as Long? }
        assertEquals(2_000L, delays[0])
        val tried = delays.takeWhile { it != null }.map { it!! }
        assertTrue("no retry at all", tried.size >= 3)
        assertTrue("does not back off: $tried", tried.zipWithNext().all { (a, b) -> b >= a } && tried.last() > tried.first())
        assertTrue("retries forever: $delays", delays.last() == null)
        assertTrue("waits longer than a minute: $tried", tried.all { it <= 60_000L })
        listOf(200, 204, 400, 401, 404, 500, 0).forEach {
            assertNull("retries a $it", Reach.call(rp, "watchRetryMs", it, 0))
        }
    }
}
