package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import cloud.dopp.solaris.ui.SolarisSurface

/**
 * Opens the Solaris **PWA** in a Custom Tab from a widget tap (#27) — so tapping a
 * widget lands in the live web view (energy screen / device view), not the bare
 * app shell. Mirrors the onboarding `openUrl` pattern: Custom Tab first, a plain
 * VIEW intent as fallback (both covered by the manifest `<queries>`), and the
 * onboarding hub if the server isn't configured yet.
 */
object PwaLauncher {

    /**
     * PWA deep-link routes appended to `ServerStore.baseUrl`. Confirmed server-side
     * in solarisbay#769 (the PWA is hash-routed under `/#/p/…`); centralised here so
     * every widget tap lands on the exact view.
     */
    object Routes {
        /** Energy widgets → the energy portal view (confirmed solarisbay#769). */
        const val ENERGY = "/#/p/energy"

        /** Generic/overview tap → the PWA root. */
        const val ROOT = "/"

        /**
         * The household start page (#167) — the resident's pinned devices with
         * live state and controls, refreshing itself while open (solarisbay#711).
         *
         * This is where anything ABOUT DEVICES belongs. [ROOT] is the chat, and a
         * device widget that lands there answers nothing — the same empty
         * destination a notice tap used to have (#158).
         */
        const val START = "/#/p/start"

        /**
         * The signed APK the paired server publishes (#143, contract
         * solarisbay#1326). Base-relative on purpose: the address never comes
         * from a notification's payload, only from the server we are paired with.
         */
        const val DOWNLOAD = "/download"

        /**
         * ServiceBay tap **fallback** for an unpaired install (#109): with no base
         * there is no admin host to derive, so the tap keeps the old behaviour and
         * lands on the PWA root (which bounces to onboarding). The paired case does
         * NOT come through here — see [serviceBayUrl].
         */
        const val SERVICEBAY = "/"

        /**
         * A single device's card view (confirmed solarisbay#769) — a tap on a
         * device widget's name, or an active-devices row, opens exactly that
         * entity's card.
         */
        fun device(entityId: String): String = "/#/p/device/$entityId"

        /**
         * Camera widget (#36): open the PWA's live camera view for [entityId]
         * (confirmed placeholder, solarisbay#782). The camera widget is a pure
         * trigger tile — it no longer loads a snapshot; a tap lands on the live
         * view. Falls back to the generic camera root when no entity is bound.
         */
        fun camera(entityId: String): String = "/#/p/camera/$entityId"

        /** The generic camera view when no specific entity is bound yet (#36). */
        const val CAMERA_ROOT = "/#/p/camera"

        /**
         * A single ServiceBay approval's **verdict page** (#43, solarisbay#1085).
         * [id] is the approval id from the `servicebay` SSE frame `{id,kind,summary}`
         * or `GET /napi/box/approvals`. The page's buttons POST the verdict
         * over the Authelia session — the device token cannot (servicebay#2249),
         * which is why a tap must land here in the Custom Tab, not render natively.
         *
         * The **hash is not cosmetic**: server-side `/p/{type}` matches only one path
         * segment, so `/p/servicebay/approvals/<id>` 404s — only the hash form, which
         * the client router resolves, works. `PwaLauncherUrlTest` locks this shape.
         */
        fun approval(id: String): String = "/#/p/servicebay/approvals/$id"

        /**
         * Voice widget (#17): open a NEW household chat pre-loaded with [text] as the
         * user turn, which the PWA auto-sends once on load (confirmed solarisbay#766,
         * consumed via history.replaceState). The text is URL-encoded so spaces and
         * umlauts survive. Pure/Android-free so it's unit-testable.
         */
        fun ask(text: String): String =
            "/#/?ask=" + java.net.URLEncoder.encode(text, "UTF-8")

