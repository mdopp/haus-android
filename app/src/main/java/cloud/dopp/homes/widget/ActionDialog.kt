package cloud.dopp.homes.widget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import cloud.dopp.homes.R

/**
 * Draws an [ActionSheet] — the one dialog of the whole app (#113/#114; owner,
 * 2026-11-03: every confirmation looks alike). What — the title; why and what
 * it means — the question; then the actions: the positive one filled in the
 * accent, a secondary one outlined, the one that opens the house outlined in
 * the warning colour; and the neutral footer („Abbrechen“, „Später“,
 * „Fertig“) last. Used by the device actions, the tool rows, the pairing,
 * the battery question and Share → Homes.
 *
 * The bare `AlertDialog` button row is gone: a sheet is a Homes card with the
 * actions stacked as full-width rows and **Abbrechen pinned underneath them**,
 * outside the scrolling part, so it keeps its place whether the sheet offers one
 * action or ten. The dangerous row (there is exactly one today, `lock.open`)
 * sits last in the stack, in the warning colour, behind its own line of warning
 * — set apart as #92 demanded, but never in the slot the eye has learned to read
 * as "Abbrechen".
 *
 * It also owns the **backdrop** (#114). The action activities are translucent, so
 * without this they showed whatever happened to be on top of the app's task —
 * the last screen the user had open, or the homescreen when no task existed.
 * [scrim] paints the activity's own window instead, so the dialog looks the same
 * whether the app is closed, backgrounded or in front.
 */
object ActionDialog {

    /**
     * How a dialog activity must be launched (#114). `NEW_TASK` alone let the
     * activity join the app's existing task; with `taskAffinity=""` in the
     * manifest it gets a task of its own, and `MULTIPLE_TASK` keeps a second
     * action from being folded back into the first one's task.
     */
    const val TASK_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK

    /** At most this share of the screen goes to the scrolling action stack. */
    private const val MAX_STACK_FRACTION = 0.55f

    /**
     * Show [sheet] on [activity]. [onPick] gets the index into [ActionSheet.items];
     * [onCancel] runs for Abbrechen, the back gesture and a tap outside — all
     * three do the same nothing, which is what makes the footer the safe default.
     */
    fun show(
        activity: Activity,
        title: CharSequence,
        message: CharSequence?,
        sheet: ActionSheet,
        onPick: (Int) -> Unit,
        onCancel: () -> Unit,
        /** The neutral footer's word; null: no footer (a dialog that waits for an answer). */
        footer: CharSequence? = activity.getString(R.string.widget_confirm_no),
        /** A translucent action activity paints its window ([scrim]); a screen of the app does not. */
        paintScrim: Boolean = true,
        cancelable: Boolean = true,
    ): AlertDialog {
        if (paintScrim) scrim(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_action, null)
        view.findViewById<TextView>(R.id.ad_title).text = title
        val msg = view.findViewById<TextView>(R.id.ad_message)
        if (message.isNullOrBlank()) msg.visibility = View.GONE else msg.text = message

        val dialog = AlertDialog.Builder(activity, R.style.Theme_Homes_ActionDialog)
            .setView(view)
            .setOnCancelListener { onCancel() }
            .setCancelable(cancelable)
            .create()

        val stack = view.findViewById<LinearLayout>(R.id.ad_items)
        for (row in sheet.rows) when (row) {
            is ActionRow.Note -> stack.addView(note(stack, row.textRes))
            is ActionRow.Pick -> stack.addView(
                pick(stack, row.item) {
                    dialog.dismiss()
                    onPick(row.index)
                },
            )
            // The footer is part of the layout, not of the stack — that is
            // precisely what keeps it in one place across every sheet.
            ActionRow.Cancel -> Unit
        }
        val cancel = view.findViewById<android.widget.Button>(R.id.ad_cancel)
        if (footer == null) {
            cancel.visibility = View.GONE
            view.findViewById<View>(R.id.ad_divider).visibility = View.GONE
        } else {
            cancel.text = footer
        }
        cancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }
        capStack(activity, view.findViewById(R.id.ad_scroll))
        dialog.show()
        return dialog
    }

    /**
     * A defined backdrop for a translucent action activity (#114): its window is
     * painted with the Homes scrim, so nothing of the app's task shows through
     * and the dialog looks identical in every launch state.
     */
    fun scrim(activity: Activity) {
        activity.window?.setBackgroundDrawable(
            ColorDrawable(activity.getColor(R.color.homes_scrim)),
        )
    }

    /** One pickable row: ghost by default, filled for the yes, warned for the door. */
    private fun pick(parent: ViewGroup, item: ActionItem, onClick: () -> Unit): View {
        val ctx = parent.context
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_action_row, parent, false)
        val label = v.findViewById<TextView>(R.id.ar_label)
        // A server-supplied title wins; the resource is the fallback (#169).
        item.label?.takeIf { it.isNotBlank() }?.let { label.text = it } ?: label.setText(item.labelRes)
        when (item.tone) {
            ActionTone.PRIMARY -> {
                v.setBackgroundResource(R.drawable.btn_homes)
                label.setTextColor(ctx.getColor(R.color.homes_text))
            }
            ActionTone.NORMAL -> {
                v.setBackgroundResource(R.drawable.btn_homes_ghost)
                label.setTextColor(ctx.getColor(R.color.homes_text))
            }
            ActionTone.DANGEROUS -> {
                v.setBackgroundResource(R.drawable.btn_homes_warn)
                label.setTextColor(ctx.getColor(R.color.homes_warn))
            }
        }
        v.setOnClickListener { onClick() }
        return v
    }

    /** The warning line above the dangerous block (#92) — not pickable. */
    private fun note(parent: ViewGroup, textRes: Int): View =
        (LayoutInflater.from(parent.context)
            .inflate(R.layout.item_action_note, parent, false) as TextView)
            .apply { setText(textRes) }

    /**
     * Keep the stack from pushing Abbrechen off the screen: a tool row may offer
     * many actions, and a footer that has scrolled away is a footer that has moved.
     */
    private fun capStack(activity: Activity, scroll: View) {
        val max = (activity.resources.displayMetrics.heightPixels * MAX_STACK_FRACTION).toInt()
        scroll.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > max && v.layoutParams.height != max) {
                v.layoutParams.height = max
                v.requestLayout()
            }
        }
    }
}
