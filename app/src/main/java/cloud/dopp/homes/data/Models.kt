package cloud.dopp.homes.data

import kotlin.math.roundToInt

/**
 * A renderable device card — what Homes sends as a card (`card` in its
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
    // HA's `supported_features` bitmask, forwarded verbatim by Homes. Only the
    // lock's latch bit is read (#92, `LockChooser.FEATURE_OPEN`): a
    // door-opening entry is offered only for a lock that says it has a latch.
    val supportedFeatures: Int? = null,
    // The thing's own page on Homes (`/raeume/<entity_id>`), as Homes names it: a
    // tap opens it ([cloud.dopp.homes.widget.HomesPages.item]). Null when Homes names none.
    val page: String? = null,
    // The start screen's fine controls (owner, 2026-11-03): a heating's setpoint
    // and its bounds and step, a lamp's colour modes (can it dim?), a player's volume.
    val setpoint: Double? = null,
    val minTemp: Double? = null,
    val maxTemp: Double? = null,
    val tempStep: Double? = null,
    val colorModes: List<String> = emptyList(),
) {
    /**
     * Best-effort "active" reading across the supported domains. For a lock
     * "active" is the **unsecured** side (`unlocked`/`unlocking`, and `open` =
     * the latch pulled) — that is the state worth noticing; `locked`, `jammed`
     * and `unknown` are not "on".
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
}

/** One device of the household catalogue (`/napi/portal/start/addable`): the device picker, the app-icon menu. */
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
 * Domains Homes gates **wholesale**, device class irrelevant — Homes'
 * `sensitiveDomains` in `internal/napi/home.go` (#84). Every `lock.*`
 * service is sensitive there, `lock.lock` as much as `lock.unlock`, so a bare
 * tap gets `403 sensitive_action` and only the re-sent `confirmed=true` runs.
 */
private val SENSITIVE_DOMAINS = setOf("lock", "alarm_control_panel")

/**
 * Is a device (by [domain] + [deviceClass]) safety-gated — i.e. its action needs
 * a confirmation (#38, #84)? Mirrors the server's gate.
 */
fun isSensitiveDevice(domain: String?, deviceClass: String?): Boolean =
    domain in SENSITIVE_DOMAINS || (domain == "cover" && deviceClass in SENSITIVE_COVER_CLASSES)
