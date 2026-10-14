package cloud.dopp.solaris

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.solaris.data.BoxPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #174 (laib ADR 0021): the Box keys move from `/napi/servicebay/…` to the
 * neutral `/napi/box/…`. The app asks the new path first and falls back to the
 * old one on a 404. Once a server has answered, the app remembers which path it
 * was, so a server that only knows one name costs one extra request, once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoxPathsTest {

    private val server = "https://haus.example"

    /** In-memory stand-in for the per-server memory. */
    private class MapMemo : BoxPaths.Memo {
        val m = mutableMapOf<String, String>()
        override fun get(server: String): String? = m[server]
        override fun put(server: String, prefix: String) { m[server] = prefix }
    }

    /** A fake server: the answer per prefix, and the order it was asked in. */
    private class FakeServer(private val answers: Map<String, BoxPaths.Answer<String>>) {
        val asked = mutableListOf<String>()
        fun get(prefix: String): BoxPaths.Answer<String> {
            asked += prefix
            return answers[prefix] ?: BoxPaths.Answer(404, null)
        }
    }

    private fun ok(v: String) = BoxPaths.Answer(200, v)

    private lateinit var memo: MapMemo

    @Before
    fun fresh() {
        memo = MapMemo()
        BoxPaths.memo(ApplicationProvider.getApplicationContext<Context>()).clear()
    }

    @Test
    fun theNewPathsAreUnderNapiBox() {
        assertEquals("/napi/box/home", SolarisConfig.NAPI + BoxPaths.BOX + "/home")
        assertEquals("/napi/box/services", SolarisConfig.NAPI + BoxPaths.BOX + "/services")
        assertEquals("/napi/box/approvals", SolarisConfig.NAPI + BoxPaths.BOX + "/approvals")
        assertEquals("/napi/box/upgrades", SolarisConfig.NAPI + BoxPaths.BOX + "/upgrades")
        assertEquals("/napi/servicebay/home", SolarisConfig.NAPI + BoxPaths.LEGACY + "/home")
    }

    @Test
    fun aFreshServerIsAskedTheNewPathFirst() {
        val s = FakeServer(mapOf(BoxPaths.BOX to ok("box")))
        assertEquals("box", BoxPaths.fetch(server, memo) { s.get(it) })
        assertEquals(listOf(BoxPaths.BOX), s.asked)
        assertEquals(BoxPaths.BOX, memo.get(server))
    }

    @Test
    fun aNotFoundOnTheNewPathFallsBackToTheOldOne() {
        val s = FakeServer(mapOf(BoxPaths.LEGACY to ok("legacy")))
        assertEquals("legacy", BoxPaths.fetch(server, memo) { s.get(it) })
        assertEquals(listOf(BoxPaths.BOX, BoxPaths.LEGACY), s.asked)
        assertEquals(BoxPaths.LEGACY, memo.get(server))
    }

    @Test
    fun aRememberedOldPathIsAskedDirectly() {
        memo.put(server, BoxPaths.LEGACY)
        val s = FakeServer(mapOf(BoxPaths.LEGACY to ok("legacy")))
        assertEquals("legacy", BoxPaths.fetch(server, memo) { s.get(it) })
        assertEquals(listOf(BoxPaths.LEGACY), s.asked)
    }

    /** The server dropped the old names: the app moves on to the new ones by itself. */
    @Test
    fun aRememberedOldPathThatVanishedMovesToTheNewOne() {
        memo.put(server, BoxPaths.LEGACY)
        val s = FakeServer(mapOf(BoxPaths.BOX to ok("box")))
        assertEquals("box", BoxPaths.fetch(server, memo) { s.get(it) })
        assertEquals(listOf(BoxPaths.LEGACY, BoxPaths.BOX), s.asked)
        assertEquals(BoxPaths.BOX, memo.get(server))
    }

    /** 401, 5xx or no network say nothing about the path: no fallback, no memory. */
    @Test
    fun otherFailuresDoNotFallBack() {
        for (status in listOf(401, 403, 500, 502, 0)) {
            val s = FakeServer(mapOf(BoxPaths.BOX to BoxPaths.Answer(status, null), BoxPaths.LEGACY to ok("legacy")))
            assertNull("status $status", BoxPaths.fetch(server, memo) { s.get(it) })
            assertEquals("status $status", listOf(BoxPaths.BOX), s.asked)
            assertNull("status $status", memo.get(server))
        }
    }

    /** A 2xx without a usable body is not the Box answering — try the other name. */
    @Test
    fun anUnusableSuccessFallsBack() {
        val s = FakeServer(mapOf(BoxPaths.BOX to BoxPaths.Answer(200, null), BoxPaths.LEGACY to ok("legacy")))
        assertEquals("legacy", BoxPaths.fetch(server, memo) { s.get(it) })
        assertEquals(BoxPaths.LEGACY, memo.get(server))
    }

    @Test
    fun neitherPathAnswersMeansNullAndNoMemory() {
        val s = FakeServer(emptyMap())
        assertNull(BoxPaths.fetch(server, memo) { s.get(it) })
        assertEquals(listOf(BoxPaths.BOX, BoxPaths.LEGACY), s.asked)
        assertNull(memo.get(server))
    }

    @Test
    fun theMemoryIsPerServer() {
        memo.put("https://old.example", BoxPaths.LEGACY)
        val s = FakeServer(mapOf(BoxPaths.BOX to ok("box"), BoxPaths.LEGACY to ok("legacy")))
        assertEquals("box", BoxPaths.fetch(server, memo) { s.get(it) })
        assertEquals(listOf(BoxPaths.BOX), s.asked)
    }

    /** The real memory survives the process: widgets run in short-lived ones. */
    @Test
    fun theStoredMemoryKeepsOneAnswerPerServer() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val stored = BoxPaths.memo(ctx)
        assertNull(stored.get(server))
        stored.put(server, BoxPaths.LEGACY)
        assertEquals(BoxPaths.LEGACY, BoxPaths.memo(ctx).get(server))
        assertNull(BoxPaths.memo(ctx).get("https://other.example"))
        BoxPaths.memo(ctx).clear()
        assertNull(BoxPaths.memo(ctx).get(server))
    }
}
