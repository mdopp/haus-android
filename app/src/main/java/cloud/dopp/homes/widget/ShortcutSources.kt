package cloud.dopp.homes.widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * Last-seen **device catalogue**, cached so the app-icon menu can be rebuilt
 * without waiting for (or reaching) the server (#100) — the same stale-then-fresh
 * contract as [WidgetStore.toolCatalogJson].
 *
 * The menu is republished from a background thread on every visit to the
 * app's screen; refetching the household each time and blanking the menu when
 * the phone is offline would be the wrong trade. Only the four fields a
 * shortcut needs are kept — id, name, domain, device class. Pure →
 * JVM-testable; [WidgetStore] holds the string.
 */
object ShortcutCatalog {

    fun encode(devices: List<ShortcutDevice>): String {
        val arr = JSONArray()
        for (d in devices) {
            if (d.entityId.isBlank()) continue
            val o = JSONObject()
            o.put("e", d.entityId)
            o.put("n", d.name)
            o.put("d", d.domain)
            d.deviceClass?.let { o.put("dc", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    /** Parse a cached catalogue; anything malformed reads as none. */
    fun decode(json: String?): List<ShortcutDevice> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<ShortcutDevice>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val eid = o.optString("e")
                if (eid.isBlank()) continue
                out.add(
                    ShortcutDevice(
                        entityId = eid,
                        name = o.optString("n"),
                        domain = o.optString("d"),
                        deviceClass = o.optString("dc").ifBlank { null },
                    ),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
