package cloud.dopp.haus.widget

import cloud.dopp.haus.R

/**
 * String resources for a [ConfirmVerb] (#85) — the question's verb and the
 * matching positive button. Kept next to the mapping (and off the Activity) so a
 * JVM test can assert that the button never contradicts the stated direction.
 */
object ConfirmWording {

    /** Lower-case verb for `widget_confirm_msg` ("… wirklich %2$s?"). */
    fun verbRes(verb: ConfirmVerb): Int = when (verb) {
        ConfirmVerb.LOCK -> R.string.widget_verb_lock
        ConfirmVerb.UNLOCK -> R.string.widget_verb_unlock
        ConfirmVerb.UNLATCH -> R.string.widget_verb_unlatch
        ConfirmVerb.CLOSE -> R.string.widget_verb_close
        ConfirmVerb.OPEN -> R.string.widget_verb_open
        ConfirmVerb.ARM -> R.string.widget_verb_arm
        ConfirmVerb.DISARM -> R.string.widget_verb_disarm
        ConfirmVerb.TOGGLE -> R.string.widget_verb_toggle
        ConfirmVerb.NEUTRAL -> R.string.widget_verb_neutral
    }

    /** Label of the confirming button — same verb, capitalised. */
    fun positiveRes(verb: ConfirmVerb): Int = when (verb) {
        ConfirmVerb.LOCK -> R.string.widget_confirm_lock
        ConfirmVerb.UNLOCK -> R.string.widget_confirm_unlock
        ConfirmVerb.UNLATCH -> R.string.widget_confirm_unlatch
        ConfirmVerb.CLOSE -> R.string.widget_confirm_close
        ConfirmVerb.OPEN -> R.string.widget_confirm_open
        ConfirmVerb.ARM -> R.string.widget_confirm_arm
        ConfirmVerb.DISARM -> R.string.widget_confirm_disarm
        ConfirmVerb.TOGGLE -> R.string.widget_confirm_toggle
        ConfirmVerb.NEUTRAL -> R.string.widget_confirm_neutral
    }
}
