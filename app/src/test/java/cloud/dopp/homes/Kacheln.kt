package cloud.dopp.homes

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView

/** The start screen's favourite tiles as the tests find them (owner, 2026-11-04: „Deine Geräte“ as tiles). */
object Kacheln {

    /** The tiles under „Deine Geräte“ in the favourites' order; each carries its entity id as its tag. */
    fun of(a: Activity): List<View> = walk(a.findViewById(R.id.devices_group))

    private fun walk(v: View): List<View> = when {
        v.tag is String -> listOf(v)
        v is ViewGroup -> (0 until v.childCount).flatMap { walk(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** What stands under „Deine Geräte“ when there are no tiles: one sentence each. */
    fun notes(a: Activity): List<String> = a.findViewById<ViewGroup>(R.id.devices_group).let { g ->
        (0 until g.childCount).mapNotNull { (g.getChildAt(it) as? TextView)?.text?.toString() }
    }

    /** A tile's fine buttons, by what a screen reader says of them („Rollladen: Hoch“). */
    fun buttons(v: View): List<ImageButton> =
        if (v is ImageButton) listOf(v) else if (v is ViewGroup) (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) } else emptyList()

    /** The visible texts of [v], in the order they are laid out. */
    fun texts(v: View): List<String> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else listOfNotNull((v as? TextView)?.takeIf { it.visibility == View.VISIBLE }?.text?.toString())
}