        /**
         * Tool launcher tile, mode **B** (#71, confirmed solarisbay#1213): open the
         * tool's **create** screen. [declared] is the def's own `tool-compose-path`
         * (`#/p/task/new`) — the route is never synthesised here, because only the
         * server knows whether a tool has a create path at all, and a guessed one
         * lands on [TOOL_START]. Only the leading slash of the join is added.
         */
        fun toolCompose(declared: String): String =
            if (declared.startsWith("/")) declared else "/$declared"

        /**
         * Tool launcher tile, mode **C** (#71, solarisbay#1213): open the chat with
         * this tool's card already rendered. The PWA consumes `?tool=` **once** via
         * `history.replaceState`, and `?ask=` wins when both are present — which is
         * why the voice tile ([ask]) and this one never share a URL.
         */
        fun toolChat(toolId: String): String =
            "/#/?tool=" + java.net.URLEncoder.encode(toolId, "UTF-8")

        /**
         * A single **item** of a tool (#107, solarisbay#1256):
         * `#/p/<tool-id>/item/<item-id>`. `item` is its own path segment, which is
         * what keeps it from colliding with the create route `#/p/<tool-id>/new`
         * (#1213) and lets an id that contains dots — `doc` ids look like
         * `doc.2026-08.rechnung` — pass through untouched.
         *
         * The id is **not** percent-encoded: the route is matched as literal
         * segments, and `URLEncoder` would turn a space into `+`, which a hash
         * router reads as a plus. An id carrying a separator of the route's own
         * grammar is therefore refused instead, exactly like a missing one — both
         * land on [TOOL_START], the fallback the PWA itself uses for an unknown
         * tool or a deleted item.
         */
        fun toolItem(toolId: String?, itemId: String?): String {
            val tool = toolId?.trim().orEmpty()
            val item = itemId?.trim().orEmpty()
            if (tool.isEmpty() || item.isEmpty()) return TOOL_START
            if (ROUTE_UNSAFE.containsMatchIn(tool) || ROUTE_UNSAFE.containsMatchIn(item)) {
                return TOOL_START
            }
            return "/#/p/$tool/item/$item"
        }

        /** Characters that would end the segment they sit in rather than ride in it. */
        private val ROUTE_UNSAFE = Regex("[/?#\\s]")

        /**
         * Where both tool deep links land when the id no longer resolves — a tile
         * outlives the `.tool` it points at (uninstalled, renamed). The PWA does
         * this fallback itself; the constant exists so the app can too.
         */
        const val TOOL_START = START
    }

    /**
     * Open `baseUrl + path` in the app's own Solaris surface (#115). If no server
     * is configured, bounce to the onboarding hub so the user can pair first.
     */
    fun open(ctx: Context, path: String) {
        val base = ServerStore.baseUrl(ctx)
        if (base.isNullOrBlank()) {
            openHome(ctx)
            return
        }
        openAbsolute(ctx, url(base, path))
    }

    /**
     * Open an **absolute** [url] on any host — NOT joined to the paired Solaris
     * base (#45). A URL on the paired server's own origin is shown **inside the
     * app** ([SolarisSurface], #115), so a widget tap, a shortcut and a
     * notification all land in the same view as the app icon does. Every other
     * host — the ServiceBay admin surface (#45/#109) above all — keeps the Custom
     * Tab, because our assetlinks handshake does not cover it.
     */
    fun openAbsolute(ctx: Context, url: String) {
        if (url.isBlank()) { openHome(ctx); return }
        if (SolarisSurface.isOwnOrigin(ServerStore.baseUrl(ctx), url) &&
            SolarisSurface.start(ctx, url)
        ) {
            return
        }
        customTab(ctx, url)
    }

    /**
     * The pre-#115 path, still the fallback: a Custom Tab, then a plain VIEW
     * intent, then the onboarding hub. Used for foreign hosts and whenever the
     * in-app surface can't be shown.
     */
    fun customTab(ctx: Context, url: String) {
        if (url.isBlank()) { openHome(ctx); return }
        val uri = Uri.parse(url)
        val app = ctx.applicationContext
        try {
            val ct = CustomTabsIntent.Builder().build()
            ct.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ct.launchUrl(app, uri)
        } catch (e: Exception) {
            try {
                app.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e2: Exception) {
                openHome(app)
            }
        }
    }

