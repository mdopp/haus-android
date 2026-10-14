package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.haus.R
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.data.isSensitiveDevice
import java.util.Locale

/**
 * Size-adaptive [RemoteViews] for one device widget. Icon + state + bar are
 * tinted by the device-type accent when active; the medium size adds real
 * controls — brightness ± for lights, up/stop/down for covers (widgets can't
 * host a drag slider, so stepped buttons are the interactive model).
 */
object WidgetRender {
    private const val OFF = 0xFF9E9E9E.toInt()
    private const val COVER_ACCENT = 0xFF7E9CFF.toInt()
    private const val LIGHT_ACCENT = 0xFFFFC107.toInt()

    // Lock state colours (#84). `locked` is the only calm/green one; `unknown`
    // deliberately gets the neutral grey so a missing MQTT retain message can
    // never be mistaken for a secured door.
    private const val LOCK_SECURED = 0xFF66BB6A.toInt()   // abgeschlossen
    private const val LOCK_UNSECURED = 0xFFFFA726.toInt() // aufgeschlossen
    private const val LOCK_LATCH = 0xFFFF7043.toInt()     // entriegelt (Falle offen)
    private const val LOCK_MOVING = 0xFF7E9CFF.toInt()    // schließt ab/auf
    private const val LOCK_JAMMED = 0xFFEF5350.toInt()    // klemmt

    /**
     * The tint of a tile that has **no state to show** (#120) — nothing was ever
     * fetched, or a lock's reading has aged past the point where it may still
     * assert itself. Dimmer than [OFF] and deliberately not equal to it: [OFF] is
     * a *measured* "aus", this one is the absence of a measurement, and a tile
     * that paints the second like the first tells the user something nobody
     * checked. It is also what keeps a stale lock off [LOCK_SECURED]'s calm
     * green (#84/#111).
     */
    private const val UNKNOWN = 0xFF6B7280.toInt()

    /** The widget size tiers. Selected by [sizeTier] from the host's min size. */
    enum class Tier { TINY, SMALL, WIDE, MEDIUM }

    /**
     * Load outcome for the state placeholder (#58): while a card is still `null`
     * the state field must say *why*, not a cryptic "…". [LOADING] = first fetch in
     * flight; [FAILED] = the fetch (with retries) gave nothing → hint to refresh;
     * [UNCONFIGURED] = no bound entity (fresh/orphaned after reinstall) → hint to
     * set it up; [LOADED] = a card is present, real state shown.
     */
    enum class Load { LOADING, LOADED, FAILED, UNCONFIGURED }

    /**
     * Pick the layout tier from the host-reported min size (dp). A tall-enough
     * box gets the stacked [MEDIUM] controls; an otherwise wide-but-flat box
     * (e.g. 4×1) still gets an inline control row via [WIDE]; a mid box falls
     * back to [SMALL] (name + state only); the smallest single cell (1×1) gets
     * [TINY] — name + one toggle + a docked state bar (#31). Pure so it can be
     * JVM-tested.
     */
    fun sizeTier(minW: Int, minH: Int): Tier = when {
        minW >= 180 && minH >= 110 -> Tier.MEDIUM
        minW >= 170 -> Tier.WIDE   // ~3 cells wide (flat) already fits inline controls
        minW < 110 && minH < 110 -> Tier.TINY
        else -> Tier.SMALL
    }

    /**
     * Does a **body/central-area tap** toggle the device on/off for this domain
     * (#33)? Lights and switches are simple on↔off actuators, so the big obvious
     * area should switch them directly — Haus's pages moves to the name/header tap.
     * Covers (no simple toggle) and sensors keep the body tap = open-page. Pure so
     * it's JVM-testable.
     */
    fun togglesOnBodyTap(domain: String): Boolean = domain == "light" || domain == "switch"

