package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.ui.HomeActivity

/**
 * Where a widget's body tap goes: one of Haus's own pages, in the person's
 * browser — where their Authelia session is (laib ADR 0022: the app has no web
 * shell of its own; Haus's pages run in any browser). A device, an active row
 * and a tool row open the item's own page when Haus names one ([item]).
 *
 * The pages are Haus's (`cmd/haus/main.go`); `NapiContractTest` holds the app
 * to them. Unpaired, a tap opens the app instead, which asks for Haus's address.
 */
object HausPages {

    /** The house: rooms, devices, energy. */
    const val START = "/start"

    /** The Box, for admins. */
    const val BOX = "/box"

    /** [page] of [base]; from a widget it carries `von=widget.<kind>` exactly once (#180), so Haus counts the tap. */
    fun url(base: String, page: String, from: WidgetKind? = null): String {
        val full = base.trimEnd('/') + page
        if (from == null) return full
        val fragment = full.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        val noFragment = full.substringBefore('#')
        val query = noFragment.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("von=") }
        return noFragment.substringBefore('?') + "?" + (query + "von=${from.von}").joinToString("&") + fragment
    }

    /**
     * [raw] when it is a page on Haus: a path from its root, as Haus names an
     * item's own page (`page`, `/raeume/<entity_id>`). An absolute or
     * protocol-relative URL, a backslash, `..` or whitespace inside is null, so
     * a reply can never send a tap off Haus.
     */
    fun page(raw: String?): String? = raw?.trim()?.takeIf { p ->
        p.startsWith("/") && !p.startsWith("//") && ".." !in p &&
            p.none { it == '\\' || it.isWhitespace() || it.isISOControl() }
    }

    /** An item's tap: its own page when Haus named one, else [START]. */
    fun item(raw: String?): String = page(raw) ?: START

    /** The intent that shows [page] of the paired Haus, or the app when there is none. */
    fun intent(ctx: Context, page: String, from: WidgetKind? = null): Intent {
        val base = ServerStore.baseUrl(ctx)
        val i = if (base.isNullOrBlank()) {
            Intent(ctx.applicationContext, HomeActivity::class.java).setAction(Intent.ACTION_MAIN)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url(base, page, from))).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** A widget tap that opens [page]. Resolved when the widget draws, so a new pairing shows on the next draw. */
    fun tap(ctx: Context, reqCode: Int, page: String, from: WidgetKind): PendingIntent =
        PendingIntent.getActivity(
            ctx, reqCode, intent(ctx, page, from),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * The widgets that open a Haus page, by the name Haus counts them under
 * (`von=widget.<kind>`, #180). The camera widget opens no page. The old app did the
 * same with `?src=widget.*` before; the app itself counts nothing.
 */
enum class WidgetKind(val kind: String) {
    DEVICE("device"),
    DEVICES("devices"),
    ACTIVE("active"),
    ENERGY("energy"),
    ENERGY_STATS("energy-stats"),
    ENERGY_TOTALS("energy-totals"),
    BOX_OVERVIEW("box-overview"),
    BOX_SERVICES("box-services"),
    TOOL("tool"),
    ;

    val von: String get() = "widget.$kind"

    companion object {
        /** The kind an intent extra names, or null for anything else: nothing foreign reaches Haus's URL. */
        fun of(raw: String?): WidgetKind? = values().firstOrNull { it.kind == raw }
    }
}
