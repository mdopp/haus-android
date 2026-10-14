package cloud.dopp.haus.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-entity staleness guard for device-state updates (#… multi-device toggle
 * race).
 *
 * With two or more companion apps active, toggling a switch used to "spring"
 * (on→off→on) and sometimes settle on the WRONG state. Cause: a device-widget's
 * state can be written from two racing sources — the realtime `card_state` SSE
 * push and the widget's own post-toggle `/portal/state` re-fetch — and a stale
 * snapshot (an older fetch, a reconnect replay, another device's earlier flip)
 * would blindly overwrite a newer state.
 *
 * The fix is last-write-wins ordered by a **source-authoritative** stamp, NOT
 * the client's wall-clock or the frame's receive-time (both are exactly what's
 * jittered / drifts between devices). The server forwards the HA entity's
 * `last_updated` as [Card.updatedAtMs] — monotonic per entity because HA is the
 * single authority. This guard keeps the high-water stamp per entity and, for
 * every incoming card, returns the card that should actually be rendered:
 *
 *   - a NEWER-or-equal stamped card becomes the winner (and is remembered);
 *   - a strictly-OLDER card is rejected — the previously-applied (newer) card is
 *     returned instead, so a late/stale update neither overwrites a newer state
 *     nor leaves the widget stuck on a loading placeholder;
 *   - a card with no stamp (legacy server) is passed through unchanged, so the
 *     guard is fully inert until the server begins sending `updated_at_ms`.
 *
 * Every state-apply site routes its card through [resolve] and renders the
 * result: `render(StateEpochGuard.resolve(card))`.
 */
object StateEpochGuard {
    private val highWaterMs = ConcurrentHashMap<String, Long>()
    private val latest = ConcurrentHashMap<String, Card>()

    /**
     * Offer [card] for its entity and return the freshest card to render. A
     * stale card (strictly older stamp) yields the last-applied newer card;
     * an unstamped card is returned as-is. Synchronized so the check-and-store
     * is atomic across the SSE thread and the widget fetch threads.
     */
    @Synchronized
    fun resolve(card: Card): Card {
        val ms = card.updatedAtMs ?: return card
        val entity = card.entityId
        val prev = highWaterMs[entity]
        if (prev == null || ms >= prev) {
            highWaterMs[entity] = ms
            latest[entity] = card
            return card
        }
        // Stale: a newer state was already applied for this entity.
        return latest[entity] ?: card
    }

    /** Test hook — reset all remembered state. */
    fun clear() {
        highWaterMs.clear()
        latest.clear()
    }
}