    /**
     * @param onBodyTap fired by a tap on the widget body/header — opens Haus's page
     *   (#27), NOT a control action. The control buttons keep their own actions.
     *   When the widget is unconfigured this is instead the picker intent.
     */
    fun build(
        ctx: Context,
        appWidgetId: Int,
        card: Card?,
        fallbackName: String,
        domain: String,
        onBodyTap: PendingIntent,
        load: Load = Load.LOADED,
    ): RemoteViews {
        val tier = tierFor(ctx, appWidgetId)
        val dom = card?.domain?.ifBlank { null } ?: domain
        val on = card?.isOn == true
        // What this tile has to say about its connection (#111) — null while it
        // is fresh, or while there is nothing to date.
        val stale = staleMark(ctx, appWidgetId, card)

        // TINY (1×1, #31) has its own shape: name instead of icon, one primary
        // toggle, and the state docked as a bottom bar. Built separately — and
        // without the confirm badge (#95), which does not fit a single cell.
        if (tier == Tier.TINY) {
            return buildTiny(ctx, appWidgetId, card, fallbackName, dom, on, onBodyTap, load, stale)
        }

        val sensitive = isSensitive(ctx, appWidgetId, card, dom)

        // WIDE and SMALL share the same wide layout — SMALL just hides the control
        // row below, so a narrow card looks identical to the wide one minus buttons.
        val layout = when (tier) {
            Tier.MEDIUM -> R.layout.widget_device_medium
            else -> R.layout.widget_device_wide
        }
        val v = RemoteViews(ctx.packageName, layout)

        // A stale tile keeps its value, its icon **and its colour** (#120) — the
        // mark above it is what says "as of a while ago"; only a lock gives its
        // colour up, because that one asserts security (#84).
        val accent = accentFor(dom, card, on, stale != null)

        v.setImageViewResource(R.id.w_icon, iconFor(dom, card))
        v.setInt(R.id.w_icon, "setColorFilter", accent)
        v.setTextViewText(R.id.w_name, (card?.name ?: fallbackName).ifBlank { fallbackName.ifBlank { "—" } })
        v.setTextViewText(R.id.w_state, stateLabel(card, load, stale != null, dom))
        v.setTextColor(R.id.w_state, accent)
        markStale(v, stale?.line)

        // Lock badge (#38): sensitive devices (garage/door/gate) need a confirm.
        v.setViewVisibility(R.id.w_lock, if (showsLockBadge(tier, sensitive)) View.VISIBLE else View.GONE)

        val level = card?.level
        if (level != null) {
            v.setViewVisibility(R.id.w_bar, View.VISIBLE)
            v.setProgressBar(R.id.w_bar, 100, level, false)
        } else {
            v.setViewVisibility(R.id.w_bar, View.GONE)
        }

        // Refresh icon (#26): re-fetch this widget's state headlessly. Present on
        // all three tiers; the click is a discreet, no-flash broadcast.
        v.setOnClickPendingIntent(
            R.id.w_refresh, op(ctx, appWidgetId, 7, WidgetActionReceiver.OP_REFRESH),
        )

        // Tap model per domain (#33): for lights/switches the big central area
        // toggles on/off and the *name* opens Haus's page; for covers/sensors the body
        // opens Haus's page (#27) and the per-domain control buttons do the rest.
        val toggles = togglesOnBodyTap(dom)
        val bodyToggle = op(ctx, appWidgetId, 9, WidgetActionReceiver.OP_TOGGLE)
        // A sensitive cover (garage/door/gate) confirms IN-APP right away instead of
        // opening the page: the body tap toggles open/close via the confirm dialog (#38).
        val entityId = card?.entityId ?: WidgetStore.entityId(ctx, appWidgetId)
        val gatedBodyTap = when {
            // A lock never acts blind (#84): the tap runs the toggle op, which reads
            // the live state, picks the bolt direction and hits the server's 403
            // confirm gate — so a mistap costs a dialog, not the front door.
            dom == "lock" -> bodyToggle
            sensitive && dom == "cover" && entityId != null ->
                confirmPending(ctx, appWidgetId, 1, entityId, if (on) "cover.close_cover" else "cover.open_cover")
            else -> onBodyTap
        }
        if (tier == Tier.MEDIUM || tier == Tier.WIDE) {
            if (toggles) {
                // Central icon+state area toggles; name opens Haus's page.
                v.setOnClickPendingIntent(R.id.w_icon, bodyToggle)
                v.setOnClickPendingIntent(R.id.w_state, bodyToggle)
                v.setOnClickPendingIntent(R.id.w_name, onBodyTap)
            } else {
                v.setOnClickPendingIntent(R.id.w_header, gatedBodyTap)
            }
            wireControls(ctx, v, appWidgetId, dom, sensitive, entityId, card)
        } else {
            // SMALL: same wide layout, control row hidden → identical look, no buttons.
            v.setViewVisibility(R.id.w_light_controls, View.GONE)
            v.setViewVisibility(R.id.w_cover_controls, View.GONE)
            v.setViewVisibility(R.id.w_lock_controls, View.GONE)
            v.setViewVisibility(R.id.w_switch_controls, View.GONE)
            if (toggles) {
                // Small tier: whole card toggles, name opens Haus's page.
                v.setOnClickPendingIntent(R.id.w_root, bodyToggle)
                v.setOnClickPendingIntent(R.id.w_name, onBodyTap)
            } else {
                v.setOnClickPendingIntent(R.id.w_root, gatedBodyTap)
            }
        }
        return v
    }

