package cloud.dopp.haus.realtime

import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Card
import org.json.JSONObject

/**
 * The live stream's wire (laib ADR 0022 §2; Haus's `internal/napi/live.go`):
 * `GET /napi/portal/events` sends `card_state` frames for the entities this
 * device watches, and `: ping` comments. Nothing else is read — no notices, no
 * approvals, no catch-up. Pure (only `org.json`), so it is JVM-tested.
 */
object RealtimeProtocol {

    /** The one event the stream carries: `{"entity_id": …, "card": {…}}`. */
    const val EVENT_CARD_STATE = "card_state"

    data class CardStateEvent(val entityId: String, val card: Card)

    /**
     * One SSE frame of [type] → the card it carries, or null for anything that is
     * not a well-formed `card_state` frame. Every other type is dropped here, so
     * the stream cannot grow a second purpose by accident.
     */
    fun frame(type: String?, data: String?): CardStateEvent? =
        if (type == EVENT_CARD_STATE) parseCardState(data) else null

    /**
     * Parse a `card_state` payload. The entity id is the top-level one, or the
     * card's own; without an id or a card there is nothing to route. Never throws.
     */
    fun parseCardState(data: String?): CardStateEvent? {
        if (data.isNullOrBlank()) return null
        return try {
            val root = JSONObject(data)
            val entityId = root.optString("entity_id")
                .ifBlank { root.optJSONObject("card")?.optString("entity_id") ?: "" }
            if (entityId.isBlank()) return null
            val cardObj = root.optJSONObject("card") ?: return null
            CardStateEvent(entityId, ApiClient.parseCardJson(cardObj))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reconnect delay after the [attempt]-th failure in a row while the screen is
     * on: 2 s doubling to a 60 s cap, overflow-safe.
     */
    fun backoffMillis(
        attempt: Int,
        baseMs: Long = 2_000L,
        capMs: Long = 60_000L,
    ): Long {
        if (attempt < 0) return baseMs
        if (attempt >= 32) return capMs
        val grown = baseMs shl attempt
        return if (grown <= 0L || grown > capMs) capMs else grown
    }

    /** A session that stayed open this long was healthy; only then does the backoff reset (#79). */
    const val STABLE_SESSION_MS = 20_000L

    fun healthySession(connectedAtMs: Long, nowMs: Long): Boolean =
        connectedAtMs != 0L && nowMs - connectedAtMs >= STABLE_SESSION_MS
}
