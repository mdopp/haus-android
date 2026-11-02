package cloud.dopp.haus

import cloud.dopp.haus.widget.BoxOverviewWidgetProvider
import cloud.dopp.haus.widget.BoxServicesWidgetProvider
import cloud.dopp.haus.widget.EnergyWidgetProvider
import cloud.dopp.haus.widget.WidgetTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget-type table (#34) lists every pinnable provider, so the app's
 * screen offers them all. Since the owner's call of 2026-11-02 that is one
 * energy widget and the Box's two: the devices are on the start screen.
 * Pure JVM coverage: no Android context needed.
 */
class WidgetTypesTest {

    @Test
    fun oneEnergyWidgetAndTheBoxes() {
        assertEquals(
            listOf<Class<*>>(EnergyWidgetProvider::class.java, BoxOverviewWidgetProvider::class.java, BoxServicesWidgetProvider::class.java),
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

    /** The Box widgets are the admin ones, and only they. */
    @Test
    fun onlyTheBoxWidgetsAreForAdmins() {
        assertEquals(
            setOf<Class<*>>(BoxOverviewWidgetProvider::class.java, BoxServicesWidgetProvider::class.java),
            WidgetTypes.ALL.filter { it.admin }.map { it.provider }.toSet(),
        )
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
