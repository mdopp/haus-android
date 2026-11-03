package cloud.dopp.haus

import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.EnergyWidgetProvider
import cloud.dopp.haus.widget.WidgetTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget-type table (#34) lists every pinnable provider, so the app's
 * screen offers them all in one list (owner, 2026-11-03): one device, the
 * energy; the tool widget comes once per tool. No Box widgets any more.
 * Pure JVM coverage: no Android context needed.
 */
class WidgetTypesTest {

    @Test
    fun oneDeviceAndTheEnergy() {
        assertEquals(
            listOf<Class<*>>(DeviceWidgetProvider::class.java, EnergyWidgetProvider::class.java),
            WidgetTypes.ALL.map { it.provider },
        )
    }

    /** Every widget the manifest declares is offered — the tool widget once per tool, elsewhere. */
    @Test
    fun tableMatchesTheManifest() {
        val manifest = java.io.File(RepoFiles.main, "AndroidManifest.xml").readText()
        val declared = Regex("""<receiver\s+android:name="\.widget\.(\w+WidgetProvider)"""").findAll(manifest)
            .map { it.groupValues[1] }.toSet() - "ToolWidgetProvider"
        assertEquals(declared, WidgetTypes.ALL.map { it.provider.simpleName }.toSet())
    }

    @Test
    fun everyEntryHasLabelAndIcon() {
        assertTrue(WidgetTypes.ALL.isNotEmpty())
        WidgetTypes.ALL.forEach {
            assertTrue("label res id set", it.label != 0)
            assertTrue("icon res id set", it.icon != 0)
        }
    }
}
