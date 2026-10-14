package cloud.dopp.haus.widget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import cloud.dopp.haus.R

/**
 * Draws an [ActionSheet] — the one dialog every widget action uses (#113/#114).
 *
 * The bare `AlertDialog` button row is gone: a sheet is a Haus card with the
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
    ): AlertDialog {
        scrim(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_action, null)
        view.findViewById<TextView>(R.id.ad_title).text = title
        val msg = view.findViewById<TextView>(R.id.ad_message)
        if (message.isNullOrBlank()) msg.visibility = View.GONE else msg.text = message

        val dialog = AlertDialog.Builder(activity, R.style.Theme_Haus_ActionDialog)
            .setView(view)
            .setOnCancelListener { onCancel() }
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
        view.findViewById<View>(R.id.ad_cancel).setOnClickListener {
            dialog.dismiss()
            onCancel()
        }
        capStack(activity, view.findViewById(R.id.ad_scroll))
        dialog.show()
        return dialog
    }

    /**
     * A defined backdrop for a translucent action activity (#114): its window is
     * painted with the Haus scrim, so nothing of the app's task shows through
     * and the dialog looks identical in every launch state.
     */
    fun scrim(activity: Activity) {
        activity.window?.setBackgroundDrawable(
            ColorDrawable(activity.getColor(R.color.haus_scrim)),
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
                v.setBackgroundResource(R.drawable.btn_haus)
                label.setTextColor(ctx.getColor(R.color.haus_text))
            }
            ActionTone.NORMAL -> {
                v.setBackgroundResource(R.drawable.btn_haus_ghost)
                label.setTextColor(ctx.getColor(R.color.haus_text))
            }
            ActionTone.DANGEROUS -> {
                v.setBackgroundResource(R.drawable.btn_haus_warn)
                label.setTextColor(ctx.getColor(R.color.haus_warn))
            }
        }
        val swatch = v.findViewById<View>(R.id.ar_swatch)
        val rgb = item.swatchRgb
        if (rgb == null) {
            swatch.visibility = View.GONE
        } else {
            swatch.visibility = View.VISIBLE
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF000000.toInt() or rgb)
                setStroke(1, ctx.getColor(R.color.haus_card_edge))
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
     * Keep the stack from pushing Abbrechen off the screen: the palette offers ten
     * colours, and a footer that has scrolled away is a footer that has moved.
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
