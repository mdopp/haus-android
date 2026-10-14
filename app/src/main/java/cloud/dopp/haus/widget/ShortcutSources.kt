package cloud.dopp.haus.widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * The **usage history** behind the app-icon menu's order (#100).
 *
 * Deliberately not a log: a capped list of entity ids, most recently switched
 * first. The order *is* the timing information — a shortcut menu only ever asks
 * "which did I touch last", never "when", so keeping timestamps would store more
 * than anything reads. [CAP] is a handful more than any launcher shows, so a
 * device that drops out of the menu for one switch elsewhere comes straight back.
 *
 * Pure (only `org.json`) → the whole round-trip is JVM-tested; the persistence
 * lives in [WidgetStore].
 */
object ShortcutUsage {

    /** How many entities the history remembers. */
    const val CAP = 12

    /** [entityId] to the front of [history], deduplicated and capped. */
    fun record(history: List<String>, entityId: String, cap: Int = CAP): List<String> {
        if (entityId.isBlank() || cap <= 0) return history.take(maxOf(cap, 0))
        val out = ArrayList<String>(minOf(history.size + 1, cap))
        out.add(entityId)
        for (e in history) {
            if (out.size >= cap) break
            if (e.isNotBlank() && e != entityId) out.add(e)
        }
        return out
    }

    fun encode(history: List<String>): String {
        val arr = JSONArray()
        for (e in history) arr.put(e)
        return arr.toString()
    }

    /** Parse a stored history; anything malformed reads as "no history yet". */
    fun decode(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.optString(i)
                if (e.isNotBlank()) out.add(e)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Last-seen **device catalogue**, cached so the app-icon menu can be rebuilt
 * without waiting for (or reaching) the server (#100) — the same stale-then-fresh
 * contract as [ActiveCache] and [WidgetStore.toolCatalogJson].
 *
 * The menu is republished from a background thread on every widget change and on
 * every visit to the hub; refetching the household each time and blanking the
 * menu when the phone is offline would be the wrong trade. Only the four fields a
 * shortcut needs are kept — id, name, domain, device class — not the picker's
 * room grouping. Pure → JVM-testable; [WidgetStore] holds the string.
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

    /**
     * Parse a cached catalogue. Every entry comes back **without** an
     * `appWidgetId`: the cache is the household, not the home screen — the live
     * widget ids are merged on top by [AppShortcuts].
     */
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
                        appWidgetId = null,
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
