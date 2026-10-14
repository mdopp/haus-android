package cloud.dopp.haus

import cloud.dopp.haus.widget.ConfirmDirection
import cloud.dopp.haus.widget.ConfirmVerb
import cloud.dopp.haus.widget.ConfirmWording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The confirm dialog's verb (#85). It used to come from
 * `service.contains("close")`, which quietly labelled `lock.lock` as *öffnen*
 * and every `alarm_arm_*` likewise. The mapping is pure JVM logic, so the whole
 * table is testable without a device.
 */
class ConfirmVerbTest {

    @Test
    fun lockKeepsItsOwnVerbs() {
        // "abschließen" is not "schließen", and the latch is not the bolt.
        assertEquals(ConfirmVerb.LOCK, ConfirmVerb.of("lock.lock"))
        assertEquals(ConfirmVerb.UNLOCK, ConfirmVerb.of("lock.unlock"))
        assertEquals(ConfirmVerb.UNLATCH, ConfirmVerb.of("lock.open"))
        assertNotEquals(ConfirmVerb.CLOSE, ConfirmVerb.of("lock.lock"))
        assertNotEquals(ConfirmVerb.of("lock.unlock"), ConfirmVerb.of("lock.open"))
    }

    @Test
    fun coverOpensAndCloses() {
        assertEquals(ConfirmVerb.CLOSE, ConfirmVerb.of("cover.close_cover"))
        assertEquals(ConfirmVerb.OPEN, ConfirmVerb.of("cover.open_cover"))
        // Stopping moves it nowhere we can name.
        assertEquals(ConfirmVerb.NEUTRAL, ConfirmVerb.of("cover.stop_cover"))
    }

    @Test
    fun alarmPanelArmsAndDisarms() {
        assertEquals(ConfirmVerb.DISARM, ConfirmVerb.of("alarm_control_panel.alarm_disarm"))
        for (mode in listOf("away", "home", "night", "vacation", "custom_bypass")) {
            assertEquals(ConfirmVerb.ARM, ConfirmVerb.of("alarm_control_panel.alarm_arm_$mode"))
        }
    }

    @Test
    fun toggleClaimsNoDirection() {
        for (s in listOf("light.toggle", "switch.toggle", "cover.toggle", "lock.toggle")) {
            assertEquals(ConfirmVerb.TOGGLE, ConfirmVerb.of(s))
            assertEquals(ConfirmDirection.TOGGLING, ConfirmVerb.of(s).direction)
        }
    }

    @Test
    fun unknownServiceStaysNeutral() {
        val unknown = listOf(
            "vacuum.start", "script.turn_on", "not_a_service", "", "   ", "lock.", ".lock", null,
        )
        for (s in unknown) {
            assertEquals("verb for $s", ConfirmVerb.NEUTRAL, ConfirmVerb.of(s))
            assertEquals(ConfirmDirection.UNKNOWN, ConfirmVerb.of(s).direction)
        }
    }

    @Test
    fun directionsMatchTheAction() {
        val securing = listOf("lock.lock", "cover.close_cover", "alarm_control_panel.alarm_arm_away")
        for (s in securing) assertEquals(s, ConfirmDirection.SECURING, ConfirmVerb.of(s).direction)
        val unsecuring = listOf(
            "lock.unlock", "lock.open", "cover.open_cover", "alarm_control_panel.alarm_disarm",
        )
        for (s in unsecuring) assertEquals(s, ConfirmDirection.UNSECURING, ConfirmVerb.of(s).direction)
    }

    @Test
    fun serviceIsReadCaseAndWhitespaceInsensitively() {
        assertEquals(ConfirmVerb.LOCK, ConfirmVerb.of("  LOCK.Lock "))
    }

    @Test
    fun everyVerbHasItsOwnWording() {
        val verbs = ConfirmVerb.values().toList()
        assertEquals(verbs.size, verbs.map { ConfirmWording.verbRes(it) }.distinct().size)
        // The button label may repeat ("Öffnen" for cover open and the latch)
        // but must never carry the opposite direction's label.
        for (a in verbs) {
            for (b in verbs) {
                if (a.direction == ConfirmDirection.SECURING && b.direction == ConfirmDirection.UNSECURING) {
                    assertNotEquals(ConfirmWording.positiveRes(a), ConfirmWording.positiveRes(b))
                }
            }
        }
    }

    @Test
    fun positiveButtonFollowsTheStatedVerb() {
        // Same lookup key for question and button — they cannot drift apart.
        assertEquals(R.string.widget_confirm_lock, ConfirmWording.positiveRes(ConfirmVerb.of("lock.lock")))
        assertEquals(R.string.widget_verb_lock, ConfirmWording.verbRes(ConfirmVerb.of("lock.lock")))
        assertEquals(
            R.string.widget_confirm_disarm,
            ConfirmWording.positiveRes(ConfirmVerb.of("alarm_control_panel.alarm_disarm")),
        )
        assertEquals(
            R.string.widget_confirm_neutral,
            ConfirmWording.positiveRes(ConfirmVerb.of("vacuum.start")),
        )
    }
}
