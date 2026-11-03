package cloud.dopp.homes

import cloud.dopp.homes.data.isSensitiveDevice
import cloud.dopp.homes.ui.StartDevices
import cloud.dopp.homes.widget.DeviceIcons
import cloud.dopp.homes.widget.WidgetActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A lock on the start screen (#84): the door lock is a switchable domain (Homes'
 * `callDomains`), and everything that decides how it reads or what it does is pure
 * logic — the gate itself stays on the server (403 → confirm dialog).
 *
 * Two properties matter more than the rest and both are asserted here: the row
 * talks about the **bolt**, never the door (there is no door sensor), and
 * `unknown` — no MQTT retain message yet after a broker or phone restart — is a
 * visibly distinct state that must never read as *abgeschlossen*.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockCardTest {

    /** Every `lock.*` service is sensitive server-side, so the badge is too. */
    @Test fun lockIsSensitiveRegardlessOfDeviceClass() {
        assertTrue(isSensitiveDevice("lock", null))
        assertTrue(isSensitiveDevice("lock", "door"))
        assertTrue(isSensitiveDevice("lock", "irgendwas"))
        // The server's second wholesale domain comes along for free.
        assertTrue(isSensitiveDevice("alarm_control_panel", null))
        // Unchanged: covers stay class-gated, ordinary domains stay open.
        assertTrue(isSensitiveDevice("cover", "garage"))
        assertEquals(false, isSensitiveDevice("cover", "shade"))
        assertEquals(false, isSensitiveDevice("light", null))
        assertEquals(false, isSensitiveDevice(null, "door"))
    }

    @Test fun stateTextNamesTheBoltNotTheDoor() {
        assertEquals("abgeschlossen", StartDevices.lockLabel("locked"))
        assertEquals("aufgeschlossen", StartDevices.lockLabel("unlocked"))
        assertEquals("entriegelt", StartDevices.lockLabel("open"))
        // "zu" / "offen" would claim something about the door itself.
        for (s in listOf("locked", "unlocked", "open", "unknown", null)) {
            val label = StartDevices.lockLabel(s)
            assertNotEquals("zu", label)
            assertNotEquals("offen", label)
        }
    }

    @Test fun unknownIsItsOwnState() {
        val locked = StartDevices.lockLabel("locked")
        for (s in listOf(null, "", "unknown", "  UNKNOWN ")) {
            assertEquals("unbekannt", StartDevices.lockLabel(s))
            assertNotEquals("state $s", locked, StartDevices.lockLabel(s))
        }
        // An unreachable lock is not an unknown one, and neither is a jammed bolt.
        assertEquals("nicht erreichbar", StartDevices.lockLabel("unavailable"))
        assertEquals("klemmt", StartDevices.lockLabel("jammed"))
    }

    @Test fun transitionsAreVisible() {
        assertEquals("schließt ab …", StartDevices.lockLabel("locking"))
        assertEquals("schließt auf …", StartDevices.lockLabel("unlocking"))
        // An unmapped state is shown verbatim rather than guessed at.
        assertEquals("kaputt", StartDevices.lockLabel("kaputt"))
    }

    /**
     * The toggle's direction. `lock.open` unlatches the door — no tap may ever
     * reach it, so the toggle only ever names the bolt.
     */
    @Test fun toggleNeverReachesTheLatch() {
        assertEquals("lock.unlock", WidgetActionReceiver.lockToggleService("locked"))
        assertEquals("lock.unlock", WidgetActionReceiver.lockToggleService(" LOCKED "))
        // Anything that is not a confirmed "locked" secures instead — including
        // the unknown state, where guessing "unlock" would open the house.
        for (s in listOf("unlocked", "open", "jammed", "unknown", "", null)) {
            assertEquals("state $s", "lock.lock", WidgetActionReceiver.lockToggleService(s))
        }
        val all = listOf("locked", "unlocked", "open", "jammed", "unknown", null)
        for (s in all) assertNotEquals("lock.open", WidgetActionReceiver.lockToggleService(s))
    }

    /** The domain glyph is a door lock, not the generic device fallback. */
    @Test fun lockHasItsOwnIcon() {
        assertEquals(R.drawable.ic_door_lock, DeviceIcons.forDomain("lock"))
        assertNotEquals(DeviceIcons.forDomain("lock"), DeviceIcons.forDomain("cover"))
        assertNotEquals(DeviceIcons.forDomain("lock"), DeviceIcons.forDomain("unbekannt"))
    }
}
