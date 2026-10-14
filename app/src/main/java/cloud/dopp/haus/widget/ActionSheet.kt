package cloud.dopp.haus.widget

import cloud.dopp.haus.R

/**
 * How prominent one offered action is (#113) — and, for [DANGEROUS], how much it
 * costs to hit it by accident.
 */
enum class ActionTone {
    /** The one thing the dialog exists to offer (a confirm's yes). */
    PRIMARY,

    /** An ordinary choice among several. */
    NORMAL,

    /** Folgenschwer: `lock.open` and anything that opens the house (#92). */
    DANGEROUS,
}

/**
 * One offered action in an action dialog. [service] is the only thing that ever
 * leaves the app; [swatchRgb] is drawn as a dot in front of the label (the colour
 * palette, #87) and names nothing.
 */
data class ActionItem(
    val labelRes: Int,
    val tone: ActionTone = ActionTone.NORMAL,
    val service: String? = null,
    val swatchRgb: Int? = null,
    /**
     * A label **Haus** supplied (`tool-actions-titled`, #169).
     * When present it wins over [labelRes]; the titles arrive ready to read and
     * are not shortened or translated here — the same rule as `status_text`: the
     * server says, the client shows.
     *
     * [labelRes] stays required so every item has a fallback: a tool that declares
     * no title (or an older server that sends no titles at all) still reads
     * "Ausführen" rather than an empty row.
     */
    val label: String? = null,
)

/** A rendered line of the dialog, in the order it is drawn. */
sealed interface ActionRow {
    /** The warning above the dangerous block — never itself pickable. */
    data class Note(val textRes: Int) : ActionRow

    /** A pickable action; [index] indexes [ActionSheet.items]. */
    data class Pick(val item: ActionItem, val index: Int) : ActionRow

    /** "Abbrechen" — the footer, and by construction always the last row. */
    object Cancel : ActionRow
}

/**
 * The one shape every widget action dialog has (#113).
 *
 * Three dialogs used to sit side by side in [WidgetActionActivity]: a confirm
 * with a positive button, the lock chooser with a list plus a *neutral* button,
 * and the colour palette with a list. Material puts the neutral button far left
 * and negative/positive right, so "Abbrechen" landed in a different place per
 * dialog — reported from the device as dangerous, because muscle memory then
 * hits the wrong thing.
 *
 * Here every dialog is the same object: a title, an optional question, a
 * vertical stack of actions, and **Abbrechen as a fixed footer**. [rows] appends
 * [ActionRow.Cancel] last for every sheet there can ever be, so the safe answer
 * is always in the same spot and no action can occupy it. The dangerous block is
 * kept apart the way #92 demanded — last in the stack, behind its own warning,
 * in the warning colour — but *above* Abbrechen, never in its place.
 *
 * Pure (resource ids only) → the whole arrangement is JVM-testable; only the
 * drawing is a device matter.
 */
data class ActionSheet(
    val items: List<ActionItem>,
    /** Warning shown above the first dangerous item; required when there is one. */
    val noteRes: Int? = null,
) {
    init {
        val firstDanger = items.indexOfFirst { it.tone == ActionTone.DANGEROUS }
        // Dangerous last, always: the finger that slips lands on an ordinary
        // entry, never on the door.
        require(firstDanger < 0 || items.drop(firstDanger).all { it.tone == ActionTone.DANGEROUS }) {
            "a dangerous action must come after every ordinary one"
        }
        require(firstDanger < 0 || noteRes != null) {
            "a dangerous action must carry its warning"
        }
    }

    /** Does the sheet offer anything that opens the house? */
    val hasDangerous: Boolean get() = items.any { it.tone == ActionTone.DANGEROUS }

    /** Title, question and actions are the caller's; this is the fixed order. */
    val rows: List<ActionRow>
        get() = buildList {
            var noted = false
            items.forEachIndexed { i, item ->
                if (item.tone == ActionTone.DANGEROUS && !noted) {
                    noteRes?.let { add(ActionRow.Note(it)) }
                    noted = true
                }
                add(ActionRow.Pick(item, i))
            }
            add(ActionRow.Cancel)
        }

    /** Where "Abbrechen" sits — the bottom, in every sheet. */
    val cancelIndex: Int get() = rows.lastIndex
}

