package cloud.dopp.haus.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cloud.dopp.haus.R

/**
 * The typed table of pinnable widget types (#34): one entry per home-screen
 * widget provider with a fixed purpose, so the app's screen can offer every one
 * of them and pin the picked provider via `requestPinAppWidget`. The tool widget
 * is not here: it is offered once per tool in Haus's catalog. The devices are
 * not widgets (owner, 2026-11-02): they are on the app's start screen.
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
        Entry(EnergyWidgetProvider::class.java, R.string.widget_type_energy, R.drawable.ic_bolt),
        Entry(BoxOverviewWidgetProvider::class.java, R.string.widget_type_box_overview, R.drawable.ic_box, admin = true),
        Entry(BoxServicesWidgetProvider::class.java, R.string.widget_type_box_services, R.drawable.ic_services, admin = true),
    )
}
