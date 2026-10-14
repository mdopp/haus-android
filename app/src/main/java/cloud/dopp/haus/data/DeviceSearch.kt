package cloud.dopp.haus.data

import java.util.Locale

/**
 * Pure, Android-free device search used by the widget config picker (#19). Filters
 * by device **name** AND **room** (falling back to domain when a device has no
 * room), case-insensitively. Lowercasing uses [Locale.GERMANY] so German umlauts
 * (e.g. "Küche" ⇄ "küche") fold the way the household expects. Extracted from
 * `WidgetConfigActivity` so it can be unit-tested on the JVM without Robolectric.
 */
object DeviceSearch {

    /** True if [device] matches [query] by name or room (case-insensitive). */
    fun matches(device: Device, query: String): Boolean {
        val q = query.trim().lowercase(Locale.GERMANY)
        if (q.isEmpty()) return true
        return device.name.lowercase(Locale.GERMANY).contains(q) ||
            (device.room ?: device.domain).lowercase(Locale.GERMANY).contains(q)
    }

    /** Filter [devices] to those matching [query]; blank query returns all. */
    fun filter(devices: List<Device>, query: String): List<Device> {
        if (query.trim().isEmpty()) return devices
        return devices.filter { matches(it, query) }
    }
}
