package cloud.dopp.solaris.ui

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import cloud.dopp.solaris.realtime.RealtimeService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisApp
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.PairLinks
import cloud.dopp.solaris.data.PersonaStore
import cloud.dopp.solaris.data.SbApiClient
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.UpdateCheck
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.data.ToolDef
import cloud.dopp.solaris.data.ToolDefs
import cloud.dopp.solaris.widget.AppShortcuts
import cloud.dopp.solaris.widget.PwaLauncher
import cloud.dopp.solaris.widget.ToolLauncherWidgetProvider
import cloud.dopp.solaris.widget.ToolWidgetProvider
import cloud.dopp.solaris.widget.WidgetPin
import cloud.dopp.solaris.widget.WidgetStore
import cloud.dopp.solaris.widget.WidgetTypes
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The app's launcher + onboarding hub (replaces the retired TWA LauncherActivity
 * and the old PairingActivity). Guides the user in a few clear steps:
 *  1. connect to their own Solaris server (URL entry; QR to follow),
 *  2. install the Solaris PWA (opens the server in a Custom Tab),
 *  3. add widgets (one-tap via requestPinAppWidget).
 * It also captures the `haus://pair#token=…` (or legacy
 * `cloud.dopp.solaris://pair#token=…`) redirect from the
 * server's /pair-device page and stores the device token.
 *
 * **This is the screen the app icon opens** (#129). #115 briefly sent the icon
 * straight to the Solaris surface; the cost was that everything only this app
 * has — widgets, Live-Updates, the diagnostics entry, sign-out — had no way *in*
 * any more, only the back gesture out of the surface. "Zu Solaris" is the jump
 * onward, one tap, and every surface has a forward path again. Deep links from
 * widgets and shortcuts are unaffected: they go through [PwaLauncher] straight
 * into the surface and never through this screen.
 */
class OnboardingHomeActivity : AppCompatActivity() {

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScanned(it) }
    }

    // Realtime toggle (#48): after the user grants notifications, actually enable.
    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { _ -> enableRealtime() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_home)
            findViewById<Button>(R.id.qr_scan_btn).setOnClickListener { startScan() }
            findViewById<Button>(R.id.connect_btn).setOnClickListener { onConnect() }
            findViewById<TextView>(R.id.request_access_btn).setOnClickListener { requestAccess() }
            // Quick-add tiles (#37): tap → pin that widget type directly. The tiles
            // are now THE add surface (the standalone add-widget button is gone).
            findViewById<View>(R.id.tile_device).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.DeviceWidgetProvider::class.java)
            }
            // Energy has 3 variants → the tile opens a small chooser so every
            // energy widget type stays reachable.
            findViewById<View>(R.id.tile_energy).setOnClickListener { pinEnergyChoice() }
            findViewById<View>(R.id.tile_active).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.ActiveDevicesWidgetProvider::class.java)
            }
            findViewById<View>(R.id.tile_camera).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.CameraWidgetProvider::class.java)
            }
            findViewById<View>(R.id.tile_voice).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.VoiceWidgetProvider::class.java)
            }
            findViewById<View>(R.id.tile_devices).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.DevicesWidgetProvider::class.java)
            }
            // ServiceBay group (#42/#44) — offer the infra widgets in-app too.
            findViewById<View>(R.id.tile_sb_overview).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.SbOverviewWidgetProvider::class.java)
            }
            findViewById<View>(R.id.tile_sb_services).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.SbServicesWidgetProvider::class.java)
            }
            // Everything past the five household types (#42/#44 ServiceBay, #72
            // tools) lives behind one disclosure so the first screen is short (#126).
            findViewById<View>(R.id.more_widgets_toggle).setOnClickListener { toggleMoreWidgets() }
            findViewById<Button>(R.id.open_btn).setOnClickListener { openSolaris() }
            findViewById<View>(R.id.logout_btn).setOnClickListener { logout() }
            findViewById<Switch>(R.id.realtime_switch)
                .setOnClickListener { onRealtimeToggled((it as Switch).isChecked) }
            findViewById<View>(R.id.realtime_info).setOnClickListener { showRealtimeInfo() }
            findViewById<TextView>(R.id.realtime_interval).setOnClickListener { pickPollInterval() }
            findViewById<TextView>(R.id.home_notices).setOnClickListener {
                startActivity(android.content.Intent(this, NoticesActivity::class.java))
            }
            findViewById<TextView>(R.id.app_version).apply {
                text = getString(R.string.app_version_fmt, appVersionName())
                setOnClickListener {
                    startActivity(android.content.Intent(this@OnboardingHomeActivity, DiagnosticsActivity::class.java))
                }
            }
            applySystemBarInsets()
            handleDeepLink(intent)
            render()
            checkForUpdate()
            // If Live-Updates is on and we're paired, make sure the service runs.
            RealtimeService.ensure(this)
        } catch (e: Throwable) {
            showError("onCreate", e)
        }
        // A crash in a previous process (e.g. the deep-link launch) was written to
        // file — surface it now as a readable, copyable message.
        showLastCrashIfAny()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            handleDeepLink(intent)
            render()
        } catch (e: Throwable) {
            showError("onNewIntent", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            render()
            // Catch-all for the app-icon long-press menu (#97): bind/delete keep it
            // current, this picks up a restore or a widget removed while away.
            AppShortcuts.refreshAsync(applicationContext)
        } catch (e: Throwable) {
            showError("onResume", e)
        }
    }

    /**
     * Start the screen **below** the system bars (#130).
     *
     * `targetSdkVersion 35` makes every window edge-to-edge, and nothing here
     * consumed the insets: the layout's own `paddingTop` was all that stood
     * between the content and the status bar, which is why the status line ended
     * up level with the system clock. It only became visible when #126 removed
     * the brand block, which had been filling that space by accident.
     * `android:statusBarColor` does not help — it is a no-op from API 35.
     *
     * Same shape as the fix on the config screens (#19) and the approvals list:
     * the system-bar insets are added onto the root's own padding, so a cutout,
     * a tall status bar and a gesture-navigation bar are all cleared.
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.home_root)
        val basePadTop = root.paddingTop
        val basePadBottom = root.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                basePadTop + bars.top,
                v.paddingRight,
                basePadBottom + bars.bottom,
            )
            insets
        }
    }

    /**
     * The brand lockup, in two sizes (#130).
     *
     * #126 hid the wordmark once the install was paired, on the reasoning that a
     * paired app no longer has to introduce itself. The operator disagreed after
     * living with it — the wordmark in its glow is what the screen looks like —
     * so it is back in **both** states. What #126 actually won is kept: paired,
     * the lockup switches to the compact tokens and the first-run claim goes, so
     * the essentials still fit without scrolling. The height that ticket freed
     * came from the tile grid, not from here.
     *
     * The compact size is **one scale factor**, not a second set of hand-picked
     * values (#132). Halo and wordmark used to be shrunk independently (190→112dp
     * against 40→26sp), so the mark filled the small circle more than the large
     * one and looked crammed into it. Everything inside the lockup now follows
     * the halo's own factor, which is what keeps the two presentations reading
     * as the same mark at two sizes.
     */
    private fun renderBrand(connected: Boolean) {
        findViewById<View>(R.id.brand_block).visibility = View.VISIBLE
        findViewById<View>(R.id.brand_claim).visibility =
            if (connected) View.GONE else View.VISIBLE

        val glow = if (connected) R.dimen.hero_glow_size_compact else R.dimen.hero_glow_size
        val glowPx = resources.getDimensionPixelSize(glow)
        findViewById<View>(R.id.brand_glow).apply {
            layoutParams = layoutParams.also { it.width = glowPx; it.height = glowPx }
        }
        val scale = glowPx / resources.getDimension(R.dimen.hero_glow_size)

        val heroPx = resources.getDimension(R.dimen.text_hero) * scale
        listOf(R.id.brand_word_start, R.id.brand_word_end).forEach {
            findViewById<TextView>(it).setTextSize(TypedValue.COMPLEX_UNIT_PX, heroPx)
        }

        val figurePx = resources.getDimension(R.dimen.brand_figure_height) * scale
        findViewById<View>(R.id.brand_figure).apply {
            layoutParams = layoutParams.also { it.height = Math.round(figurePx) }
        }

        // The house persona's name replaces the wordmark (laib#130); without one
        // the Solaris lockup stays exactly as it was.
        val persona = PersonaStore.get(this)
        findViewById<TextView>(R.id.brand_word_start).text = persona?.display ?: "Solar"
        val mark = if (persona == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.brand_figure).visibility = mark
        findViewById<View>(R.id.brand_word_end).visibility = mark
        persona?.accent?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }?.let {
            findViewById<View>(R.id.brand_rule).setBackgroundColor(it)
        }

        val top = if (connected) R.dimen.brand_lockup_top_compact else R.dimen.brand_lockup_top
        setTopMargin(R.id.brand_lockup, resources.getDimensionPixelSize(top))
        // With the claim gone the rule carries the gap to the status line itself.
        val rule = if (connected) R.dimen.brand_rule_bottom_compact else R.dimen.brand_rule_bottom
        setBottomMargin(R.id.brand_rule, resources.getDimensionPixelSize(rule))
    }

    private fun setTopMargin(id: Int, px: Int) {
        val v = findViewById<View>(id)
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = px
            v.layoutParams = it
        }
    }

    private fun setBottomMargin(id: Int, px: Int) {
        val v = findViewById<View>(id)
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = px
            v.layoutParams = it
        }
    }

    private fun render() {
        val connected = ServerStore.isConfigured(this) && TokenStore.isPaired(this)
        findViewById<View>(R.id.connect_section).visibility = if (connected) View.GONE else View.VISIBLE
        findViewById<View>(R.id.connected_section).visibility = if (connected) View.VISIBLE else View.GONE
        // Sign out shares the footer row with the version line (#132), but the row
        // itself belongs to both states — an unpaired install has nothing to sign
        // out of, and the version line then owns the row and re-centres.
        findViewById<View>(R.id.logout_btn).visibility = if (connected) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.app_version).gravity =
            if (connected) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        renderBrand(connected)
        renderMoreWidgets()

        val urlField = findViewById<EditText>(R.id.server_url)
        // Only prefill an already-configured server; a fresh install stays blank so
        // the generic layout hint ("https://dein-server") shows — multi-tenant, no
        // hardcoded chat.dopp.cloud (#52).
        if (urlField.text.isNullOrBlank()) {
            ServerStore.baseUrl(this)?.let { urlField.setText(it) }
        }
        findViewById<TextView>(R.id.connect_hint).text =
            if (ServerStore.isConfigured(this) && !TokenStore.isPaired(this)) {
                getString(R.string.home_reconnect, ServerStore.host(this) ?: "")
            } else {
                getString(R.string.home_connect_hint)
            }
        // Ampel: green dot when connected, muted grey otherwise (#37) — set for
        // both states, outside the connected branch.
        findViewById<View>(R.id.status_dot).background?.setTint(
            getColor(if (connected) R.color.energy_supply else R.color.solaris_text_faint),
        )
        if (connected) {
            findViewById<TextView>(R.id.status).text =
                getString(R.string.home_connected, ServerStore.host(this) ?: "")
            // Reflect the persisted Live-Updates state (#48) — a plain click listener
            // is used elsewhere so this programmatic set doesn't re-fire the toggle.
            findViewById<Switch>(R.id.realtime_switch).isChecked =
                ServerStore.realtimeEnabled(this)
            renderPollInterval()
        }

        // #11: the install-PWA hint card is dismissible — once dismissed it stays
        // hidden on future launches (we can't detect an installed WebAPK).

        renderToolSection()
        if (connected) refreshPersona(connected)
    }

    /** Ask Haus who the house is (laib#130); redraw the lockup when that changed. */
    private fun refreshPersona(connected: Boolean) {
        kotlin.concurrent.thread {
            val fresh = try {
                SbApiClient(applicationContext).getPersona()
            } catch (e: Exception) {
                null
            } ?: return@thread
            if (fresh == PersonaStore.get(applicationContext)) return@thread
            PersonaStore.save(applicationContext, fresh)
            runOnUiThread { if (!isFinishing) renderBrand(connected) }
        }
    }

    /**
     * The **catalog-driven** part of the widget picker (#72). Unlike the tiles
     * above — a static Kotlin table — this section is built from the server's
     * `.tool` catalog (`/napi/defs/tool`), so a newly installed `.tool` plugin
     * shows up as an addable widget **without an app rebuild**.
     *
     * Draws the last-seen catalog first (instant, and the fallback when the fetch
     * fails), then refreshes from the server. Only tools that can fill a list card
     * are offered ([ToolDef.isListable]).
     */
    private fun renderToolSection() {
        val group = findViewById<ViewGroup>(R.id.tools_group)
        showTools(group, listableTools(WidgetStore.toolCatalogJson(this)))
        if (toolsLoading || !(ServerStore.isConfigured(this) && TokenStore.isPaired(this))) return
        toolsLoading = true
        kotlin.concurrent.thread {
            val body = try {
                ApiClient(applicationContext).toolCatalogBody()
            } catch (e: Exception) {
                null
            }
            if (body != null) WidgetStore.setToolCatalogJson(applicationContext, body)
            runOnUiThread {
                toolsLoading = false
                // No catalog (offline / not deployed) → keep the cached section.
                if (body != null && !isFinishing) showTools(group, listableTools(body))
            }
        }
    }

    private fun listableTools(catalogJson: String?): List<ToolDef> =
        ToolDefs.parseCatalog(catalogJson).filter { it.isListable }

    /** Rebuild the tool rows; the whole group hides when there is nothing to offer. */
    private fun showTools(group: ViewGroup, defs: List<ToolDef>) {
        group.removeAllViews()
        val vis = if (defs.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tools_group_title).visibility = vis
        findViewById<View>(R.id.tools_group_hint).visibility = vis
        group.visibility = vis
        defs.forEach { def ->
            val row = layoutInflater.inflate(R.layout.item_widget_type, group, false)
            row.findViewById<ImageView>(R.id.type_icon).setImageResource(R.drawable.ic_services)
            row.findViewById<TextView>(R.id.type_label).text = def.label
            row.setOnClickListener { pinTool(def) }
            group.addView(row)
        }
        if (defs.isNotEmpty()) addLauncherRow(group)
    }

    /**
     * The 1×1 launcher tile (#71) as one entry, not one row per tool × mode: the
     * tile's own config screen is the picker (it offers a "Neu" entry only where
     * the def declares a `tool-compose-path`), so this section stays short.
     */
    private fun addLauncherRow(group: ViewGroup) {
        val row = layoutInflater.inflate(R.layout.item_widget_type, group, false)
        row.findViewById<ImageView>(R.id.type_icon).setImageResource(R.drawable.ic_plus)
        row.findViewById<TextView>(R.id.type_label).setText(R.string.tool_launch_label)
        row.setOnClickListener {
            val mgr = pinManagerOrToast() ?: return@setOnClickListener
            WidgetPin.request(this, mgr, ToolLauncherWidgetProvider::class.java)
        }
        group.addView(row)
    }

    /**
     * Pin the generic tool widget bound to [def]. The picked id rides the pin's
     * `successCallback`, which binds the instance the host assigns (#105); parking
     * it for [ToolWidgetConfigActivity] stays as the fallback. See [WidgetPin].
     */
    private fun pinTool(def: ToolDef) {
        val mgr = pinManagerOrToast() ?: return
        WidgetPin.request(this, mgr, ToolWidgetProvider::class.java, def.id, def.label)
    }

    /** Guards against a second catalog fetch while one is in flight (onResume). */
    @Volatile
    private var toolsLoading = false

    /**
     * Whether the "Weitere Widgets" section is unfolded (#126). Session state on
     * purpose: the point of the disclosure is that the screen *starts* short, and
     * a persisted "always open" would quietly undo that on the next launch. Every
     * widget type stays one tap away either way.
     */
    private var moreWidgetsShown = false

    private fun toggleMoreWidgets() {
        moreWidgetsShown = !moreWidgetsShown
        renderMoreWidgets()
    }

    private fun renderMoreWidgets() {
        findViewById<View>(R.id.more_widgets).visibility =
            if (moreWidgetsShown) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.more_widgets_toggle).setText(
            if (moreWidgetsShown) R.string.home_less_widgets else R.string.home_more_widgets,
        )
    }

    /**
     * The long half of the Live-Updates text (#126): why a permanent notification
     * exists, what it costs, and what the idle interval does. The card keeps one
     * line saying what the feature is *for*; this is for whoever asks why.
     */
    private fun showRealtimeInfo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.home_realtime_title)
            .setMessage(R.string.home_realtime_info)
            .setPositiveButton(R.string.home_info_ok, null)
            .show()
    }

    /** Persist the dismiss flag (#11) and hide the install-PWA hint card. */

    private fun onConnect() {
        val url = findViewById<EditText>(R.id.server_url).text.toString().trim()
        if (url.isBlank()) {
            toast(getString(R.string.home_enter_url)); return
        }
        ServerStore.setBaseUrl(this, url)
        openPairing()
    }

    /**
     * Request access (#50): a user without an account opens their server's
     * request-access page ([SolarisConfig.PORTAL_PATH]) in a Custom Tab. This is
     * a pre-auth public web flow (ADR 0010) — no token, no /napi. Prefer the
     * URL just typed into the field, fall back to a previously stored server;
     * if neither is present, nudge the user to enter the server address first.
     */
    private fun requestAccess() {
        val typed = findViewById<EditText>(R.id.server_url).text.toString().trim()
        val base = typed.ifBlank { ServerStore.baseUrl(this) ?: "" }.trimEnd('/')
        if (base.isBlank()) {
            toast(getString(R.string.home_enter_url)); return
        }
        // The request-access CTA lives on the ServiceBay portal (apex domain),
        // not under the Solaris host (#50). Open it logged-out to see the form.
        openUrl(Uri.parse(SolarisConfig.portalUrl(base)))
    }

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    /**
     * Parse `haus://setup?server=…&token=…` (or its legacy
     * `cloud.dopp.solaris://` twin, see [PairLinks]), or a plain https URL.
     */
    private fun handleScanned(text: String) {
        val setup = PairLinks.parseSetup(text)
        if (setup != null) {
            ServerStore.setBaseUrl(this, setup.server)
            if (setup.token != null) {
                TokenStore.save(this, setup.token)
                toast(getString(R.string.pairing_success))
                render()
            } else {
                openPairing()
            }
            return
        }
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            ServerStore.setBaseUrl(this, text)
            openPairing()
            return
        }
        toast(getString(R.string.qr_invalid))
    }

    private fun openPairing() {
        val base = ServerStore.baseUrl(this) ?: return
        openUrl(Uri.parse(base + SolarisConfig.PAIR_PATH))
    }

    /** Open in a Custom Tab, falling back to a plain browser intent, then a toast. */
    private fun openUrl(uri: Uri) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: Exception) {
                toast(getString(R.string.home_no_browser))
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        // haus://pair#token=… or the legacy cloud.dopp.solaris://pair#token=… (#174).
        // PairLinks parses fragment and query by hand and never throws.
        if (!PairLinks.isPairLink(data)) return
        val token = PairLinks.pairToken(data)
        if (token != null) {
            try {
                TokenStore.save(this, token)
                toast(getString(R.string.pairing_success))
            } catch (e: Exception) {
                toast(getString(R.string.pairing_failed))
            }
        } else {
            toast(getString(R.string.pairing_failed))
        }
    }

    /**
     * The energy tile (#37) covers three variants — Jetzt / Verlauf / Gesamt —
     * so tapping it opens a small styled chooser (type icon + German name), just
     * like the retired full widget-type picker, then pins the picked provider via
     * requestPinAppWidget. The other four tiles pin their single provider directly.
     */
    private fun pinEnergyChoice() {
        val mgr = pinManagerOrToast() ?: return
        val entries = WidgetTypes.ALL.filter {
            it.provider == cloud.dopp.solaris.widget.EnergyWidgetProvider::class.java ||
                it.provider == cloud.dopp.solaris.widget.EnergyStatsWidgetProvider::class.java ||
                it.provider == cloud.dopp.solaris.widget.EnergyTotalsWidgetProvider::class.java
        }
        val adapter = object : ArrayAdapter<WidgetTypes.Entry>(this, 0, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_widget_type, parent, false)
                val entry = entries[position]
                row.findViewById<ImageView>(R.id.type_icon).setImageResource(entry.icon)
                row.findViewById<TextView>(R.id.type_label).setText(entry.label)
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.home_pick_widget_title)
            .setAdapter(adapter) { _, which ->
                WidgetPin.request(this, mgr, entries[which].provider)
            }
            .setNegativeButton(R.string.home_pick_widget_cancel, null)
            .show()
    }

    /**
     * Return the [AppWidgetManager] iff pinning is supported (API 26+ & launcher
     * supports it), else show the manual-add toast and return null. Shared by the
     * quick-add tiles [pinTile] and the energy variant chooser [pinEnergyChoice] (#37).
     */
    private fun pinManagerOrToast(): AppWidgetManager? {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            mgr == null || !mgr.isRequestPinAppWidgetSupported
        ) {
            toast(getString(R.string.home_add_widget_manual))
            return null
        }
        return mgr
    }

    /** Quick-add (#37): pin one specific widget type — callback-completed (#105). */
    private fun pinTile(provider: Class<*>) {
        val mgr = pinManagerOrToast() ?: return
        WidgetPin.request(this, mgr, provider)
    }

    /**
     * Step 2 of onboarding (#11): nudge the user to install the Solaris PWA — that's
     * where chat & push notifications live. Opens the server root in the browser so
     * the site's `beforeinstallprompt` / "add to home screen" affordance appears.
     */
    /**
     * The install-PWA hint (#11) stays a **browser tab on purpose**, even now that
     * the app has its own surface (#115): "Zum Startbildschirm hinzufügen" only
     * exists in the browser, and the standalone PWA is still the channel that
     * delivers timer/reminder Web Push until the app takes those over natively
     * (#116). Nothing here tells the user to remove it yet.
     */

    /**
     * "Zu Solaris" (#127) — and since #129 **the** way into the surface, not just
     * the fastest one.
     *
     * #115 made the app icon open the surface directly, which left this screen —
     * widgets, Live-Updates, the diagnostics entry on the version line (#110),
     * sign-out — reachable only by the back gesture. A way out is not a way in.
     * The app icon leads here again; this button is the jump onward. What #115
     * won is untouched: the surface still opens **inside** the app (no URL bar,
     * no second login, one icon), it is simply no longer the start surface.
     *
     * Widgets, app-icon shortcuts and notifications keep going straight to their
     * place in the surface — they reach [PwaLauncher] without passing through
     * here, and nothing on this path reroutes them (`SurfaceLaunchTest`).
     */
    private fun openSolaris() {
        PwaLauncher.open(this, PwaLauncher.Routes.ROOT)
    }

    private fun logout() {
        // Stop the realtime service and forget the opt-in — we're unpaired now (#48).
        RealtimeService.stop(this)
        RealtimeService.cancelRescue(this)
        ServerStore.setRealtimeEnabled(this, false)
        TokenStore.clear(this)
        ServerStore.clear(this)
        PersonaStore.clear(this)
        findViewById<EditText>(R.id.server_url).setText("")
        render()
        toast(getString(R.string.home_logged_out))
    }

    /**
     * Live-Updates switch (#48). Enabling: on Android 13+ first ensure the
     * POST_NOTIFICATIONS grant (the foreground service needs a visible notification),
     * then persist + start the service and prompt the battery-optimisation exemption.
     * Disabling: persist off + stop the service.
     */
    private fun onRealtimeToggled(enabled: Boolean) {
        if (!enabled) {
            ServerStore.setRealtimeEnabled(this, false)
            RealtimeService.stop(this)
            RealtimeService.cancelRescue(this)
            renderPollInterval()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // The result callback finishes the enable (grant or not, we proceed —
            // the notification just won't show if denied, the stream still runs).
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        enableRealtime()
    }

    private fun enableRealtime() {
        ServerStore.setRealtimeEnabled(this, true)
        RealtimeService.start(this)
        // Periodic backstop: revive the service if an OEM kills it (#48).
        RealtimeService.scheduleRescue(this)
        // Keep the switch visually in sync (a denied prompt can leave it toggled).
        runCatching { findViewById<Switch>(R.id.realtime_switch).isChecked = true }
        renderPollInterval()
        promptBatteryExemption()
    }

    /** Human label for a screen-off interval in seconds (0=off, <60=sec, else min). */
    private fun intervalLabel(seconds: Int): String = when {
        seconds <= 0 -> getString(R.string.realtime_interval_off)
        seconds < 60 -> getString(R.string.realtime_interval_sec, seconds)
        else -> getString(R.string.realtime_interval_label, seconds / 60)
    }

    /** Show the screen-off responsiveness (only while Live-Updates is on). */
    private fun renderPollInterval() {
        val tv = findViewById<TextView>(R.id.realtime_interval)
        if (!ServerStore.realtimeEnabled(this)) {
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        tv.text = intervalLabel(ServerStore.pollSeconds(this))
    }

    /** Pick the screen-off responsiveness (0=off; <60s stays live; ≥60s polls). */
    private fun pickPollInterval() {
        val seconds = intArrayOf(0, 1, 5, 10, 30, 60, 120, 240, 600, 1800, 3600)
        val labels = seconds.map { intervalLabel(it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.realtime_interval_title)
            .setItems(labels) { _, which ->
                ServerStore.setPollSeconds(this, seconds[which])
                // Re-evaluate now so a change takes effect promptly (awake or asleep).
                if (ServerStore.realtimeEnabled(this)) RealtimeService.start(this)
                renderPollInterval()
            }
            .setNegativeButton(R.string.home_pick_widget_cancel, null)
            .show()
    }

    /**
     * Ask the OS to exempt Solaris from battery optimisation so the SSE service
     * isn't killed (#48). Guarded/try-catch — some OEMs/ROMs don't expose the
     * intent, and it must never crash the toggle.
     */
    private fun promptBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) return
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: Exception) {
            // Fall back to the general battery-settings screen, else give up silently.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun showError(where: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        // An error in the *current* session — jump straight to the details view
        // (Copy + Melden), no "report last crash?" preamble needed.
        showCrashDetails(sw.toString())
    }

    /**
     * Surface a crash saved by [SolarisApp] from a previous process death, then
     * clear it so it doesn't nag on every launch. Staged: a discreet Stage-1
     * message with [Details]/[Fehler melden]/[Schließen] — the raw trace only
     * appears on request.
     */
    private fun showLastCrashIfAny() {
        val f = File(filesDir, SolarisApp.CRASH_FILE)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        if (!text.isNullOrBlank()) showCrashPrompt(text)
    }

    /** Stage 1 — friendly message; the trace is not shown yet. */
    private fun showCrashPrompt(trace: String) {
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(R.string.crash_message)
                .setPositiveButton(R.string.crash_report) { _, _ -> reportCrash(trace) }
                .setNeutralButton(R.string.crash_details) { _, _ -> showCrashDetails(trace) }
                .setNegativeButton(R.string.crash_close, null)
                .show()
        }
    }

    /** Stage 2 — the full stack trace (scrollable), with Copy + Melden. */
    private fun showCrashDetails(trace: String) {
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_details_title)
                .setMessage(trace.take(6000))
                .setPositiveButton(R.string.crash_copy) { _, _ -> copyTrace(trace) }
                .setNeutralButton(R.string.crash_report) { _, _ -> reportCrash(trace) }
                .setNegativeButton(R.string.crash_close, null)
                .show()
        }
    }

    private fun copyTrace(trace: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Solaris-Fehler", trace))
        toast(getString(R.string.crash_copied))
    }

    /** Open a prefilled GitHub new-issue form (title+body+label) in a Custom Tab. */
    private fun reportCrash(trace: String) {
        val url = cloud.dopp.solaris.CrashReport.issueUrl(
            trace = trace,
            appVersion = appVersionName(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        openUrl(Uri.parse(url))
    }

    /**
     * The way to the app download (#141, #149, #151).
     *
     * Lives in the footer row beside sign-out and the version, because all three
     * are secondary and the installed version must appear exactly **once** — as a
     * line of its own above the footer it repeated what stood right below it.
     *
     * **Always** present once a server is paired: the action has to be reachable
     * when the user wants it, not only in the moment the server happens to be
     * announcing something. Hiding it whenever nothing is new made the only route
     * to a fresh build disappear exactly when someone went looking for it.
     *
     * Quiet "App laden" by default; when the paired server publishes something
     * genuinely newer it names that version in the accent colour. The tap always
     * goes to the paired server's own `/download` — never to an address out of a
     * response, unless `/download/version` itself names an https target (see
     * [UpdateCheck]). Unpaired: no base, no entry.
     */
    private fun checkForUpdate() {
        val installed = appVersionName()
        val base = ServerStore.baseUrl(this)?.trimEnd('/') ?: return
        val entry = findViewById<TextView>(R.id.update_hint)
        runCatching {
            entry.text = getString(R.string.home_update_download)
            entry.visibility = View.VISIBLE
            entry.setOnClickListener { openUrl(Uri.parse("$base/download")) }
        }
        kotlin.concurrent.thread {
            val found = UpdateCheck.fetch(applicationContext, installed) ?: return@thread
            runOnUiThread {
                runCatching {
                    entry.text = getString(R.string.home_update_available, found.versionName)
                    entry.setTextColor(getColor(R.color.solaris_accent))
                    entry.setOnClickListener { openUrl(Uri.parse(found.downloadUrl)) }
                }
            }
        }
    }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
