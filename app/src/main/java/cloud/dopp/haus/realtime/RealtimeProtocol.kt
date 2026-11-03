package cloud.dopp.haus.realtime

import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.data.Napi
import cloud.dopp.haus.data.Napi.EventJson
import cloud.dopp.haus.data.Napi.GemeinsamFrameJson
import cloud.dopp.haus.konto.HausKonto
import org.json.JSONObject

/**
 * The live stream's wire (laib ADR 0022 §2; Haus's `internal/napi/live.go`):
 * `GET /napi/portal/events` sends `card_state` frames for the entities this
 * device watches, `gemeinsam` frames when the household's shared data changed
 * (the Haus account, #209), and `: ping` comments. Nothing else is read — no
 * notices, no approvals, no catch-up. Pure (only `org.json`), so it is
 * JVM-tested.
 */
object RealtimeProtocol {

    /** One frame the stream carries. */
    sealed interface Frame

    /** `card_state`: `{"entity_id": …, "card": {…}}`. */
    data class CardStateEvent(val entityId: String, val card: Card) : Frame

    /** `gemeinsam`: `{"teil": "kontakte"|"kalender"}`, that part of the Haus account changed at Haus. */
    data class GemeinsamEvent(val teil: HausKonto.Teil) : Frame

    /**
     * One SSE frame of [type] → what it carries, or null for anything that is not
     * a well-formed `card_state` or `gemeinsam` frame. Every other type is
     * dropped here, so the stream cannot grow another purpose by accident.
     */
    fun frame(type: String?, data: String?): Frame? = when (type) {
        Napi.CARD_STATE -> parseCardState(data)
        Napi.GEMEINSAM_FRAME -> parseGemeinsam(data)
        else -> null
    }

    /** A `gemeinsam` payload: which part changed; anything else is null. Never throws. */
    fun parseGemeinsam(data: String?): GemeinsamEvent? = try {
        when (JSONObject(data.orEmpty()).optString(GemeinsamFrameJson.TEIL)) {
            Napi.TEIL_KONTAKTE -> GemeinsamEvent(HausKonto.Teil.KONTAKTE)
            Napi.TEIL_KALENDER -> GemeinsamEvent(HausKonto.Teil.KALENDER)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Parse a `card_state` payload. The entity id is the top-level one, or the
     * card's own; without an id or a card there is nothing to route. Never throws.
     */
    fun parseCardState(data: String?): CardStateEvent? {
        if (data.isNullOrBlank()) return null
        return try {
            val root = JSONObject(data)
            val entityId = root.optString(EventJson.ENTITY_ID)
                .ifBlank { root.optJSONObject(EventJson.CARD)?.optString(Napi.CardJson.ENTITY_ID) ?: "" }
            if (entityId.isBlank()) return null
            val cardObj = root.optJSONObject(EventJson.CARD) ?: return null
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

    /**
     * A session that stayed open this long was healthy; only then does the
     * backoff reset (#79). Longer than Haus's 90 s ping (`live.go`): a proxy
     * that cuts streams idle for less than that ends each session before the
     * first ping, and such a session must keep backing off, not reconnect in
     * 2 s and re-read every widget once a minute.
     */
    const val STABLE_SESSION_MS = 120_000L

    fun healthySession(connectedAtMs: Long, nowMs: Long): Boolean =
        connectedAtMs != 0L && nowMs - connectedAtMs >= STABLE_SESSION_MS

    /** How often a refused watch post is tried again before the 20-minute re-post takes over. */
    const val WATCH_RETRIES = 6

    /**
     * After the [attempt]-th watch post in a row was answered [code]: how long to
     * wait before posting again, or null to stop. Only a 503 is retried — Haus
     * sends it while Home Assistant is down (`live.go`), and without its watch
     * set the open stream carries no frames. 2 s doubling to 60 s, [WATCH_RETRIES]
     * times (about two minutes); every other answer is final.
     */
    fun watchRetryMs(code: Int, attempt: Int): Long? =
        if (code == 503 && attempt in 0 until WATCH_RETRIES) backoffMillis(attempt) else null
}
