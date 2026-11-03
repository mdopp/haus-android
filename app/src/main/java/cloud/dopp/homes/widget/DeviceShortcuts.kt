package cloud.dopp.homes.widget

import cloud.dopp.homes.data.isSensitiveDevice

/** What a long-press entry does when it is tapped (#97). */
enum class ShortcutAction {
    /** Switch the device straight away — only where a mistap is harmless. */
    TOGGLE,

    /** Open the lock chooser ([WidgetActionActivity]) — acts on nothing itself. */
    CHOOSE,

    /** Open the device's card in Homes' pages — no call leaves the phone. */
    OPEN,
}

/**
 * One candidate for the menu: an entity plus the meta needed to label, icon and
 * route it. Plain data, no Android — the shortcut list is decided from this
 * alone.
 *
 * The action path works from [entityId] alone (#100).
 */
data class ShortcutDevice(
    val entityId: String,
    val name: String,
    val domain: String,
    val deviceClass: String? = null,
)

/** One published app-icon shortcut. [id] is stable per entity across refreshes. */
data class ShortcutEntry(
    val id: String,
    val label: String,
    val device: ShortcutDevice,
    val action: ShortcutAction,
) {
    /** Does tapping this entry call a service? Only [ShortcutAction.TOGGLE] does. */
    val actsDirectly: Boolean get() = action == ShortcutAction.TOGGLE
}

/**
 * Which entries the **app-icon long-press** offers (#97).
 *
 * A long-press menu on the *widget* is impossible — that menu belongs to the
 * launcher and takes no app actions (#96) — so the device shortcuts live on the
 * app icon instead. They cannot be static: an XML list written at build time
 * cannot know the user's devices.
 *
 * **The source is the household catalogue** (#100), `/napi/portal/start/addable`.
 *
 * **The cap is asked for, never assumed.** `getMaxShortcutCountPerActivity()` is
 * passed in as [cap]; launchers commonly *show* four but neither the shown nor
 * the allowed number is fixed. Since the catalogue is far larger than the cap,
 * the order is what decides the menu, in two tiers:
 *
 * 1. **Last switched first** — [recent], the capped usage list [ShortcutUsage]
 *    keeps. This is the rule; the other only fills the gap it leaves.
 * 2. Then the rest, in catalogue order (rooms, as Homes groups them).
 *
 * An entity in [recent] that is no longer in the catalogue simply does not
 * appear — it consumes no slot and is not resurrected from the history.
 *
 * **Security (#92 rules, unchanged here):** a lock never gets a directly-acting
 * entry. It gets [ShortcutAction.CHOOSE], which opens the very same chooser as
 * a lock on the start screen, behind the device lock and still subject to the server's
 * authoritative 403 `sensitive_action`. `lock.open` is named nowhere in this
 * file: no shortcut carries a service at all, so none can reach the latch. Every
 * other sensitive device (garage/door/gate cover, alarm panel) merely opens its
 * card. Pure (no Android) → JVM-testable, which is the point.
 */
object DeviceShortcuts {

    /** The domains where a mistapped shortcut is harmless, so it may act. */
    val DIRECT_DOMAINS = setOf("light", "switch")

    /** Shortcut-id prefix, so a published entry is recognisable and stable. */
    const val ID_PREFIX = "dev:"

    /** May a shortcut for this domain switch the device without asking? */
    fun actsDirectly(domain: String?, deviceClass: String? = null): Boolean =
        actionFor(domain, deviceClass) == ShortcutAction.TOGGLE

    /**
     * The domain an entity id names, e.g. `lock.haustuer` → `lock` (#100).
     *
     * The domain is what decides whether a tap may act at all. Taking it from
     * the entity id keeps that decision tied to the very
     * string the call will be made against: a tampered shortcut aiming at
     * `lock.haustuer` yields `lock`, and a lock never acts directly. An id
     * without a dot yields `""`, which acts on nothing either.
     */
    fun domainOf(entityId: String?): String =
        entityId?.substringBefore('.', "")?.trim().orEmpty()

    /**
     * The one rule table. Locks come first: [isSensitiveDevice] would catch them
     * too, but a lock is the one device with a chooser to send the user to.
     */
    fun actionFor(domain: String?, deviceClass: String? = null): ShortcutAction = when {
        domain == "lock" -> ShortcutAction.CHOOSE
        isSensitiveDevice(domain, deviceClass) -> ShortcutAction.OPEN
        domain in DIRECT_DOMAINS -> ShortcutAction.TOGGLE
        else -> ShortcutAction.OPEN
    }

    /**
     * The entries to publish for [devices], never more than [cap], ordered by
     * [recent] (most recently switched entity ids first) as described above.
     *
     * Devices without an entity id are dropped; a device listed twice appears
     * once. A blank name falls back to the entity id — an unnamed row is worse
     * than a technical one.
     */
    fun entries(
        devices: List<ShortcutDevice>,
        cap: Int,
        recent: List<String> = emptyList(),
    ): List<ShortcutEntry> {
        if (cap <= 0) return emptyList()
        // Position in the usage list; everything unused sorts behind all of it.
        val used = HashMap<String, Int>(recent.size)
        recent.forEachIndexed { i, e -> if (!used.containsKey(e)) used[e] = i }
        return devices
            .filter { it.entityId.isNotBlank() }
            // Stable sort, so tier 2 keeps the catalogue's own order.
            .sortedWith(compareBy { used[it.entityId] ?: Int.MAX_VALUE })
            .distinctBy { it.entityId }
            .take(cap)
            .map { d ->
                ShortcutEntry(
                    id = ID_PREFIX + d.entityId,
                    label = d.name.ifBlank { d.entityId },
                    device = d,
                    action = actionFor(d.domain, d.deviceClass),
                )
            }
    }
}