/**
 * Every action dialog the app shows, assembled in one place (#113).
 *
 * The tool row's sheet (#90) slots in here rather than growing a fourth layout
 * next to the others — that is the point of this object.
 */
object ActionSheets {

    /**
     * The server's 403 confirm gate, as a sheet: exactly one action, worded by
     * [ConfirmWording] so the button can never contradict the question (#85).
     * `lock.open` is dangerous wherever it appears, including here.
     */
    fun confirm(verb: ConfirmVerb): ActionSheet {
        val dangerous = verb == ConfirmVerb.UNLATCH
        return ActionSheet(
            items = listOf(
                ActionItem(
                    labelRes = ConfirmWording.positiveRes(verb),
                    tone = if (dangerous) ActionTone.DANGEROUS else ActionTone.PRIMARY,
                ),
            ),
            noteRes = if (dangerous) R.string.widget_open_door_note else null,
        )
    }

    /**
     * The tool widget's confirm (#70/#90). The widget knows no verb for a catalog
     * action id, so the wording names the row and the single action is neutral —
     * inventing a direction would claim something the def never stated.
     */
    fun toolConfirm(): ActionSheet =
        ActionSheet(listOf(ActionItem(R.string.tool_action_do, ActionTone.PRIMARY)))

    /**
     * The tool row's sheet (#90, #169): one entry per action the row can fill, in
     * the order the tool declared them, each under its own title, then the
     * "Abbrechen" footer [ActionSheet] appends. A title of null falls back to
     * "Ausführen".
     *
     * The row itself carries one tap target (three attempts at a second one inside
     * a collection row lost the press on the device), so its choices live here —
     * the pattern the lock chooser (#92) proves.
     *
     * Nothing here is dangerous: a catalog action is whatever the tool declared,
     * and the server decides which need confirming (the `confirm_required` 403,
     * answered by [toolConfirm]) — the widget does not promote one on its own.
     *
     * With [open] the row's page on Haus is one more entry, after the actions, so
     * an action keeps its place (#178).
     */
    fun toolActions(titles: List<String?>, open: Boolean = false): ActionSheet =
        ActionSheet(
            titles.map { ActionItem(labelRes = R.string.tool_action_do, tone = ActionTone.PRIMARY, label = it) } +
                listOfNotNull(if (open) ActionItem(R.string.tool_open) else null),
        )

    /**
     * The 1×1 lock tile's chooser (#92). What is offered still comes from
     * [LockChooser] — the latch appears only for a lock that advertises one — and
     * the pick is the confirmation, so no second dialog is stacked on top.
     *
     * "Tür öffnen" is the last entry, marked dangerous and preceded by its
     * warning. It used to be the neutral button, i.e. the far-left slot, which is
     * where other dialogs put nothing at all and where the eye looks for the safe
     * answer.
     */
    fun lock(supportedFeatures: Int?): ActionSheet {
        val entries = LockChooser.entries(supportedFeatures)
        return ActionSheet(
            items = entries.map {
                ActionItem(
                    labelRes = it.labelRes,
                    tone = if (it == LockChoice.OPEN) ActionTone.DANGEROUS else ActionTone.NORMAL,
                    service = it.service,
                )
            },
            noteRes = R.string.widget_open_door_note,
        )
    }

    /**
     * The named-colour list of a colour-capable light (#87), in palette order —
     * the pick's index is [LightColors.PALETTE]'s index. Colour is not a
     * sensitive action: no entry here is dangerous.
     */
    fun colors(): ActionSheet =
        ActionSheet(
            LightColors.PALETTE.map {
                ActionItem(labelRes = it.labelRes, swatchRgb = it.rgb)
            },
        )
}