    /**
     * Join [base] + [path] into a single URL, collapsing the seam so a trailing
     * slash on the base and a leading slash on the path never double up. Pure and
     * Android-free so it's unit-testable (#27).
     */
    fun url(base: String, path: String): String = base.trimEnd('/') + path

    private fun openHome(ctx: Context) {
        ctx.applicationContext.startActivity(
            Intent(ctx.applicationContext, OnboardingHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * A widget body-tap [PendingIntent] that opens the PWA at [path] via the
     * invisible [PwaTrampolineActivity]. `reqCode` must be unique per widget id so
     * distinct instances don't share (and overwrite) each other's intent.
     */
    fun tapPending(ctx: Context, reqCode: Int, path: String): PendingIntent {
        val i = Intent(ctx, PwaTrampolineActivity::class.java)
            .putExtra(PwaTrampolineActivity.EXTRA_PATH, path)
            // Own task (#114) — the trampoline must not join the app's.
            .addFlags(ActionDialog.TASK_FLAGS)
        return PendingIntent.getActivity(
            ctx, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A notification/widget tap [PendingIntent] that opens an **absolute** [url]
     * (any host, not the paired Solaris base) via the trampoline — for ServiceBay
     * targets like the admin UI (#45). `reqCode` unique per use.
     */
    fun tapPendingUrl(ctx: Context, reqCode: Int, url: String): PendingIntent {
        val i = Intent(ctx, PwaTrampolineActivity::class.java)
            .putExtra(PwaTrampolineActivity.EXTRA_URL, url)
            .addFlags(ActionDialog.TASK_FLAGS)
        return PendingIntent.getActivity(
            ctx, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The ServiceBay tap target derived from the paired Solaris [base] (#109).
     *
     * ServiceBay lives on its **own host** (`admin.<apex>`, see
     * [cloud.dopp.solaris.SolarisConfig.adminUrl]) — the decision already taken for
     * the updates notification (#45): a ServiceBay surface is never a path under the
     * Solaris base, so joining [Routes.SERVICEBAY] onto it lands in the chat instead.
     * Returns null when no server is paired; the caller then keeps the old
     * base-relative fallback. Pure / Android-free so it's unit-testable.
     */
    fun serviceBayUrl(base: String?): String? =
        if (base.isNullOrBlank()) null else cloud.dopp.solaris.SolarisConfig.adminUrl(base)

    /**
     * A tap [PendingIntent] onto the ServiceBay surface (#109) — the absolute admin
     * URL when a server is paired, the base-relative fallback otherwise. Shared by
     * the two ServiceBay widgets and the updates notification so all three land on
     * the same place. `reqCode` unique per use.
     */
    fun serviceBayTap(ctx: Context, reqCode: Int): PendingIntent {
        val url = serviceBayUrl(ServerStore.baseUrl(ctx))
        return if (url != null) tapPendingUrl(ctx, reqCode, url)
        else tapPending(ctx, reqCode, Routes.SERVICEBAY)
    }

    /**
     * The `PendingIntentTemplate` for a collection widget's ListView (#28): a single
     * mutable [PendingIntent] into [PwaTrampolineActivity] that each row's fill-in
     * intent completes (supplying the target PWA path). Must be **mutable** so the
     * collection can merge the per-row fill-in; `reqCode` is unique per widget id.
     */
    fun rowTapTemplate(ctx: Context, reqCode: Int): PendingIntent {
        val i = Intent(ctx, PwaTrampolineActivity::class.java)
            .addFlags(ActionDialog.TASK_FLAGS)
        val mutable =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(
            ctx, 300_000 + reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }
}
