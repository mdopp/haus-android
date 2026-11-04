package cloud.dopp.homes.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import cloud.dopp.homes.R
import cloud.dopp.homes.kann.Anfordern
import cloud.dopp.homes.kann.Anfrage
import cloud.dopp.homes.kann.Modul

/**
 * The quiet trace of what the box does not have (homes ADR 0001 §10): one
 * grey line where the feature would be — its title, where it stands, and
 * „Hier anfordern“ while Homes offers it. It must read as „gibt es hier noch
 * nicht“, never as something broken: grey text and icon, no red, no warning.
 */
object Spuren {

    /** The trace's words for [m]: the app's own for a key it knows, else Homes' German, else „nicht eingerichtet“. */
    fun stand(ctx: Context, m: Modul?): String {
        val res = when (m?.spurId) {
            "fehlt" -> R.string.spur_fehlt
            "aus" -> R.string.spur_aus
            "angefordert" -> R.string.spur_angefordert
            "wartet" -> R.string.spur_wartet
            "abgelehnt" -> R.string.spur_abgelehnt
            "eingerichtet" -> R.string.spur_eingerichtet
            else -> null
        }
        return res?.let(ctx::getString) ?: m?.spur ?: ctx.getString(R.string.spur_fehlt)
    }

    /** How a request ended when not as wished, in words; null while none did. */
    fun satz(ctx: Context, a: Anfrage): String? = when (a) {
        is Anfrage.Abgelehnt -> if (a.code == Anfordern.UNAUTHORIZED) {
            ctx.getString(R.string.anfordern_gekoppelt)
        } else {
            a.satz ?: ctx.getString(R.string.anfordern_nicht)
        }
        Anfrage.Zeit -> ctx.getString(R.string.anfordern_zeit)
        Anfrage.Offline -> ctx.getString(R.string.anfordern_offline)
        else -> null
    }

    /**
     * One trace row in [group]: [titel] with [icon], [m]'s state below, and
     * „Hier anfordern“ while [m] offers it. A press answers at once and
     * [neu] draws the rows again, then once more when the request ended.
     */
    fun zeile(activity: Activity, group: ViewGroup, titel: String, @DrawableRes icon: Int, m: Modul?, neu: () -> Unit): View {
        val v = activity.layoutInflater.inflate(R.layout.item_spur, group, false)
        v.findViewById<ImageView>(R.id.spur_icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.spur_titel).text = titel
        val stand = stand(activity, m)
        v.findViewById<TextView>(R.id.spur_stand).text = stand
        v.contentDescription = "$titel, $stand"
        val a = m?.let { Anfordern.stand(it.name) } ?: Anfrage.Bereit
        satz(activity, a)?.let { s ->
            v.findViewById<TextView>(R.id.spur_satz).apply {
                text = s
                visibility = View.VISIBLE
            }
        }
        val btn = v.findViewById<Button>(R.id.spur_anfordern)
        if (m != null && (m.anfordern || a == Anfrage.Laeuft)) {
            btn.visibility = View.VISIBLE
            val running = a == Anfrage.Laeuft
            btn.isEnabled = !running
            btn.setText(if (running) R.string.anfordern_laeuft else R.string.anfordern)
            btn.contentDescription = if (running) activity.getString(R.string.anfordern_laeuft) else activity.getString(R.string.anfordern_beschreibung, titel)
            btn.setOnClickListener {
                Anfordern.los(activity, m.name) { if (!activity.isFinishing) neu() }
                neu()
            }
        }
        return v
    }
}
