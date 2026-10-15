package cloud.dopp.haus.widget

import cloud.dopp.haus.data.Card
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON (de)serialization for the cached active-devices list (#28). The collection
 * widget's [ActiveDevicesRemoteViewsService] renders the list from this cache so a
 * fresh scroll draws **instantly** from the last-known devices while the provider
 * re-fetches in the background (stale-then-fresh, no long blank). Kept pure
 * (no Android context) so the round-trip is JVM-testable — the actual persistence
 * lives in [WidgetStore]. NB: the real latency fix is a batch/active endpoint
 * server-side; this cache only hides the wait.
 */
object ActiveCache {

    /** Serialize the active [cards] to a compact JSON array string. */
    fun encode(cards: List<Card>): String {
        val arr = JSONArray()
        for (c in cards) {
            val o = JSONObject()
            o.put("e", c.entityId)
            o.put("n", c.name)
            o.put("d", c.domain)
            c.deviceClass?.let { o.put("dc", it) }
            c.state?.let { o.put("s", it) }
            c.unit?.let { o.put("u", it) }
            c.brightness?.let { o.put("b", it) }
            c.position?.let { o.put("p", it) }
            c.temperature?.let { o.put("t", it) }
            c.page?.let { o.put("pg", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    /** Parse a cached JSON string back into [Card]s. Malformed → empty list. */
    fun decode(json: String?): List<Card> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Card>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Card(
                        entityId = o.optString("e"),
                        name = o.optString("n"),
                        domain = o.optString("d"),
                        deviceClass = o.optString("dc").ifBlank { null },
                        state = o.optString("s").ifBlank { null },
                        unit = o.optString("u").ifBlank { null },
                        brightness = o.optInt("b", -1).takeIf { it >= 0 },
                        position = o.optInt("p", -1).takeIf { it in 0..100 },
                        temperature = o.optDouble("t", Double.NaN).takeIf { !it.isNaN() },
                        page = o.optString("pg").ifBlank { null },
                    ),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
