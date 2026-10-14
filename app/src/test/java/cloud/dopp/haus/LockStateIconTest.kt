package cloud.dopp.haus

import cloud.dopp.haus.widget.DeviceIcons
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
 * The lock tile's state glyph (#91). #84 gave the lock its own words —
 * `abgeschlossen` / `aufgeschlossen` / `entriegelt` — and its own accent colour,
 * but a single domain icon for all of them: a tile on a home screen is read in
 * passing, and *aufgeschlossen* against *entriegelt* is exactly the distinction
 * that gets lost when only the word underneath changes.
 *
 * The wording stays as it is (an explicit decision), so the **icon** has to carry
 * the state. Two properties are asserted here: the mapping is a pure function
 * whose states really do land on different drawables, and the glyphs stay
 * tintable — the tiles paint them through `setColorFilter`, so a hardcoded hue
 * would either vanish or lie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockStateIconTest {

    private val drawableDir = locate("src/main/res/drawable")

    /** The three real states a user acts on must be three different pictures. */
    @Test fun lockedUnlockedAndOpenAreThreeDifferentGlyphs() {
        val locked = DeviceIcons.forLockState("locked")
        val unlocked = DeviceIcons.forLockState("unlocked")
        val open = DeviceIcons.forLockState("open")
        assertEquals(R.drawable.ic_lock_locked, locked)
        assertEquals(R.drawable.ic_lock_unlocked, unlocked)
        assertEquals(R.drawable.ic_lock_open, open)
        assertEquals("three states, three glyphs", 3, setOf(locked, unlocked, open).size)
        // Whitespace and casing come off the wire in every shape (#84).
        assertEquals(locked, DeviceIcons.forLockState(" LOCKED "))
        assertEquals(open, DeviceIcons.forLockState("Open"))
    }

    /**
     * `unknown` — no MQTT retain message yet after a broker or phone restart —
     * must assert nothing, the same rule `lockAccent` follows with its neutral
     * grey. Above all it must not borrow the `locked` picture.
     */
    @Test fun unknownClaimsNothing() {
        val locked = DeviceIcons.forLockState("locked")
        val unlocked = DeviceIcons.forLockState("unlocked")
        val open = DeviceIcons.forLockState("open")
        for (s in listOf(null, "", "  ", "unknown", " UNKNOWN ", "none", "unavailable", "kaputt")) {
            val icon = DeviceIcons.forLockState(s)
            assertEquals("state $s", R.drawable.ic_lock_unknown, icon)
            assertNotEquals("state $s must not read as locked", locked, icon)
            assertNotEquals("state $s", unlocked, icon)
            assertNotEquals("state $s", open, icon)
        }
    }

    /** A jammed bolt is its own picture — not "locked", not "unknown". */
    @Test fun jammedIsItsOwnGlyph() {
        val jammed = DeviceIcons.forLockState("jammed")
        assertEquals(R.drawable.ic_lock_jammed, jammed)
        for (s in listOf("locked", "unlocked", "open", "unknown", null)) {
            assertNotEquals("state $s", jammed, DeviceIcons.forLockState(s))
        }
    }

    /** A lock in motion already shows the picture it is heading for. */
    @Test fun transitionsBorrowTheirTargetGlyph() {
        assertEquals(DeviceIcons.forLockState("locked"), DeviceIcons.forLockState("locking"))
        assertEquals(DeviceIcons.forLockState("unlocked"), DeviceIcons.forLockState("unlocking"))
        assertEquals(DeviceIcons.forLockState("open"), DeviceIcons.forLockState("opening"))
    }

    /**
     * Only the lock is state-driven. Every other domain — and the picker rows,
     * which have no state to show — keep [DeviceIcons.forDomain].
     */
    @Test fun onlyTheLockDomainReadsTheState() {
        for (d in listOf("light", "switch", "cover", "climate", "sensor", null)) {
            assertEquals("domain $d", DeviceIcons.forDomain(d), DeviceIcons.forState(d, "locked"))
            assertEquals("domain $d", DeviceIcons.forDomain(d), DeviceIcons.forState(d, null))
        }
        assertEquals(DeviceIcons.forLockState("open"), DeviceIcons.forState("lock", "open"))
        // The domain glyph itself is untouched: it is what a picker row shows.
        assertEquals(R.drawable.ic_door_lock, DeviceIcons.forDomain("lock"))
    }

    /**
     * The tiles paint the glyph with `setColorFilter`, so a state glyph may only
     * be white-on-transparent — a baked-in hue would survive as a lie about the
     * state, and the accent is what carries colour.
     */
    @Test fun everyStateGlyphStaysTintable() {
        val files = stateGlyphs()
        assertEquals("expected one file per state glyph", 5, files.size)
        for (f in files) {
            val text = f.readText()
            val colors = Regex("""android:(fillColor|strokeColor|tint)="([^"]+)"""")
                .findAll(text).map { it.groupValues[2].uppercase() }.toList()
            assertTrue("${f.name}: expected a drawn shape", colors.isNotEmpty())
            for (c in colors) {
                assertTrue("${f.name}: $c defeats setColorFilter", c == "#FFFFFFFF" || c == "#FFFFFF")
            }
            // A themed colour would resolve per-context and dodge the check above.
            assertFalse("${f.name}: no colour references", text.contains("?attr/"))
            assertFalse("${f.name}: no colour references", text.contains("@color/"))
            // 24dp square, like every other widget glyph — the tiles size them down.
            assertTrue("${f.name}: expected a 24 viewport", text.contains("""android:viewportWidth="24""""))
            assertTrue("${f.name}: expected a 24 viewport", text.contains("""android:viewportHeight="24""""))
        }
    }

    /**
     * Distinguishable at a glance means distinguishable **by shape**: the tile has
     * to stay readable for someone with a red-green weakness, so no two states may
     * share a path and differ only in colour.
     */
    @Test fun noTwoStateGlyphsAreTheSameDrawing() {
        val shapes = stateGlyphs().associate { f ->
            f.name to Regex("""android:pathData="([^"]+)"""")
                .findAll(f.readText()).map { it.groupValues[1] }.toList()
        }
        for (f in shapes) assertTrue("${f.key}: expected paths", f.value.isNotEmpty())
        val names = shapes.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            assertNotEquals(
                "${names[i]} and ${names[j]} draw the same shape",
                shapes[names[i]], shapes[names[j]],
            )
        }
    }

    private fun stateGlyphs(): List<File> = listOf(
        "ic_lock_locked", "ic_lock_unlocked", "ic_lock_open", "ic_lock_jammed", "ic_lock_unknown",
    ).map { File(drawableDir, "$it.xml") }
        .onEach { assertTrue("missing res/drawable/${it.name}", it.isFile) }

    /** Unit tests run from the module dir, but don't depend on it (see NotificationIconTest). */
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
