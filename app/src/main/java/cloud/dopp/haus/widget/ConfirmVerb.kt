package cloud.dopp.haus.widget

/**
 * Which way a service moves the device — the only thing the confirm dialog is
 * allowed to claim. [TOGGLING] and [UNKNOWN] deliberately state no direction.
 */
enum class ConfirmDirection { SECURING, UNSECURING, TOGGLING, UNKNOWN }

/**
 * The wording a confirm dialog uses for a Home-Assistant service (#85).
 *
 * The old rule was `service.contains("close")`, which reads `cover.close_cover`
 * correctly but sends `lock.lock` and `alarm_control_panel.alarm_arm_away` down
 * the *open* branch — the dialog then asked about opening while the user was
 * locking up. This is the replacement: a pure, Android-free mapping from the
 * dotted `domain.action` service to a verb, each verb carrying its
 * [ConfirmDirection] so the positive button can never contradict the question.
 *
 * A lock gets its own words on purpose: `lock.lock` is *abschließen*, not
 * *schließen*, and `lock.open` (the door latch/buzzer) is not `lock.unlock`.
 * Anything unrecognised maps to [NEUTRAL] — never a guessed direction.
 */
enum class ConfirmVerb(val direction: ConfirmDirection) {
    /** `lock.lock` — abschließen. */
    LOCK(ConfirmDirection.SECURING),

    /** `lock.unlock` — aufschließen (the bolt, not the door). */
    UNLOCK(ConfirmDirection.UNSECURING),

    /** `lock.open` — the latch/buzzer, physically opening the door. */
    UNLATCH(ConfirmDirection.UNSECURING),

    /** `cover.close_cover` and friends. */
    CLOSE(ConfirmDirection.SECURING),

    /** `cover.open_cover` and friends. */
    OPEN(ConfirmDirection.UNSECURING),

    /** `alarm_control_panel.alarm_arm_*`. */
    ARM(ConfirmDirection.SECURING),

    /** `alarm_control_panel.alarm_disarm`. */
    DISARM(ConfirmDirection.UNSECURING),

    /** `*.toggle` — the direction depends on state we don't have here. */
    TOGGLE(ConfirmDirection.TOGGLING),

    /** Anything else — ask neutrally rather than guess. */
    NEUTRAL(ConfirmDirection.UNKNOWN),
    ;

    companion object {

        /** Map a dotted `domain.action` service to its verb. Never throws. */
        fun of(service: String?): ConfirmVerb {
            val s = service?.trim()?.lowercase() ?: return NEUTRAL
            val dot = s.indexOf('.')
            if (dot <= 0 || dot == s.length - 1) return NEUTRAL
            val domain = s.substring(0, dot)
            val action = s.substring(dot + 1)
            if (action == "toggle") return TOGGLE
            return when (domain) {
                "lock" -> when (action) {
                    "lock" -> LOCK
                    "unlock" -> UNLOCK
                    "open" -> UNLATCH
                    else -> NEUTRAL
                }
                // cover.open_cover / close_cover, valve.open_valve / close_valve.
                "cover", "valve" -> when {
                    action.startsWith("close") -> CLOSE
                    action.startsWith("open") -> OPEN
                    else -> NEUTRAL // stop_cover & co. move nothing we can name
                }
                "alarm_control_panel" -> when {
                    action.startsWith("alarm_arm") -> ARM
                    action == "alarm_disarm" -> DISARM
                    else -> NEUTRAL
                }
                else -> NEUTRAL
            }
        }
    }
}
