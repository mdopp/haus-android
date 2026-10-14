package cloud.dopp.haus

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.widget.ConfirmVerb
import cloud.dopp.haus.widget.LockChoice
import cloud.dopp.haus.widget.LockChooser
import cloud.dopp.haus.widget.WidgetActionReceiver
import cloud.dopp.haus.widget.WidgetRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The 1×1 lock tile's chooser (#92). A tap on that tile used to flip the bolt
 * blind — the tier shows barely any state, so a mistap acted on the front door.
 * Now the tap only ever *asks*, and the answer is the confirmation.
 *
 * Two properties carry the whole security argument and both are asserted here:
 * `lock.open` — the latch, i.e. the door actually opening — is reachable **only**
 * through an explicit pick, and it is offered only for a lock that says it has a
 * latch. Everything that decides either is pure logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockChooserTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** Both bolt directions are always there — no state guessing, no toggle. */
    @Test fun bothBoltDirectionsAreAlwaysOffered() {
        for (features in listOf(null, 0, 1, 2, 3, 255)) {
            val entries = LockChooser.entries(features)
            assertTrue("features $features", LockChoice.UNLOCK in entries)
            assertTrue("features $features", LockChoice.LOCK in entries)
        }
        assertEquals(listOf(LockChoice.UNLOCK, LockChoice.LOCK), LockChooser.BOLT)
    }

    /**
     * The latch is a capability, not an assumption: HA advertises it as bit 0 of
     * `supported_features`. No bit — or no card at all — means no offer to open
     * the house, which is also what an old server (no attribute) gets.
     */
    @Test fun theDoorOpensOnlyWhereTheLockSaysItHasALatch() {
        assertEquals(listOf(LockChoice.UNLOCK, LockChoice.LOCK, LockChoice.OPEN), LockChooser.entries(1))
        assertEquals(listOf(LockChoice.UNLOCK, LockChoice.LOCK, LockChoice.OPEN), LockChooser.entries(3))
        assertTrue(LockChooser.offersOpen(1 or 8))
        // No latch bit ⇒ two entries, whatever else the lock can do.
        for (features in listOf(null, 0, 2, 4, 8, 6)) {
            val entries = LockChooser.entries(features)
            assertFalse("features $features", LockChoice.OPEN in entries)
            assertEquals("features $features", LockChooser.BOLT, entries)
            assertFalse("features $features", LockChooser.offersOpen(features))
        }
    }

    /**
     * The latch never becomes an ordinary row: the chooser lists the bolt and
     * sets "Tür öffnen" apart, so the entry the finger lands on by accident can
     * never be the one that opens the door.
     */
    @Test fun theLatchIsNeverAPlainListRow() {
        assertFalse(LockChoice.OPEN in LockChooser.BOLT)
        for (choice in LockChooser.BOLT) assertNotEquals("lock.open", choice.service)
        // …and when it is offered, it is the last entry, never the first.
        assertEquals(LockChoice.OPEN, LockChooser.entries(1).last())
    }

    /**
     * No toggle path reaches the latch. The tiny tile no longer toggles a lock at
     * all, and the receiver's toggle helper — the only place that derives a lock
     * service from state — never names `lock.open` for any reading whatsoever.
     */
    @Test fun noToggleReachesTheLatch() {
        val states = listOf(
            "locked", "unlocked", "open", "opening", "locking", "unlocking",
            "jammed", "unavailable", "unknown", "", "  OPEN ", "kaputt", null,
        )
        for (s in states) {
            assertNotEquals("state $s", "lock.open", WidgetActionReceiver.lockToggleService(s))
            // A lock tap on 1×1 does not act — it asks.
            assertEquals(
                "state $s",
                WidgetActionReceiver.OP_LOCK_CHOOSE,
                WidgetRender.tinyToggleOp("lock", card(s)),
            )
        }
        assertNotEquals(
            WidgetActionReceiver.OP_TOGGLE,
            WidgetRender.tinyToggleOp("lock", null),
        )
        // The chooser op is inert by construction: it carries no service.
        assertFalse(WidgetActionReceiver.OP_LOCK_CHOOSE.contains("."))
    }

    /** Every other domain — and the rest of the tile — keeps what it had (#57). */
    @Test fun otherDomainsKeepTheirToggle() {
        assertEquals(WidgetActionReceiver.OP_TOGGLE, WidgetRender.tinyToggleOp("light", null))
        assertEquals(WidgetActionReceiver.OP_TOGGLE, WidgetRender.tinyToggleOp("switch", null))
        assertEquals(WidgetActionReceiver.OP_COVER_OPEN, WidgetRender.tinyToggleOp("cover", null))
        assertEquals(WidgetActionReceiver.OP_REFRESH, WidgetRender.tinyToggleOp("sensor", null))
    }

    /** The wording is #85's, not a parallel set of words invented here. */
    @Test fun theWordsComeFromTheConfirmTable() {
        assertEquals(ConfirmVerb.UNLOCK, LockChoice.UNLOCK.verb)
        assertEquals(ConfirmVerb.LOCK, LockChoice.LOCK.verb)
        assertEquals(ConfirmVerb.UNLATCH, LockChoice.OPEN.verb)
        assertEquals(R.string.widget_confirm_unlatch, LockChoice.OPEN.labelRes)

        val unlock = ctx.getString(LockChoice.UNLOCK.labelRes)
        val lock = ctx.getString(LockChoice.LOCK.labelRes)
        val open = ctx.getString(LockChoice.OPEN.labelRes)
        assertEquals("Aufschließen", unlock)
        assertEquals("Abschließen", lock)
        // The bolt entry and the door entry must not read alike — a bare "Öffnen"
        // next to "Aufschließen" is exactly the confusion this dialog exists to
        // prevent.
        assertEquals("Tür öffnen", open)
        assertNotEquals(unlock, open)
        assertNotEquals(lock, open)
    }

    /**
     * The chooser sits behind the device lock, and that is the point: without a
     * keyguard dismissal a stolen, locked phone cannot open the door from the
     * homescreen. Asserted over the manifest itself, since nothing about a
     * `showWhenLocked` attribute would fail to compile.
     */
    @Test fun nothingBypassesTheKeyguard() {
        val manifest = File(locate("src/main"), "AndroidManifest.xml").readText()
        for (flag in listOf("showWhenLocked", "turnScreenOn", "dismissKeyguard")) {
            assertFalse("$flag must not appear in the manifest (#92)", manifest.contains(flag))
        }
        val sources = File(locate("src/main"), "java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
        for (src in sources) {
            val text = src.readText()
            for (call in listOf("setShowWhenLocked", "requestDismissKeyguard", "FLAG_SHOW_WHEN_LOCKED")) {
                assertFalse("${src.name} must not call $call (#92)", text.contains(call))
            }
        }
    }

    private fun card(state: String?) = Card(
        entityId = "lock.home",
        name = "Haustür",
        domain = "lock",
        deviceClass = null,
        state = state,
        unit = null,
    )

    /** Unit tests run from the module dir, but don't depend on it. */
    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, rel), File(dir, "app/$rel"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $rel from ${File("").absolutePath}")
    }
}
