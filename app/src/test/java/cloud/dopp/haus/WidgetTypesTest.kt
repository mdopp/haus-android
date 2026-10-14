package cloud.dopp.haus

import cloud.dopp.haus.widget.ActiveDevicesWidgetProvider
import cloud.dopp.haus.widget.CameraWidgetProvider
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.EnergyStatsWidgetProvider
import cloud.dopp.haus.widget.EnergyTotalsWidgetProvider
import cloud.dopp.haus.widget.EnergyWidgetProvider
import cloud.dopp.haus.widget.BoxOverviewWidgetProvider
import cloud.dopp.haus.widget.BoxServicesWidgetProvider
import cloud.dopp.haus.widget.DevicesWidgetProvider
import cloud.dopp.haus.widget.WidgetTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget-type chooser table (#34) must list *every* pinnable provider, so the
 * onboarding "Widget hinzufügen" dialog can offer all types — not only the device
 * widget. Pure JVM coverage: no Android context needed.
 */
class WidgetTypesTest {

    @Test
    fun tableCoversEveryWidgetProvider() {
        val expected = setOf<Class<*>>(
            DeviceWidgetProvider::class.java,
            EnergyWidgetProvider::class.java,
            EnergyStatsWidgetProvider::class.java,
            EnergyTotalsWidgetProvider::class.java,
            ActiveDevicesWidgetProvider::class.java,
            CameraWidgetProvider::class.java,
            DevicesWidgetProvider::class.java,
            BoxOverviewWidgetProvider::class.java,
            BoxServicesWidgetProvider::class.java,
        )
        assertEquals(expected, WidgetTypes.ALL.map { it.provider }.toSet())
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
    fun deviceWidgetIsListedFirst() {
        assertEquals(DeviceWidgetProvider::class.java, WidgetTypes.ALL.first().provider)
    }

    @Test
    fun everyEntryHasLabelAndIcon() {
        assertTrue(WidgetTypes.ALL.isNotEmpty())
        WidgetTypes.ALL.forEach {
            assertTrue("label res id set", it.label != 0)
            assertTrue("icon res id set", it.icon != 0)
        }
    }

    @Test
    fun noDuplicateProviders() {
        val providers = WidgetTypes.ALL.map { it.provider }
        assertEquals(providers.size, providers.toSet().size)
    }
}
