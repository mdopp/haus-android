package cloud.dopp.homes.data

import java.util.Locale

/**
 * The household's devices for the device widget's picker and the app-icon
 * menu: which may be offered (Homes offers cameras in the addable list too,
 * but draws no card for one, [Napi.CARD_DOMAINS]), and the picker's search by
 * name and room, case-insensitive, German umlauts folded the German way.
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
    fun filter(devices: List<Device>, query: String): List<Device> =
        if (query.isBlank()) devices else devices.filter { matches(it, query) }

    /** The devices Homes draws a card for. */
    fun pickable(devices: List<Device>): List<Device> = devices.filter { it.domain in Napi.CARD_DOMAINS }
}
