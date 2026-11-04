package cloud.dopp.homes.widget

/**
 * One household device as the launcher's garage shortcut looks for it: an
 * entity plus what names and routes it. Plain data, no Android; the cached
 * catalogue ([ShortcutCatalog]) keeps these (#100).
 */
data class ShortcutDevice(
    val entityId: String,
    val name: String,
    val domain: String,
    val deviceClass: String? = null,
)

/**
 * What an app-icon shortcut may do with a device (#97, owner 2026-11-04).
 *
 * The launcher's menu is the four of [cloud.dopp.homes.einrichtung.Verknuepfungen];
 * the one with a device is the garage. Its tap is the start screen's tap on
 * the garage's tile: the receiver opens or closes it, and Homes' 403 puts the
 * confirm dialog before an open. A light's or a switch's shortcut pinned
 * before stays working the same way.
 *
 * **Security (#92 rules, unchanged):** the domain is the entity id's own
 * prefix ([domainOf]), never an extra; a lock never acts from a shortcut, and
 * no shortcut carries a service, so none can reach a latch.
 */
object DeviceShortcuts {

    /** The domains a shortcut's tap hands to the receiver as the device's own tap. */
    private val TAP_DOMAINS = setOf("light", "switch", "cover")

    /** May a shortcut for this domain tap the device? Never a lock. */
    fun actsDirectly(domain: String?): Boolean = domain in TAP_DOMAINS

    /**
     * The domain an entity id names, e.g. `lock.haustuer` → `lock` (#100).
     * Taken from the very string the call will be made against, so a tampered
     * shortcut aiming at `lock.haustuer` yields `lock`, and a lock never acts.
     * An id without a dot yields `""`, which acts on nothing either.
     */
    fun domainOf(entityId: String?): String =
        entityId?.substringBefore('.', "")?.trim().orEmpty()
}
