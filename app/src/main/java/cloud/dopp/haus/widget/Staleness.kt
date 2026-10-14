package cloud.dopp.haus.widget

/**
 * "Wann haben **wir** zuletzt erfolgreich vom Server gehört?" — the widget-side
 * freshness decision (#111).
 *
 * The outage of 29./30.08. had every `/napi/` call answering
 * 500 for more than a day while the tiles kept showing their last values,
 * unchanged and perfectly plausible. Nothing threw — the providers render
 * *stale-then-fresh* (last-good cache first, re-fetch after), so when nothing
 * arrives the old value simply stays and [WidgetFallback], which only fires on a
 * thrown fetch/render, never sees anything. [cloud.dopp.haus.realtime]'s
 * `flagDisconnect` did notice, but it writes to the ongoing notification, not to
 * a tile, and names no device.
 *
 * The one thing that must not be confused here is **which timestamp**:
 * `Card.updatedAtMs` is Home Assistant's *state* time (the ordering fuel for
 * `StateEpochGuard`) — a lamp that has been on, untouched, for a week is not
 * stale. What decides staleness is the **fetch time** of the last successful
 * refresh, recorded by [WidgetCache.noteFetch].
 *
 * Three rules are baked into the numbers and the wording below:
 * - **Do not cry wolf.** The device widget's host update period is 30 min, and
 *   Android may stretch it; [THRESHOLD_MS] sits at two full periods plus slack,
 *   so a network handover, one missed cycle, or a slow reconnect never marks a
 *   tile. A homescreen that flashes on every blip gets ignored — and then it does
 *   not help during the real outage either.
 * - **Mark, don't replace.** The last known value stays readable; the mark rides
 *   *next to* it. "Vor zwei Stunden war abgeschlossen" is useful, an empty error
 *   tile is not.
 * - **A lock may not assert what nobody checked.** Same rule as #84, where
 *   `unknown` must never look like *abgeschlossen* — a *stale* `abgeschlossen`
 *   falls under it too, so [staleValue] turns it into a question.
 *
 * The threshold answers the *passive* question — "has anything arrived lately?".
 * It cannot answer the active one, and the device test proved why: in flight mode
 * a tap raised the toast, but the tiles kept their colour, because 90 minutes had
 * obviously not passed. So the second input arrived: an **executed action is a
 * measurement of the connection** — the only one that happens exactly while the
 * user is looking. [markFor] folds it in; [ActionProbe] decides what an outcome
 * measured.
 *
 * Pure (no Android) → the whole decision is JVM-testable.
 */
object Staleness {

    /**
     * How old the last successful fetch may get before a tile says so — 90 min.
     *
     * Derived from the cadence, not taste: `device_widget_info.xml` asks the host
     * for an update every 30 min, the host is free to batch it, and the realtime
     * push may be off entirely. Two periods plus half a period of slack is the
     * smallest window in which "nothing arrived" means the server, not the phone.
     */
    const val THRESHOLD_MS = 90L * 60 * 1000

    /** What we can say about a tile's data age. */
    enum class State {
        /** Heard from the server within [THRESHOLD_MS]. */
        FRESH,

        /** Older than the threshold — the value on screen may be long overtaken. */
        STALE,

        /** No successful fetch on record: a freshly bound tile, or a cache from
         *  before this was tracked. Never marked — we cannot date what we never
         *  timed, and claiming age we don't know is its own kind of lie. */
        UNKNOWN,
    }

    /**
     * Decide a tile's freshness from the time of its last successful fetch.
     * [lastFetchMs] is `null`/`0` when there is none. A clock that moved backwards
     * (`now` before the fetch) counts as [State.FRESH]: a time-zone or NTP jump is
     * not an outage.
     *
     * The boundary is deliberate — exactly [thresholdMs] old is still fresh; only
     * *older than* the threshold is stale.
     */
    fun state(lastFetchMs: Long?, nowMs: Long, thresholdMs: Long = THRESHOLD_MS): State {
        if (lastFetchMs == null || lastFetchMs <= 0L) return State.UNKNOWN
        val age = nowMs - lastFetchMs
        if (age < 0L) return State.FRESH
        return if (age > thresholdMs) State.STALE else State.FRESH
    }

    /** [state] as the yes/no the renderer asks for. [State.UNKNOWN] is not stale. */
    fun isStale(lastFetchMs: Long?, nowMs: Long, thresholdMs: Long = THRESHOLD_MS): Boolean =
        state(lastFetchMs, nowMs, thresholdMs) == State.STALE