    /**
     * The 1×1 (single-cell) device widget (#31 → #55 → #57): the **name** is kept
     * (top, single line, ellipsized), the **toggle is a tappable state-tinted domain
     * icon** with no button chrome (tap = the domain's primary action), and the
     * **state is a full-width bottom bar** (position / brightness). Tapping the name
     * opens Haus's page; tapping the icon toggles — unless nothing is bound yet, when
     * the whole tile is one "einrichten" button (#94).
     */
    private fun buildTiny(
        ctx: Context,
        appWidgetId: Int,
        card: Card?,
        fallbackName: String,
        dom: String,
        on: Boolean,
        onBodyTap: PendingIntent,
        load: Load,
        stale: Staleness.Mark? = null,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_device_tiny)

        if (tinyTap(load) == TinyTap.SETUP) return tinySetup(v, onBodyTap, load)

        val accent = accentFor(dom, card, on, stale != null)

        // Name kept (#57); tapping it opens Haus's page (#27).
        v.setTextViewText(R.id.w_name, (card?.name ?: fallbackName).ifBlank { fallbackName.ifBlank { "—" } })
        v.setOnClickPendingIntent(R.id.w_name, onBodyTap)
        // One cell has room for one line (#111) — the age, or the reason once a
        // tap has just failed; the icon's dropped tint carries the rest.
        markStale(v, stale?.compact)

        // Toggle = a tappable, state-tinted domain icon (no button chrome, #57).
        // The icon shows the domain (lamp/cover/…), its tint says on/off, and the
        // tap runs the domain's primary action (toggle / open↔close by state).
        v.setImageViewResource(R.id.w_tiny_toggle, iconFor(dom, card))
        v.setInt(R.id.w_tiny_toggle, "setColorFilter", accent)
        v.setOnClickPendingIntent(R.id.w_tiny_toggle, op(ctx, appWidgetId, 8, tinyToggleOp(dom, card)))

