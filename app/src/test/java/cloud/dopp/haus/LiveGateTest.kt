package cloud.dopp.haus

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.realtime.LiveGate
import cloud.dopp.haus.realtime.LiveStream
import cloud.dopp.haus.realtime.RealtimeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The live stream runs only while the screen is on (#176, laib ADR 0022 §2):
 * screen on → connect, and once connected resync (watch set, cards); screen
 * off → close, and nothing runs, polls or catches up until the screen is on
 * again. The stream reads card_state and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveGateTest {

    private class Recorder : LiveGate.Stream {
        val log = mutableListOf<String>()
        override fun open() { log.add("open") }
        override fun close() { log.add("close") }
        override fun resync() { log.add("resync") }
        fun count(what: String) = log.count { it == what }
    }

    @Test
    fun withTheScreenOffNothingOpens() {
        val s = Recorder()
        val gate = LiveGate(s)
        gate.screen(on = false, eligible = true)
        assertEquals(emptyList<String>(), s.log)
        assertFalse(gate.running)
    }

    @Test
    fun screenOnOpensAndResyncsOnceConnected() {
        val s = Recorder()
        val gate = LiveGate(s)
        gate.screen(on = true, eligible = true)
        assertEquals(listOf("open"), s.log)
        gate.connected()
        assertEquals(listOf("open", "resync"), s.log)
        assertTrue(gate.running)
    }

    @Test
    fun screenOffClosesTheStream() {
        val s = Recorder()
        val gate = LiveGate(s)
        gate.screen(on = true, eligible = true)
        gate.connected()
        gate.screen(on = false, eligible = true)
        assertEquals("close", s.log.last())
        assertFalse(gate.running)
        assertFalse("a frame after screen-off is not read", gate.accepts())
    }

    @Test
    fun screenOnAgainReconnectsAndResyncs() {
        val s = Recorder()
        val gate = LiveGate(s)
        repeat(2) {
            gate.screen(on = true, eligible = true)
            gate.connected()
            gate.screen(on = false, eligible = true)
        }
        assertEquals(2, s.count("open"))
        assertEquals(2, s.count("resync"))
        assertEquals(2, s.count("close"))
    }

    /** SCREEN_ON and USER_PRESENT both arrive on unlock: one stream, not two. */
    @Test
    fun aSecondScreenOnSignalOpensNoSecondStream() {
        val s = Recorder()
        val gate = LiveGate(s)
        gate.screen(on = true, eligible = true)
        gate.screen(on = true, eligible = true)
        assertEquals(1, s.count("open"))
    }

    /** Unpaired, or no widget that shows live state: there is nothing to stream. */
    @Test
    fun withoutAReasonToListenNothingOpens() {
        val s = Recorder()
        val gate = LiveGate(s)
        gate.screen(on = true, eligible = false)
        assertEquals(emptyList<String>(), s.log)
        gate.screen(on = true, eligible = true)
        gate.screen(on = true, eligible = false)
        assertEquals(listOf("open", "close"), s.log)
    }

    @Test
    fun aDropIsRetriedOnlyWhileTheScreenIsOn() {
        val s = Recorder()
        val gate = LiveGate(s)
        gate.screen(on = true, eligible = true)
        assertTrue("screen on: try again", gate.dropped())
        assertFalse(gate.running)
        gate.screen(on = true, eligible = true)
        gate.screen(on = false, eligible = true)
        assertFalse("screen off: nothing to retry", gate.dropped())
    }

    @Test
    fun aResyncWithoutAStreamDoesNothing() {
        val s = Recorder()
        val gate = LiveGate(s)
        gate.connected()
        assertEquals(emptyList<String>(), s.log)
    }

    @Test
    fun theStreamReadsCardStateAndNothingElse() {
        val frame = """{"entity_id":"light.kueche","card":{"entity_id":"light.kueche","domain":"light","state":"on"}}"""
        val ev = RealtimeProtocol.frame("card_state", frame)
        assertNotNull(ev)
        assertEquals("light.kueche", ev!!.entityId)
        assertEquals("on", ev.card.state)
        for (other in listOf("ha", "notice", "approval", "box", "message", null)) {
            assertNull("$other is not read", RealtimeProtocol.frame(other, frame))
        }
        assertNull("a frame without a card", RealtimeProtocol.frame("card_state", """{"entity_id":"light.x"}"""))
        assertNull("garbage", RealtimeProtocol.frame("card_state", "{"))
    }

    /** The app listens for the screen itself; no service, no alarm keeps it awake. */
    @Test
    fun theAppListensForTheScreen() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        LiveStream.install(app)
        val actions = shadowOf(app as android.app.Application).registeredReceivers
            .flatMap { w -> (0 until w.intentFilter.countActions()).map { w.intentFilter.getAction(it) } }
        assertTrue(actions.contains(Intent.ACTION_SCREEN_ON))
        assertTrue(actions.contains(Intent.ACTION_SCREEN_OFF))
    }
}