    /**
     * How long ago that was, in German and short enough for a tile:
     * `vor 12 Min.` · `vor 3 Std.` · `vor 2 Tagen`. `null` when there is no
     * fetch time to date (see [State.UNKNOWN]) or the clock jumped backwards.
     */
    fun ageLabel(lastFetchMs: Long?, nowMs: Long): String? {
        if (lastFetchMs == null || lastFetchMs <= 0L) return null
        val age = nowMs - lastFetchMs
        if (age < 0L) return null
        val minutes = age / 60_000L
        return when {
            minutes < 1L -> "gerade eben"
            minutes < 60L -> "vor $minutes Min."
            minutes < 48L * 60 -> "vor ${minutes / 60} Std."
            else -> "vor ${minutes / (24 * 60)} Tagen"
        }
    }

    /**
     * The tile's stale marker line. [compact] is the 1×1 tier, where a single cell
     * has room for the age and nothing else; every larger tier says the word.
     * `null` in → `null` out, i.e. no marker at all.
     */
    fun mark(ageLabel: String?, compact: Boolean = false): String? {
        val age = ageLabel?.takeIf { it.isNotBlank() } ?: return null
        return if (compact) age else "veraltet · $age"
    }

    // --- the action as a probe (#111) ----------------------------------------

    /**
     * What a tile says when the last thing we tried **did not reach the server**.
     * Not "veraltet": the value's age is not the news here — the news is that we
     * asked a moment ago and nothing came back. It matches the toast the same tap
     * raises ("… Haus antwortet nicht"), so the tile and the message agree.
     */
    const val UNREACHABLE = "nicht erreichbar"

    /**
     * Is the last thing we know about the connection a **failure**?
     *
     * [failedAtMs] is when a user-triggered action last failed to reach the server
     * ([WidgetCache.unreachableSince]); `null`/`0` = no such failure on record. A
     * successful fetch clears the record, so in practice only one of the two is
     * ever set — the comparison is the belt to that suspender: a fetch *after* the
     * failure means we have heard from the server since, and the failure is old
     * news.
     */
    fun unreachable(lastFetchMs: Long?, failedAtMs: Long?): Boolean {
        if (failedAtMs == null || failedAtMs <= 0L) return false
        if (lastFetchMs == null || lastFetchMs <= 0L) return true
        return failedAtMs >= lastFetchMs
    }

    /** The two wordings of one tile's mark — [line] for a full tier, [compact] for 1×1. */
    data class Mark(val line: String, val compact: String)

    /**
     * The whole freshness verdict for one tile, ready to render — or `null` when
     * the tile must not be marked at all.
     *
     * Two independent reasons to mark, and they are **not** the same statement:
     *
     * - **The probe** (this issue's follow-up): the user tapped, the action never
     *   reached the server, and we know that *now*. That does not wait out
     *   [THRESHOLD_MS] — waiting would be dishonest, because nobody has to guess
     *   here: the user just watched it fail. It is no new false-alarm source
     *   either, since the only thing that can set it is an action they triggered
     *   themselves. A refusal the server *authored* (the 403 confirm gate, a
     *   forbidden action) is not a failure to reach it — see [ActionProbe].
     * - **The age**: nothing has arrived for longer than [THRESHOLD_MS]. Unchanged
     *   — 90 minutes stays 90 minutes.
     *
     * The probe wins when both apply: "nicht erreichbar" is the stronger and more
     * recent statement, and it carries the age along when there is one.
     */
    fun markFor(
        lastFetchMs: Long?,
        failedAtMs: Long?,
        nowMs: Long,
        thresholdMs: Long = THRESHOLD_MS,
    ): Mark? {
        val age = ageLabel(lastFetchMs, nowMs)?.takeIf { it.isNotBlank() }
        if (unreachable(lastFetchMs, failedAtMs)) {
            // The single cell says the reason and drops the age: which of the two
            // the user needs is not in question once a tap has just failed.
            return Mark(line = if (age == null) UNREACHABLE else "$UNREACHABLE · $age", compact = UNREACHABLE)
        }
        if (!isStale(lastFetchMs, nowMs, thresholdMs)) return null
        val line = mark(age) ?: return null
        return Mark(line = line, compact = mark(age, compact = true) ?: line)
    }

    /**
     * The state word of a **stale** tile. Mark, don't replace: the last known
     * value is kept verbatim for every domain but one.
     *
     * A lock gets a question mark. `abgeschlossen` is the single reading where an
     * old value actively misleads — it asserts a security state nobody verified —
     * and #84 already forbids `unknown` from looking secured. A stale
     * `abgeschlossen?` is still readable as the last thing we knew, and it no
     * longer claims to be the case now. (The colour does the rest — for the lock
     * alone: a stale lock drops to the neutral grey, so the calm green of a
     * confirmed `locked` cannot appear on an unverified one. Every other domain
     * *keeps* its colour while stale, #120: a lamp painted grey stops saying "it
     * was on" and starts saying "it is off", which is the very substitution this
     * whole file exists to prevent.)
     */
    fun staleValue(base: String, domain: String): String {
        if (domain.trim().lowercase() != "lock") return base
        val v = base.trim()
        if (v.isEmpty() || v.endsWith("?") || v.endsWith("…")) return base
        return "$v?"
    }
}
