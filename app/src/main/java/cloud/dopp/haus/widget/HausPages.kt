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

    fun url(base: String, page: String): String = base.trimEnd('/') + page

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
    fun intent(ctx: Context, page: String): Intent {
        val base = ServerStore.baseUrl(ctx)
        val i = if (base.isNullOrBlank()) {
            Intent(ctx.applicationContext, HomeActivity::class.java).setAction(Intent.ACTION_MAIN)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url(base, page))).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** A widget tap that opens [page]. Resolved when the widget draws, so a new pairing shows on the next draw. */
    fun tap(ctx: Context, reqCode: Int, page: String): PendingIntent =
        PendingIntent.getActivity(
            ctx, reqCode, intent(ctx, page),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
