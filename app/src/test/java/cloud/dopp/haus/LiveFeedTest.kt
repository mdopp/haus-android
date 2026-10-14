package cloud.dopp.haus

import cloud.dopp.haus.widget.ActiveDevicesWidgetProvider
import cloud.dopp.haus.widget.BoxOverviewWidgetProvider
import cloud.dopp.haus.widget.BoxServicesWidgetProvider
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.EnergyStatsWidgetProvider
import cloud.dopp.haus.widget.EnergyTotalsWidgetProvider
import cloud.dopp.haus.widget.EnergyWidgetProvider
import cloud.dopp.haus.widget.ToolWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Which widgets keep the live service running (#177 review, findings 3 and
 * 9): those it keeps current — the device widgets take its frames, the active
 * list and the energy widgets refetch on them, and every one of them is read
 * again on screen-on. A static tile (devices, camera) alone runs nothing.
 * Removing a fed widget looks again, so the last one gone stops the service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveFeedTest {

    private val fed: List<*> by lazy { Reach.get(Reach.obj("cloud.dopp.haus.realtime.Live"), "FED") as List<*> }

    @Test
    fun theWidgetsTheStreamKeepsCurrentAreFed() {
        assertEquals(
            setOf(
                DeviceWidgetProvider::class.java, ActiveDevicesWidgetProvider::class.java,
                BoxOverviewWidgetProvider::class.java, BoxServicesWidgetProvider::class.java,
                EnergyWidgetProvider::class.java, EnergyStatsWidgetProvider::class.java,
                EnergyTotalsWidgetProvider::class.java, ToolWidgetProvider::class.java,
            ),
            fed.toSet(),
        )
    }

    @Test
    fun removingAFedWidgetLooksAgain() {
        val missing = fed.map { it as Class<*> }.filter { cls ->
            val src = File(RepoFiles.main, "java/" + cls.name.replace('.', '/') + ".kt").readText()
            val deleted = src.substringAfter("override fun onDeleted(", "")
            deleted.isEmpty() || !deleted.substringBefore("\n    }").contains("Live.recheck(")
        }
        assertTrue("onDeleted does not look again: $missing", missing.isEmpty())
    }
}
