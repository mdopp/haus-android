package cloud.dopp.homes.hochladen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cloud.dopp.homes.R

/**
 * What the Hochladen job is doing, the one source of its notification and of
 * the start screen's row (owner, 2026-11-04: what the app shows as a
 * notification shows in the open app too): a file going up and how far, a
 * pause until later, or what came of the files since the person last looked.
 * Running and paused stay until the job is done; a result goes once the
 * person has seen it in the app, its notification with it.
 */
object Stand {
    const val GEAENDERT = "cloud.dopp.homes.hochladen.GEAENDERT"

    private const val PREFS = "homes_hochladen_stand"
    private const val NAME = "name"
    private const val PROZENT = "prozent"
    private const val PAUSE = "pause"
    private const val MUSIK = "musik"
    private const val ZEILE = "zeile"
    private const val GESEHEN = "gesehen"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun aendern(ctx: Context, was: android.content.SharedPreferences.Editor.() -> Unit) {
        p(ctx).edit { was() }
        runCatching { ctx.sendBroadcast(Intent(GEAENDERT).setPackage(ctx.packageName)) }
    }

    /** [name] goes up, [prozent] done (-1: not known yet). */
    fun lauf(ctx: Context, name: String, prozent: Int) = aendern(ctx) { putString(NAME, name).putInt(PROZENT, prozent).remove(PAUSE) }

    /** The job stopped before the queue was done; [text] says when it goes on. */
    fun pause(ctx: Context, text: String) = aendern(ctx) { remove(NAME).putString(PAUSE, text) }

    /** A file is done: one more song in the music, or [zeile] — the Standortverlauf's sentence, or why not. */
    @Synchronized
    fun fertig(ctx: Context, musik: Boolean, zeile: String?) {
        val p = p(ctx)
        val alt = p.getBoolean(GESEHEN, false) // a result seen in the app is replaced, not added to
        val n = if (alt) 0 else p.getInt(MUSIK, 0)
        aendern(ctx) {
            remove(NAME).remove(PAUSE).putBoolean(GESEHEN, false).putInt(MUSIK, if (musik) n + 1 else n)
            if (alt) remove(ZEILE)
            if (!musik) putString(ZEILE, zeile)
        }
    }

    /** The queue is done: nothing goes up any more. */
    fun leer(ctx: Context) = aendern(ctx) { remove(NAME) }

    /** The row's words now; null when there is nothing to say. */
    fun text(ctx: Context): String? {
        val p = p(ctx)
        return text(ctx.resources, p.getString(NAME, null), p.getInt(PROZENT, -1), p.getString(PAUSE, null), p.getInt(MUSIK, 0), p.getString(ZEILE, null))
    }

    /** The row's words for these facts: going up, else paused, else the results. Pure but for the words. */
    fun text(res: Resources, name: String?, prozent: Int, pause: String?, musik: Int, zeile: String?): String? = when {
        name != null && prozent >= 0 -> res.getString(R.string.hochladen_zeile_lauf, name, prozent)
        name != null -> res.getString(R.string.hochladen_zeile_beginnt, name)
        pause != null -> res.getString(R.string.hochladen_zeile_pause)
        else -> listOfNotNull(
            musik.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.hochladen_zeile_musik, it, it) },
            zeile,
        ).joinToString(" · ").ifEmpty { null }
    }

    /** Is the row a result (not a running or paused upload)? */
    private fun istErgebnis(ctx: Context): Boolean =
        p(ctx).let { it.getString(NAME, null) == null && it.getString(PAUSE, null) == null && (it.getInt(MUSIK, 0) > 0 || it.contains(ZEILE)) }

    /** The person saw the result in the app: its notifications go now, the row with the next visit. */
    fun gesehen(ctx: Context) {
        if (!istErgebnis(ctx)) return
        p(ctx).edit { putBoolean(GESEHEN, true) }
        Hochladen.ergebnisseWeg(ctx)
    }

    /** At a new visit: a result already seen is forgotten. */
    fun vergessen(ctx: Context) {
        if (p(ctx).getBoolean(GESEHEN, false) && istErgebnis(ctx)) aendern(ctx) { remove(MUSIK).remove(ZEILE).remove(GESEHEN) }
    }

    /** The start screen's row, at its top: drawn on each resume and each change while [a] is in front. */
    fun zeigen(a: ComponentActivity, view: TextView) {
        fun zeichnen() {
            val t = text(a)
            view.text = t
            view.visibility = if (t == null) View.GONE else View.VISIBLE
            if (t != null) gesehen(a)
        }
        val empfaenger = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = zeichnen()
        }
        a.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                vergessen(a)
                ContextCompat.registerReceiver(a, empfaenger, IntentFilter(GEAENDERT), ContextCompat.RECEIVER_NOT_EXPORTED)
                zeichnen()
            }

            override fun onPause(owner: LifecycleOwner) {
                runCatching { a.unregisterReceiver(empfaenger) }
            }
        })
    }
}
