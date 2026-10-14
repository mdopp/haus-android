package cloud.dopp.haus.widget

/**
 * One pickable lock action (#92). The dotted service is the only thing that
 * leaves the app; the wording comes from [ConfirmVerb] so the chooser and the
 * confirm dialog can never drift apart.
 */
enum class LockChoice(val service: String) {
    /** The bolt back — the door stays shut. */
    UNLOCK("lock.unlock"),

    /** The bolt out. */
    LOCK("lock.lock"),

    /** The latch — this one physically opens the door. Chooser-only. */
    OPEN("lock.open"),
    ;

    /** Direction + wording, straight from the #85 table. */
    val verb: ConfirmVerb get() = ConfirmVerb.of(service)

    /** The entry's label — the same capitalised verb the confirm button uses. */
    val labelRes: Int get() = ConfirmWording.positiveRes(verb)
}

/**
 * What the 1×1 lock tile's chooser offers (#92).
 *
 * On the tiny tier a tap used to run [WidgetActionReceiver.lockToggleService]
 * blind: the tile shows barely any state there, so a mistap flipped the bolt of
 * the front door. The tap now opens a chooser instead, and **the chooser is the
 * confirmation** — there is no longer any path that acts without an explicit
 * pick, and no second dialog on top of it.
 *
 * `lock.open` is the odd one out. Haus does not block it — `/napi/ha/call`
 * takes the whole `lock` domain and gates every `lock.*` with the
 * authoritative 403 `sensitive_action` (Haus's `internal/napi/home.go`) — but
 * the *lock* may not have a latch at all. HA advertises one via
 * `LockEntityFeature.OPEN`, bit 0 of the entity's `supported_features`, which
 * Haus's card forwards. No proof of
 * that bit ⇒ no door-opening entry: a missing capability must never render as an
 * offer to open the house.
 *
 * Pure (no Android) → JVM-testable, which is the point: this decides the one
 * thing that must never be got wrong by accident.
 */
object LockChooser {

    /** `LockEntityFeature.OPEN` — bit 0 of a lock's `supported_features`. */
    const val FEATURE_OPEN = 1

    /**
     * The bolt entries, the chooser's ordinary list rows. Unlocking comes first
     * (it is what the tile is tapped for), locking second. Never contains
     * [LockChoice.OPEN] — the latch is not a list row.
     */
    val BOLT: List<LockChoice> = listOf(LockChoice.UNLOCK, LockChoice.LOCK)

    /** Does this lock advertise a latch, i.e. may "Tür öffnen" be offered? */
    fun offersOpen(supportedFeatures: Int?): Boolean =
        supportedFeatures != null && (supportedFeatures and FEATURE_OPEN) != 0

    /**
     * Every entry the chooser shows for a lock with these `supported_features`
     * — the two bolt directions, plus the latch when (and only when) the lock
     * says it has one. `null` (no card cached, an old server that omits the
     * attribute) falls to the two safe entries.
     */
    fun entries(supportedFeatures: Int?): List<LockChoice> =
        if (offersOpen(supportedFeatures)) BOLT + LockChoice.OPEN else BOLT
}