        // State docked as a bottom bar (position / brightness).
        val level = card?.level
        if (level != null) {
            v.setViewVisibility(R.id.w_bar, View.VISIBLE)
            v.setProgressBar(R.id.w_bar, 100, level, false)
        } else {
            v.setViewVisibility(R.id.w_bar, View.GONE)
        }
        return v
    }

    /**
     * The empty 1×1 tile (#94): nothing is bound yet, so the tile says so and
     * behaves as a single button. [onBodyTap] is the picker intent in this state
     * (see [build]).
     *
     * #104 — it must go on **child** views. It used to sit on `w_root`, the
     * RemoteViews *root*, with the name and the toggle icon disarmed by a `null`
     * intent; the launcher hosts that root inside a container of its own and does
     * not reliably deliver a click there, so on the device the tile showed
     * "einrichten" and a plus and a tap did nothing at all. Every visible child is
     * armed with the picker instead — `w_tiny_body` fills the tile so the gaps
     * between the rows respond too, and the name and the icon carry it themselves
     * so a launcher that lets a child consume the touch still opens the picker
     * (a `null` intent is not a reliable "pass it upward" below API 30 either).
     * The state bar stays hidden: there is no state.
     */
    private fun tinySetup(v: RemoteViews, onBodyTap: PendingIntent, load: Load): RemoteViews {
        v.setTextViewText(R.id.w_name, stateLabel(null, load))
        v.setImageViewResource(R.id.w_tiny_toggle, R.drawable.ic_plus)
        v.setInt(R.id.w_tiny_toggle, "setColorFilter", OFF)
        v.setViewVisibility(R.id.w_bar, View.GONE)
        markStale(v, null) // nothing bound → nothing to be stale about (#111)
        for (id in TINY_SETUP_TARGETS) v.setOnClickPendingIntent(id, onBodyTap)
        return v
    }

    /**
     * Every view the picker tap is armed on while the 1×1 tile is empty (#104) —
     * the full-bleed body plus each visible child inside it. Public so the guard
     * test can enumerate it instead of re-listing ids of its own.
     */
    val TINY_SETUP_TARGETS = listOf(R.id.w_tiny_body, R.id.w_name, R.id.w_tiny_toggle)

    /**
     * Where a tap on the 1×1 tile goes, decided by the load state alone (#94).
     * [TinyTap.SETUP] = nothing bound (fresh widget, or orphaned after a
     * reinstall) → the whole tile opens the picker and no sub-region does anything
     * else; [TinyTap.BOUND] = an entity is behind the tile (its state may still be
     * loading or may have failed to load, which changes what is *shown*, not what a
     * tap *does*) → name opens Haus's page, icon runs the domain action. Pure →
     * JVM-testable.
     */
    fun tinyTap(load: Load): TinyTap = when (load) {
        Load.UNCONFIGURED -> TinyTap.SETUP
        Load.LOADING, Load.FAILED, Load.LOADED -> TinyTap.BOUND
    }

    /** The two tap wirings of the 1×1 tile (#94). See [tinyTap]. */
    enum class TinyTap { SETUP, BOUND }

    /**
     * Does this tier carry the confirm badge (#38)? Everything but [Tier.TINY]:
     * on a single cell the badge cost about a third of the name width, and since
     * #92 a tap there opens the chooser anyway, so it announced what the very next
     * touch makes plain (#95). Presentation only — `isSensitiveDevice` and the
     * server's 403 `sensitive_action` gate are untouched. Pure → JVM-testable.
     */
    fun showsLockBadge(tier: Tier, sensitive: Boolean): Boolean = sensitive && tier != Tier.TINY

    /**
     * The tiny-tier toggle-icon's primary action (#57): the tappable icon runs this
     * op. Cover toggles open/close by current state; light/switch toggle on↔off;
     * anything else re-fetches. Pure → JVM-testable.
     *
     * A **lock** is the exception (#92): on 1×1 there is hardly any state to read
     * before tapping, so the tap opens the chooser
     * ([WidgetActionReceiver.OP_LOCK_CHOOSE]) instead of flipping the bolt blind.
     * Every other domain — and every larger tier — keeps the toggle it had.
     */
    fun tinyToggleOp(domain: String, card: Card?): String = when (domain) {
        "cover" -> if (card?.isOn == true) WidgetActionReceiver.OP_COVER_CLOSE
                   else WidgetActionReceiver.OP_COVER_OPEN
        // The chooser is the confirmation: no tap on this tile acts by itself.
        "lock" -> WidgetActionReceiver.OP_LOCK_CHOOSE
        "light", "switch" -> WidgetActionReceiver.OP_TOGGLE
        else -> WidgetActionReceiver.OP_REFRESH
    }

    /**
     * Does this widget's device need a confirm (#38)? Prefer the live [card]'s
     * class; when there's no card yet (cache/loading render) fall back to the
     * bound widget metadata (domain + deviceClass) persisted at config time.
     */
    private fun isSensitive(ctx: Context, appWidgetId: Int, card: Card?, dom: String): Boolean {
        if (card != null) return card.isSensitive
        return isSensitiveDevice(dom, WidgetStore.deviceClass(ctx, appWidgetId))
    }

    /** Resolve the layout tier for [appWidgetId] from the host's reported min size. */
    fun tierFor(ctx: Context, appWidgetId: Int): Tier {
        val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return sizeTier(minW, minH)
    }

    /**
     * Show exactly one control row for the domain (and hide the others) so the
     * card's bottom band is filled without leaving an empty control hole for
     * non-controllable domains. The switch row (medium/wide only) is optional —
     * older/small layouts omit it, so we guard with [hasSwitchRow].
     */
    private fun wireControls(ctx: Context, v: RemoteViews, appWidgetId: Int, domain: String, sensitive: Boolean, entityId: String?, card: Card?) {
        // Default: everything hidden; enable just the row we need below.
        v.setViewVisibility(R.id.w_light_controls, View.GONE)
        v.setViewVisibility(R.id.w_cover_controls, View.GONE)
        v.setViewVisibility(R.id.w_lock_controls, View.GONE)
        setSwitchRowVisibility(v, View.GONE)
        when (domain) {
            "light" -> {
                v.setViewVisibility(R.id.w_light_controls, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.w_bright_down, op(ctx, appWidgetId, 2, WidgetActionReceiver.OP_BRIGHT_DOWN))
                v.setOnClickPendingIntent(R.id.w_bright_up, op(ctx, appWidgetId, 1, WidgetActionReceiver.OP_BRIGHT_UP))
                wireColorSwatch(ctx, v, appWidgetId, entityId, card)
            }
            "cover" -> {
                v.setViewVisibility(R.id.w_cover_controls, View.VISIBLE)
                // Grey out the impossible direction at the stop (#55): fully open ⇒ ▲
                // is muted + inert; fully closed ⇒ ▼ is muted + inert. Stop stays live.
                val pos = card?.position
                val upEnabled = coverUpEnabled(pos)
                val downEnabled = coverDownEnabled(pos)
                val upIntent = if (sensitive && entityId != null)
                    confirmPending(ctx, appWidgetId, 2, entityId, "cover.open_cover")
                else op(ctx, appWidgetId, 3, WidgetActionReceiver.OP_COVER_OPEN)
                val downIntent = if (sensitive && entityId != null)
                    confirmPending(ctx, appWidgetId, 3, entityId, "cover.close_cover")
                else op(ctx, appWidgetId, 5, WidgetActionReceiver.OP_COVER_CLOSE)
                wireCoverButton(v, R.id.w_cover_up, upEnabled, upIntent, COVER_ACCENT)
                v.setOnClickPendingIntent(R.id.w_cover_stop, op(ctx, appWidgetId, 4, WidgetActionReceiver.OP_COVER_STOP))
                wireCoverButton(v, R.id.w_cover_down, downEnabled, downIntent, COVER_ACCENT)
            }
            "lock" -> {
                v.setViewVisibility(R.id.w_lock_controls, View.VISIBLE)
                // Both directions named, both server-gated (every lock.* service is
                // sensitive → 403 → confirm dialog). `lock.open` is missing on
                // purpose: it pulls the latch, i.e. it opens the door (#84).
                v.setOnClickPendingIntent(R.id.w_lock_lock, op(ctx, appWidgetId, 10, WidgetActionReceiver.OP_LOCK))
                v.setOnClickPendingIntent(R.id.w_lock_unlock, op(ctx, appWidgetId, 11, WidgetActionReceiver.OP_UNLOCK))
            }
            "switch" -> {
                setSwitchRowVisibility(v, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.w_switch_toggle, op(ctx, appWidgetId, 6, WidgetActionReceiver.OP_TOGGLE))
            }
            // sensors / other domains: no control row — the state + bar fill the card.
        }
    }

    /**
     * The colour swatch on a light row (#87). Shown only for a colour-capable lamp
     * — a plain white bulb keeps exactly the −/+ row it had. The button's face
     * carries the lamp's current `rgb_color`, and the tap hands off to
     * [WidgetActionActivity]: `RemoteViews` has no colour-picker view, so the
     * palette lives in a dialog that POSTs `light.turn_on {rgb_color}` — the same
     * call the web card makes.
     */
    private fun wireColorSwatch(ctx: Context, v: RemoteViews, appWidgetId: Int, entityId: String?, card: Card?) {
        if (card == null || entityId == null || !card.isColorCapable) {
            v.setViewVisibility(R.id.w_light_color, View.GONE)
            return
        }
        v.setViewVisibility(R.id.w_light_color, View.VISIBLE)
        v.setTextColor(R.id.w_light_color, card.colorArgb ?: LIGHT_ACCENT)
        v.setOnClickPendingIntent(R.id.w_light_color, colorPending(ctx, appWidgetId, 12, entityId))
    }

    /**
     * Wire one cover direction button (#55): when [enabled] it gets the accent
     * tint + its action; when disabled it is muted ([OFF]) AND its PendingIntent is
     * cleared (setEnabled(false)) so a tap does nothing.
     */
    private fun wireCoverButton(v: RemoteViews, id: Int, enabled: Boolean, intent: PendingIntent, accent: Int) {
        v.setBoolean(id, "setEnabled", enabled)
        v.setTextColor(id, if (enabled) accent else OFF)
        v.setOnClickPendingIntent(id, if (enabled) intent else null)
    }

    /**
     * Can a cover still open (▲ active)? False only when fully open
     * (`position >= 99`); unknown position (null) stays enabled. Pure → JVM-testable.
     */
    fun coverUpEnabled(position: Int?): Boolean = position == null || position < 99

    /**
     * Can a cover still close (▼ active)? False only when fully closed
     * (`position <= 1`); unknown position (null) stays enabled. Pure → JVM-testable.
     */
    fun coverDownEnabled(position: Int?): Boolean = position == null || position > 1

    /** Toggle the switch row where the layout has one (medium/wide); no-op otherwise. */
    private fun setSwitchRowVisibility(v: RemoteViews, vis: Int) {
        v.setViewVisibility(R.id.w_switch_controls, vis)
    }

    /**
     * A control broadcast for [appWidgetId]. The request code is
     * `appWidgetId * 100 + code` — 100 slots per widget, widened from 10 when the
     * lock/colour buttons (#84/#87) took the codes past 9; at ×10 code 10 would
     * have been the *next* widget's code 0 and the two taps would have shared one
     * PendingIntent.
     */
    private fun op(ctx: Context, appWidgetId: Int, code: Int, op: String): PendingIntent {
        val i = Intent(ctx, WidgetActionReceiver::class.java)
            .setAction(WidgetActionReceiver.ACTION_TAP)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(WidgetActionReceiver.EXTRA_OP, op)
        return PendingIntent.getBroadcast(
            ctx, appWidgetId * 100 + code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A PendingIntent that opens the [WidgetActionActivity] confirm dialog for a
     * sensitive device (garage/door) — an immediate in-app "Wirklich öffnen?" that
     * runs [service] on confirm, instead of the server-403 round-trip or Haus's pages.
     * Distinct request-code base so it never collides with the [op] broadcasts.
     */
    private fun confirmPending(ctx: Context, appWidgetId: Int, code: Int, entityId: String, service: String): PendingIntent {
        val i = Intent(ctx, WidgetActionActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entityId)
            .putExtra(WidgetActionReceiver.EXTRA_SERVICE, service)
            // Its own task, never the app's (#114).
            .addFlags(ActionDialog.TASK_FLAGS)
        return PendingIntent.getActivity(
            ctx, 500000 + appWidgetId * 100 + code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A PendingIntent that opens [WidgetActionActivity]'s colour palette for a
     * colour-capable light (#87). Own request-code base so it never collides with
     * the [op] broadcasts or the [confirmPending] dialogs.
     */
    private fun colorPending(ctx: Context, appWidgetId: Int, code: Int, entityId: String): PendingIntent {
        val i = Intent(ctx, WidgetActionActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entityId)
            .putExtra(WidgetActionActivity.EXTRA_PICK_COLOR, true)
            .addFlags(ActionDialog.TASK_FLAGS)
        return PendingIntent.getActivity(
            ctx, 700000 + appWidgetId * 100 + code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The tile's type icon. A lock is drawn by its **state** rather than by its
     * domain (#91), so `abgeschlossen` / `aufgeschlossen` / `entriegelt` are told
     * apart by the picture and not only by the word underneath it.
     */
    private fun iconFor(domain: String, card: Card?): Int = DeviceIcons.forState(domain, card?.state)

    /**
     * The tile's accent. A lock is coloured by its **state** rather than by the
     * on/off flag ([lockAccent]) — everything else keeps the domain accent when
     * active and the neutral grey when not.
     *
     * Two rules on top of that, both from #120:
     *
     * - **No card, no colour.** With nothing fetched — a freshly bound tile, or a
     *   failed first load — `on` is `false` for want of data, not because anyone
     *   measured "aus". Painting [OFF] there turns missing data into a claim: the
     *   flight-mode report was a lamp that had been on for hours and rendered
     *   grey. So a missing card is [UNKNOWN] in every domain, not just `light` —
     *   on a lock it would be the most dangerous of the lot. Same rule as
     *   `unknown` never looking *abgeschlossen* (#84).
     * - **Stale keeps its colour.** Age is not a reason to unpaint a value we do
     *   have: *mark, don't replace* (#111) — the mark line above says how old it
     *   is and the yellow still says the lamp was on. The one exception stays the
     *   lock, whose calm green would assert a security state nobody verified;
     *   that one drops to [UNKNOWN] and its wording gains a `?`
     *   ([Staleness.staleValue]).
     *
     * Pure → JVM-testable.
     */
    fun accentFor(domain: String, card: Card?, on: Boolean, stale: Boolean = false): Int = when {
        card == null -> UNKNOWN
        domain == "lock" -> if (stale) UNKNOWN else lockAccent(card.state)
        on -> accentFor(domain)
        else -> OFF
    }

    private fun accentFor(domain: String): Int = when (domain) {
        "light" -> LIGHT_ACCENT
        "cover" -> COVER_ACCENT
        "switch" -> 0xFF66BB6A.toInt()
        "climate" -> 0xFFFF8A65.toInt()
        else -> LIGHT_ACCENT
    }

    /**
     * German state text for a lock (#84). It talks about the **bolt**, never the
     * door: there is no door sensor, so a lock reporting `locked` says
     * *abgeschlossen*, not *zu*. `unknown` (no MQTT retain message yet after a
     * broker or phone restart) is its own word — it must never read as
     * *abgeschlossen*. Pure → JVM-testable.
     */
    fun lockLabel(state: String?): String = when (state?.trim()?.lowercase()) {
        "locked" -> "abgeschlossen"
        "unlocked" -> "aufgeschlossen"
        "open" -> "entriegelt"
        "locking" -> "schließt ab …"
        "unlocking" -> "schließt auf …"
        "opening" -> "entriegelt …"
        "jammed" -> "klemmt"
        "unavailable" -> "nicht erreichbar"
        null, "", "unknown", "none" -> "unbekannt"
        else -> state
    }

    /**
     * Accent colour for a lock state (#84). Only `locked` gets the calm green;
     * `unknown` stays neutral grey, so the two never look alike. Pure →
     * JVM-testable.
     */
    fun lockAccent(state: String?): Int = when (state?.trim()?.lowercase()) {
        "locked" -> LOCK_SECURED
        "unlocked" -> LOCK_UNSECURED
        "open" -> LOCK_LATCH
        "locking", "unlocking", "opening" -> LOCK_MOVING
        "jammed" -> LOCK_JAMMED
        else -> OFF
    }

    /**
     * What this tile has to say about its connection, or `null` when it must not
     * be marked at all (#111).
     *
     * Marked only when there **is** a value to qualify: an empty tile already says
     * "lädt…" / "↻ tippen" / "einrichten", and dating a value we never had would
     * be noise. Two inputs, both about *us* and the server, never about the
     * entity: [WidgetCache.fetchedAt] — when we last heard anything, the passive
     * half — and [WidgetCache.unreachableSince] — when a tap the user made last
     * failed to get through, the active one. Neither is `Card.updatedAtMs`, which
     * is when the entity last changed and says nothing about the connection.
     */
    private fun staleMark(ctx: Context, appWidgetId: Int, card: Card?): Staleness.Mark? {
        if (card == null) return null
        val at = WidgetCache.fetchedAt(ctx, appWidgetId)
        val failed = WidgetCache.unreachableSince(ctx, appWidgetId)
        if (at == null && failed == null) return null
        return Staleness.markFor(at, failed, System.currentTimeMillis())
    }

    /** Show or hide the tile's stale line; [text] `null` = fresh, nothing to say. */
    private fun markStale(v: RemoteViews, text: String?) {
        if (text == null) {
            v.setViewVisibility(R.id.w_stale, View.GONE)
        } else {
            v.setTextViewText(R.id.w_stale, text)
            v.setViewVisibility(R.id.w_stale, View.VISIBLE)
        }
    }

    private fun stateLabel(
        card: Card?,
        load: Load = Load.LOADED,
        stale: Boolean = false,
        domain: String = "",
    ): String {
        if (card == null) return when (load) {
            Load.UNCONFIGURED -> "einrichten"
            Load.FAILED -> "↻ tippen"
            else -> "lädt…"
        }
        val value = valueLabel(card)
        // Mark, don't replace (#111): the last known value stays; only a lock's
        // reading becomes a question, because that one asserts security.
        return if (stale) Staleness.staleValue(value, domain.ifBlank { card.domain }) else value
    }

    private fun valueLabel(card: Card): String {
        return when (card.domain) {
            // On + a brightness %: show just the % (the accent colour already says "on").
            "light" -> if (card.isOn) card.brightnessPct?.let { "$it %" } ?: "an" else "aus"
            "switch" -> if (card.isOn) "an" else "aus"
            "cover" -> card.position?.let { p ->
                when {
                    p >= 99 -> "offen"
                    p <= 1 -> "zu"
                    else -> "$p % offen"
                }
            } ?: if (card.isOn) "offen" else "zu"
            "lock" -> lockLabel(card.state)
            "climate" -> card.temperature?.let { String.format(Locale.GERMANY, "%.1f °", it) } ?: (card.state ?: "?")
            else -> sensorLabel(card)
        }
    }

    private fun sensorLabel(card: Card): String {
        val s = card.state ?: return "—"
        return if (!card.unit.isNullOrBlank()) "$s ${card.unit}" else s
    }
}
