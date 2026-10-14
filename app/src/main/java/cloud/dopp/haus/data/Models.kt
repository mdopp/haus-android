package cloud.dopp.haus.data

import kotlin.math.roundToInt

/**
 * A renderable device card — what Haus sends as a card (`card` in its
 * `internal/napi/home.go`): the live state of one HA entity, incl. the #754
 * control attributes (brightness / cover position / climate temperature).
 */
data class Card(
    val entityId: String,
    val name: String,
    val domain: String,
    val deviceClass: String?,
    val state: String?,
    val unit: String?,
    val brightness: Int? = null,   // 0-255 (light)
    val position: Int? = null,     // 0-100 (cover)
    val temperature: Double? = null, // climate current temperature
    // Light colour (#87), straight out of `card_spec`'s `_CONTROL_ATTRS`. The rgb
    // triple is kept **packed** as 0xRRGGBB, not as an IntArray, so the data class
    // keeps value equality (two cards with the same colour must compare equal —
    // the staleness guard and the render cache rely on that).
    val rgbColor: Int? = null,
    val supportedColorModes: List<String> = emptyList(),
    val colorMode: String? = null,
    val colorTemp: Int? = null,    // mireds, as HA reports them
    // Source-authoritative ordering stamp: the HA entity's `last_updated` in
    // epoch-ms, forwarded by the server as `updated_at_ms`. Monotonic per entity
    // (HA is the single authority), so it orders concurrent updates from any
    // device correctly — unlike client wall-clock or SSE receive-time. Null when
    // the server doesn't send it (legacy): the staleness guard then passes
    // everything through unchanged. See [StateEpochGuard].
    val updatedAtMs: Long? = null,
    // HA's `supported_features` bitmask, forwarded verbatim by `card_spec`. Only
    // the lock's latch bit is read today (#92, `LockChooser.FEATURE_OPEN`): a
    // door-opening entry is offered only for a lock that says it has a latch.
    val supportedFeatures: Int? = null,
) {
    /**
     * Best-effort "active" reading across the supported domains. For a lock
     * "active" is the **unsecured** side (`unlocked`/`unlocking`, and `open` =
     * the latch pulled) — that is the state worth noticing; `locked`, `jammed`
     * and `unknown` are not "on". The lock tile does not colour itself from this
     * flag though: see `WidgetRender.lockAccent`, where `unknown` must never
     * look like `locked`.
     */
    val isOn: Boolean
        get() = when (state) {
            "on", "open", "opening", "heat", "cool", "auto", "heat_cool",
            "unlocked", "unlocking",
            -> true
            else -> false
        }

    /** Light brightness as a percentage (0-100), or null. */
    val brightnessPct: Int?
        get() = brightness?.let { (it / 255.0 * 100).roundToInt().coerceIn(0, 100) }

    /**
     * A 0-100 "level" for the magnitude bar: brightness for lights, position for
     * covers, else null (no bar).
     */
    val level: Int?
        get() = when (domain) {
            "light" -> if (isOn) brightnessPct ?: 100 else 0
            "cover" -> position
            else -> null
        }

    /**
     * A device whose action is safety-gated — a lock/alarm panel, or a
     * garage/door/gate cover. Mirrors the server's own gate, which answers with
     * `403 sensitive_action` either way; this only decides the badge.
     */
    val isSensitive: Boolean
        get() = isSensitiveDevice(domain, deviceClass)

    /**
     * Can this light change colour (#87)? Same rule the web card uses
     * (`COLOUR_MODES` in Haus's pages), so a lamp that offers the swatch in the
     * browser offers it on the tile too.
     */
    val isColorCapable: Boolean
        get() = domain == "light" && supportedColorModes.any { it in COLOR_CAPABLE_MODES }

    /** The current colour as an opaque ARGB int for tinting, or null. */
    val colorArgb: Int?
        get() = rgbColor?.let { 0xFF000000.toInt() or (it and 0xFFFFFF) }
}

/** One pickable actuator in the widget configuration screen. */
data class Device(
    val entityId: String,
    val name: String,
    val domain: String,
    val deviceClass: String?,
    val room: String?,
)

/** Covers whose open is confirm-first, matching the server's gate. */
val SENSITIVE_COVER_CLASSES = setOf("garage", "door", "gate")

/**
 * Domains Haus gates **wholesale**, device class irrelevant — Haus's
 * `sensitiveDomains` in `internal/napi/home.go` (#84). Every `lock.*`
 * service is sensitive there, `lock.lock` as much as `lock.unlock`, so a bare
 * tap gets `403 sensitive_action` and only the re-sent `confirmed=true` runs.
 */
val SENSITIVE_DOMAINS = setOf("lock", "alarm_control_panel")

/**
 * HA colour modes that mean "this light can change colour" (#87) — the same list
 * Haus's pages's card uses. `onoff`/`brightness` are absent on purpose: a plain white
 * bulb must keep its unchanged −/+ row.
 */
val COLOR_CAPABLE_MODES = setOf("rgb", "hs", "xy", "rgbw", "rgbww", "color_temp")

/**
 * Is a device (by [domain] + [deviceClass]) safety-gated — i.e. its action needs
 * a confirmation (#38, #84)? Mirrors [Card.isSensitive] but works from the bound
 * widget metadata when no live [Card] is available yet (cache/loading render).
 */
fun isSensitiveDevice(domain: String?, deviceClass: String?): Boolean =
    domain in SENSITIVE_DOMAINS || (domain == "cover" && deviceClass in SENSITIVE_COVER_CLASSES)
