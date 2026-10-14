package cloud.dopp.haus.widget

import android.view.View
import android.widget.ImageView
import android.widget.ListView
import cloud.dopp.haus.R

/**
 * What a config screen must already show when it is **re-entered** (#96).
 *
 * Both pickers ([WidgetConfigActivity], [ToolWidgetConfigActivity]) knew the instance's binding on the reconfigure
 * path from the start — and still opened looking exactly like a first placement
 * on the device. Two reasons, both fixed here:
 *
 * 1. **The mark was invisible.** It was a `· aktuell` suffix appended to one row
 *    among dozens — on the device picker, to the muted 13sp *second* line. Nothing
 *    told the eye which row it was, so the screen read as "starts empty" and, worse,
 *    as if the binding had been lost.
 * 2. **The row was merely on screen, not in front of the user.**
 *    `ListView.setSelection` in touch mode only guarantees visibility: it hands the
 *    list a layout hint that the end-of-list clamp is free to pull back, so the
 *    marked row lands at the bottom edge as readily as at the top. Measured on the
 *    JVM: a list of 30 with row 25 bound laid out at `firstVisiblePosition = 5`.
 *
 * So the mark is now a **painted row** — accent tint plus a check glyph, [paint] —
 * and the scroll puts it at the **top**, [scrollTo].
 *
 * The index itself is the one piece of logic worth pinning on the JVM, so it lives
 * here rather than inline in three activities: [markedIndex] is the whole rule —
 * a reconfigure marks the row carrying the current binding, a first placement
 * marks **nothing**, whatever the store happens to hold.
 */
object ConfigPrefill {

    /** No row is the current one — a first placement, or a filtered-out binding. */
    const val NONE = -1

    /**
     * Row index to mark and scroll to, or [NONE].
     *
     * [ConfigEntry.FIRST_PLACEMENT] always yields [NONE]: a fresh `appWidgetId`
     * carries no binding, and a stale key for a recycled id must not pre-fill a
     * widget the user has not chosen anything for yet.
     */
    fun <T> markedIndex(entry: ConfigEntry, rows: List<T>, isCurrent: (T) -> Boolean): Int =
        if (!entry.isReconfigure) NONE else rows.indexOfFirst(isCurrent)

    /** True while [index] names a row (i.e. there is something to pre-fill). */
    fun marks(index: Int): Boolean = index != NONE

    /** Draw [row] as the instance's current binding — or as an ordinary row. */
    fun paint(row: View, current: Boolean, check: ImageView?) {
        row.setBackgroundResource(if (current) R.drawable.row_current else 0)
        check?.visibility = if (current) View.VISIBLE else View.GONE
    }

    /**
     * Put the marked row at the **top** of [list].
     *
     * `setSelectionFromTop(i, 0)` is the positioning `setSelection` does not
     * promise. It is issued twice on purpose: once now, so the very first layout
     * already opens on the right row (no visible jump), and once posted, because
     * these screens re-lay out after the first frame — the status-bar inset arrives
     * late on notch phones and re-pads the root — which would otherwise drop the
     * list back to the top of the household.
     */
    fun scrollTo(list: ListView, index: Int) {
        if (!marks(index)) return
        list.setSelectionFromTop(index, 0)
        list.post { list.setSelectionFromTop(index, 0) }
    }
}
