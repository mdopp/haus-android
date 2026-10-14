package cloud.dopp.haus.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cloud.dopp.haus.R

/**
 * The typed table of pinnable widget types (#34): one entry per home-screen
 * widget provider with a fixed purpose, so the home screen can offer every one
 * of them and pin the picked provider via `requestPinAppWidget`. The tool widget
 * is not here: it is offered once per tool in Haus's catalog.
 *
 * Pure (label/icon are resource ids, provider is a class reference) → the table
 * itself is unit-testable on the JVM.
 */
object WidgetTypes {

    data class Entry(
        val provider: Class<*>,
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
        /** Shown under "Box", for whoever looks after it. */
        val admin: Boolean = false,
    )

    /** Ordered as shown: the house first, the Box last. */
    val ALL: List<Entry> = listOf(
        Entry(DeviceWidgetProvider::class.java, R.string.widget_type_device, R.drawable.ic_device),
        Entry(DevicesWidgetProvider::class.java, R.string.widget_type_devices, R.drawable.ic_light),
        Entry(ActiveDevicesWidgetProvider::class.java, R.string.widget_type_active, R.drawable.ic_switch),
        Entry(EnergyWidgetProvider::class.java, R.string.widget_type_energy_now, R.drawable.ic_bolt),
        Entry(EnergyStatsWidgetProvider::class.java, R.string.widget_type_energy_history, R.drawable.ic_bolt),
        Entry(EnergyTotalsWidgetProvider::class.java, R.string.widget_type_energy_totals, R.drawable.ic_bolt),
        Entry(CameraWidgetProvider::class.java, R.string.widget_type_camera, R.drawable.ic_camera),
        Entry(BoxOverviewWidgetProvider::class.java, R.string.widget_type_box_overview, R.drawable.ic_box, admin = true),
        Entry(BoxServicesWidgetProvider::class.java, R.string.widget_type_box_services, R.drawable.ic_services, admin = true),
    )
}
