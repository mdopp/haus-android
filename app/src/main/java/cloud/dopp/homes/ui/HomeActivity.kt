package cloud.dopp.homes.ui

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.view.ContextThemeWrapper
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import cloud.dopp.homes.HomesConfig
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.BoxApiClient
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.PairLinks
import cloud.dopp.homes.data.Pairing
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.data.ToolDef
import cloud.dopp.homes.realtime.Live
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone
import cloud.dopp.homes.widget.AppShortcuts
import cloud.dopp.homes.widget.DeviceIcons
import cloud.dopp.homes.widget.DeviceTaps
import cloud.dopp.homes.widget.HomesPages
import cloud.dopp.homes.widget.ToolWidgetProvider
import cloud.dopp.homes.widget.WidgetActionReceiver
import cloud.dopp.homes.widget.WidgetPin
import cloud.dopp.homes.widget.WidgetStore
import cloud.dopp.homes.widget.WidgetTypes
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.concurrent.thread

/**
 * The app's one screen (#176, laib ADR 0022 §1): pairing, then the widgets.
 *
 * **Pairing.** The person enters Homes' address; the app opens
 * `<homes>/pair-device` in a Custom Tab, where their Authelia session is; they
 * confirm; Homes redirects to `cloud.dopp.homes://pair#token=homes_device_…`, which
 * lands back here. Homes' device page also shows a QR code
 * (`cloud.dopp.homes://setup?server=…&token=…`), scanned in-app — it has no
 * intent filter, so no web page can point the app at a foreign server.
 *
 * Nothing is stored behind the person's back ([Pairing]): a pair link counts
 * only while a pairing started here is pending, a link or a scanned code asks
 * first in a dialog naming the host, the token is checked with Homes'
 * `/napi/whoami` before it is stored, an http address is refused with a
 * message, and a token that cannot be saved is said so.
 *
 * **Paired.** The app's icon and the assistant's name (`/napi/assistent`,
 * „Homes“ until Homes names one) head the screen; first below them the person's
 * favorite devices, a tap switches one, each with the fine controls Homes'
 * devices page has ([DeviceControls], owner 2026-11-03), and „Alle Geräte“,
 * Homes' devices page ([StartDevices], owner 2026-11-02); then one list of
 * widgets to add, the live switch under its hint
 * (owner, 2026-11-03: no groups) — one device, the energy, the tools from
 * Homes' catalog —, each one tap from the home screen; under Mehr, Daten
 * sichern (#194); and where the app is paired, beside the way to unpair.
 */
class HomeActivity : AppCompatActivity() {

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScanned(it) }
    }

    /** Android 13+: the live widgets' notification shows only with this permission. */
    private val notifyLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @Volatile
    private var toolsLoading = false

    @Volatile
    private var devicesLoading = false

    /** The favorites drawn last; null until the first read or the cache. */
    private var devices: List<Card>? = null

    /** The last read failed and nothing was ever read: say so instead of an empty card. */
    private var devicesFailed = false

    /** A device tap finished (WidgetActionReceiver, WidgetActionActivity): read the favorites again. */
    private val tapDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        findViewById<Button>(R.id.connect_btn).setOnClickListener { onConnect() }
        findViewById<Button>(R.id.qr_scan_btn).setOnClickListener { startScan() }
        findViewById<Button>(R.id.logout_btn).setOnClickListener { unpair() }
        findViewById<SwitchCompat>(R.id.live_switch).setOnCheckedChangeListener { _, on ->
            if (on != Live.isEnabled(this)) Live.setEnabled(applicationContext, on)
        }
        // The live row (owner, 2026-11-03): while the exemption is missing a tap asks for it, else it switches.
        val onLiveRow = View.OnClickListener {
            if (!Live.exempt(this)) requestExemption() else findViewById<SwitchCompat>(R.id.live_switch).toggle()
        }
        findViewById<View>(R.id.live_row).setOnClickListener(onLiveRow)
        findViewById<TextView>(R.id.live_battery).setOnClickListener(onLiveRow)
        findViewById<TextView>(R.id.app_version).text = getString(R.string.app_version_fmt, appVersionName())
        padForSystemBars(findViewById(R.id.home_root))
        handleDeepLink(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, tapDone, IntentFilter(DeviceTaps.ACTION_DONE), ContextCompat.RECEIVER_NOT_EXPORTED)
        render()
        if (paired()) refreshDevices()
        // The app-icon long-press menu (#97): picks up a widget removed while away.
        if (paired()) AppShortcuts.refreshAsync(applicationContext)
        // The app's screen may start the live service (Android 12+): placed widgets, the switch, the pairing.
        Live.ensure(applicationContext)
    }

    override fun onPause() {
        runCatching { unregisterReceiver(tapDone) }
        super.onPause()
    }

    private fun paired() = ServerStore.isConfigured(this) && TokenStore.isPaired(this)

    /** targetSdk 35 is edge-to-edge: keep the content below the status bar and above the navigation bar. */

    private fun render() {
        val connected = paired()
        findViewById<View>(R.id.connect_section).visibility = if (connected) View.GONE else View.VISIBLE
        findViewById<View>(R.id.connected_section).visibility = if (connected) View.VISIBLE else View.GONE
        val url = findViewById<EditText>(R.id.server_url)
        if (url.text.isNullOrBlank()) ServerStore.baseUrl(this)?.let { url.setText(it) }
        findViewById<TextView>(R.id.connect_hint).text =
            if (ServerStore.isConfigured(this) && !TokenStore.isPaired(this)) {
                getString(R.string.home_reconnect, ServerStore.host(this) ?: "")
            } else {
                getString(R.string.home_connect_hint)
            }
        renderAssistent()
        if (!connected) return
        findViewById<TextView>(R.id.status).text = getString(R.string.home_connected, ServerStore.host(this) ?: "")
        findViewById<SwitchCompat>(R.id.live_switch).isChecked = Live.isEnabled(this)
        findViewById<TextView>(R.id.live_battery).setText(
            if (Live.exempt(this)) R.string.live_battery_on else R.string.live_battery_off,
        )
        renderDevices()
        renderWidgetTypes()
        renderTools()
        refreshAssistent()
    }

    // --- the assistant's name -----------------------------------------------

    private fun renderAssistent() {
        findViewById<TextView>(R.id.assistent_name).text = Assistent.name(this)
    }

    /** Ask Homes for the assistant's name; redraw when it changed. */
    private fun refreshAssistent() {
        thread {
            val fresh = runCatching { BoxApiClient(applicationContext).getAssistent() }.getOrNull() ?: return@thread
            if (!Assistent.save(applicationContext, fresh)) return@thread
            // the share sheet's target carries the name (#188)
            runCatching { AppShortcuts.refresh(applicationContext) }
            runOnUiThread { if (!isFinishing) renderAssistent() }
        }
    }

    // --- the devices (owner, 2026-11-02) --------------------------------------

    /** The favorites, then „Alle Geräte“; the list read last draws at once. */
    private fun renderDevices() {
        val shown = devices ?: StartDevices.load(this).also { devices = it }
        val group = findViewById<ViewGroup>(R.id.devices_group)
        group.removeAllViews()
        when {
            shown == null && devicesFailed -> group.addView(note(group, getString(R.string.home_devices_unreadable)))
            shown == null -> group.addView(note(group, getString(R.string.home_devices_loading)))
            shown.isEmpty() -> group.addView(note(group, getString(R.string.home_devices_none)))
            else -> shown.forEach { group.addView(deviceRow(group, it)) }
        }
        // an open level sheet stays open across a fresh read and shows its level
        levelSheet?.let { sheet -> shown?.firstOrNull { it.entityId == sheet.entityId }?.let { sheet.refresh(DeviceControls.of(it).slider) } }
        group.addView(
            row(group, R.drawable.ic_device, getString(R.string.home_devices_all)) {
                openHomes(HomesPages.RAEUME)
            },
        )
    }

    /** Read the favorites from Homes, keep them, draw them; a failed read keeps what is shown. */
    private fun refreshDevices() {
        if (devicesLoading) return
        devicesLoading = true
        val app = applicationContext
        thread {
            val fresh = runCatching { ApiClient(app).favoritesBody() }.getOrNull()
            val parsed = fresh?.let { ApiClient.parseFavorites(it) }
            if (fresh != null && parsed != null) StartDevices.save(app, fresh)
            runOnUiThread {
                devicesLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (parsed != null) {
                    devices = parsed
                    devicesFailed = false
                } else {
                    devicesFailed = true
                }
                if (paired()) renderDevices()
            }
        }
    }

    private fun deviceRow(group: ViewGroup, card: Card): View {
        val v = layoutInflater.inflate(R.layout.item_device_row, group, false)
        val icon = v.findViewById<ImageView>(R.id.row_icon)
        icon.setImageResource(DeviceIcons.forState(card.domain, card.state, card.deviceClass))
        icon.setColorFilter(getColor(if (card.isOn) R.color.homes_accent else R.color.homes_text_muted))
        v.findViewById<TextView>(R.id.row_name).text = card.name
        val state = v.findViewById<TextView>(R.id.row_room)
        val levelView = v.findViewById<TextView>(R.id.row_level)
        val line = StartDevices.levelLine(card)
        fun switching() {
            state.text = getString(R.string.home_device_switching)
            levelView.visibility = View.GONE
        }
        if (line == null) {
            state.text = StartDevices.stateText(card)
            v.contentDescription = card.name + ", " + state.text
        } else {
            val (words, level) = line
            val percent = StartDevices.percent(level.value)
            state.text = "$words ·"
            levelView.text = percent
            levelView.visibility = View.VISIBLE
            levelView.contentDescription = getString(R.string.home_device_level, card.name, level.label, percent)
            levelView.setOnClickListener { openLevel(card, level, ::switching) }
            v.contentDescription = "${card.name}, $words, $percent"
            reachAtLeast48dp(v, levelView)
        }
        v.setOnClickListener {
            if (StartDevices.acts(card)) {
                // the tap answers at once; the fresh state comes with DeviceTaps.ACTION_DONE
                if (card.domain != "lock") switching()
                sendBroadcast(WidgetActionReceiver.tap(this, card.entityId, card.name, card.supportedFeatures))
            } else {
                openHomes(HomesPages.item(card.page))
            }
        }
        v.setOnLongClickListener {
            openHomes(HomesPages.item(card.page))
            true
        }
        controls(v.findViewById(R.id.row_controls), card, ::switching)
        return v
    }

    /** The level sheet open now, so a fresh read updates it instead of closing it. */
    private var levelSheet: LevelSheet? = null

    /** The favorite's level in its sheet (owner, 2026-11-03): each pick is one call through the device receiver. */
    private fun openLevel(card: Card, level: DeviceControls.Slider, switching: () -> Unit) {
        levelSheet?.dialog?.dismiss()
        val sheet = LevelSheet(this, card.entityId, card.name, level) { service, data ->
            switching()
            sendBroadcast(WidgetActionReceiver.call(this, card.entityId, card.name, service, data))
        }
        sheet.dialog.setOnDismissListener { if (levelSheet === sheet) levelSheet = null }
        levelSheet = sheet
        sheet.show()
    }

    /**
     * The level's touch target, at least 48 dp each way around it, while its
     * text stays the size of the line: a delegate on the row, set once the
     * row is laid out.
     */
    private fun reachAtLeast48dp(row: View, level: View) {
        val min = (48 * resources.displayMetrics.density).toInt()
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (level.visibility != View.VISIBLE || level.width == 0) return@addOnLayoutChangeListener
            val r = android.graphics.Rect(0, 0, level.width, level.height)
            (row as ViewGroup).offsetDescendantRectToMyCoords(level, r)
            val dy = ((min - r.height()) / 2).coerceAtLeast(0)
            val dx = ((min - r.width()) / 2).coerceAtLeast(0)
            r.inset(-dx, -dy)
            row.touchDelegate = android.view.TouchDelegate(r, level)
        }
    }

    /**
     * The favorite's fine buttons ([DeviceControls], owner 2026-11-03). No
     * slider on the row: its level opens [LevelSheet]. Each goes through the
     * device receiver, so a sensitive call asks in the one dialog and the
     * list reads again after.
     */
    private fun controls(holder: LinearLayout, card: Card, switching: () -> Unit) {
        val c = DeviceControls.of(card)
        if (c.buttons.isEmpty()) return
        holder.visibility = View.VISIBLE
        fun send(service: String, data: Map<String, Any>?) {
            switching()
            sendBroadcast(WidgetActionReceiver.call(this, card.entityId, card.name, service, data))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false // a label that wraps must not push its button down
        }
        c.buttons.forEachIndexed { i, b ->
            val btn = Button(ContextThemeWrapper(this, R.style.Widget_Homes_Button_Control), null, 0)
            btn.text = b.label
            btn.contentDescription = card.name + ": " + b.label
            btn.setOnClickListener { send(b.service, b.data) }
            bar.addView(
                btn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = resources.getDimensionPixelSize(R.dimen.space_xs)
                },
            )
        }
        holder.addView(bar)
    }

    private fun note(group: ViewGroup, text: String): View =
        layoutInflater.inflate(R.layout.item_device_row, group, false).apply {
            findViewById<ImageView>(R.id.row_icon).visibility = View.GONE
            findViewById<TextView>(R.id.row_name).visibility = View.GONE
            findViewById<TextView>(R.id.row_room).text = text
            isClickable = false
            background = null
        }

    /** [page] of the paired Homes in a Custom Tab. */
    private fun openHomes(page: String) {
        val base = ServerStore.baseUrl(this) ?: return
        open(Uri.parse(HomesPages.url(base, page)))
    }

    // --- the widgets ----------------------------------------------------------

    /** One list, no groups (owner, 2026-11-03): one device, the energy, then the tools. */
    private fun renderWidgetTypes() {
        showTools(WidgetStore.keptToolCatalog(this).filter { it.isOffered })
        renderMehr()
    }

    /** Mehr: Daten sichern (#194), Standortverlauf (owner 2026-11-02), Homes auf dem Handy (#206). */
    private fun renderMehr() {
        val group = findViewById<ViewGroup>(R.id.mehr_group)
        group.removeAllViews()
        group.addView(
            row(group, R.drawable.ic_backup, getString(R.string.sicherung_title)) {
                startActivity(Intent(this, SicherungActivity::class.java))
            },
        )
        group.addView(
            row(group, R.drawable.ic_standort, getString(R.string.standort_title)) {
                startActivity(Intent(this, StandortActivity::class.java))
            },
        )
        group.addView(
            row(group, R.drawable.ic_konto, getString(R.string.konto_title)) {
                startActivity(Intent(this, HomesKontoActivity::class.java))
            },
        )
    }

    private fun row(group: ViewGroup, icon: Int, label: String, onTap: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.item_widget_type, group, false)
        row.findViewById<ImageView>(R.id.type_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.type_label).text = label
        row.setOnClickListener { onTap() }
        return row
    }

    /**
     * The tools from Homes' catalog (`/napi/defs/tool`): the last one seen draws
     * at once, then the fresh one replaces it. Only tools that fill a list are
     * offered, and not the contacts ([ToolDef.isOffered]).
     */
    private fun renderTools() {
        if (toolsLoading) return
        toolsLoading = true
        thread {
            val fresh = WidgetStore.freshToolCatalog(applicationContext)
            runOnUiThread {
                toolsLoading = false
                if (fresh != null && !isFinishing) showTools(fresh.filter { it.isOffered })
            }
        }
    }

    /** The widget list: the fixed types first, then a row per tool. */
    private fun showTools(defs: List<ToolDef>) {
        val group = findViewById<ViewGroup>(R.id.widgets_group)
        group.removeAllViews()
        WidgetTypes.ALL.forEach { entry ->
            group.addView(row(group, entry.icon, getString(entry.label)) { pin(entry.provider) })
        }
        defs.forEach { def ->
            group.addView(row(group, R.drawable.ic_services, def.label) { pinTool(def) })
        }
    }

    private fun pinManager(): AppWidgetManager? {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || mgr == null || !mgr.isRequestPinAppWidgetSupported) {
            toast(getString(R.string.home_add_widget_manual))
            return null
        }
        return mgr
    }

    private fun pin(provider: Class<*>) {
        val mgr = pinManager() ?: return
        WidgetPin.request(this, mgr, provider)
    }

    private fun pinTool(def: ToolDef) {
        val mgr = pinManager() ?: return
        WidgetPin.request(this, mgr, ToolWidgetProvider::class.java, def.id, def.label)
    }

    // --- pairing --------------------------------------------------------------

    /** The typed address: refused if http, else stored and paired at. */
    private fun onConnect() {
        val typed = findViewById<EditText>(R.id.server_url).text.toString().trim()
        if (typed.isBlank()) {
            toast(getString(R.string.home_enter_url))
            return
        }
        if (ServerStore.refusesHttp(typed)) {
            toast(getString(R.string.server_http_refused))
            return
        }
        val server = ServerStore.normalize(typed)
        if (server == null) {
            toast(getString(R.string.home_enter_url))
            return
        }
        ServerStore.setBaseUrl(this, server)
        openPairing(server)
    }

    /** Open `/pair-device` at [server]; its pair link is taken while this pairing is pending. */
    private fun openPairing(server: String) {
        Pairing.start(this, server)
        open(Uri.parse(server + HomesConfig.PAIR_PATH))
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
     * `cloud.dopp.homes://setup?server=…&token=…` from Homes' device page, or a
     * plain address. Nothing switches before the person confirms the host.
     */
    private fun handleScanned(text: String) {
        val setup = PairLinks.parseSetup(text)
        val raw = setup?.server ?: text.trim().takeIf { it.contains("://") }
        if (raw == null) {
            toast(getString(R.string.qr_invalid))
            return
        }
        if (ServerStore.refusesHttp(raw)) {
            toast(getString(R.string.server_http_refused))
            return
        }
        val server = ServerStore.normalize(raw)
        if (server == null) {
            toast(getString(R.string.qr_invalid))
            return
        }
        val host = ServerStore.hostOf(server)
        val token = setup?.token
        if (token != null) {
            confirm(getString(R.string.pairing_confirm_setup, host)) { pair(server, token) }
        } else {
            confirm(getString(R.string.pairing_confirm_server, host)) { openPairing(server) }
        }
    }

    /**
     * The redirect from /pair-device: `cloud.dopp.homes://pair#token=homes_device_…&id=…`.
     * Taken only while a pairing started here waits for it, and only once the
     * person confirms the host.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return
        val data = intent.data ?: return
        if (!PairLinks.isPairLink(data)) return
        intent.data = null // handled once, not again on a recreate
        val server = Pairing.pending(this)
        if (server == null) {
            toast(getString(R.string.pairing_not_started))
            return
        }
        val token = PairLinks.pairToken(data)
        if (token == null) {
            toast(getString(R.string.pairing_failed))
            return
        }
        confirm(
            getString(R.string.pairing_confirm_link, ServerStore.hostOf(server)),
            onNo = { Pairing.cancel(this) },
        ) { pair(server, token) }
    }

    /** Ask before pairing; [message] names the host. The app's one dialog ([ActionDialog]). */
    private fun confirm(message: String, onNo: () -> Unit = {}, onYes: () -> Unit) {
        ActionDialog.show(
            activity = this,
            title = getString(R.string.pairing_confirm_title),
            message = message,
            sheet = ActionSheet(listOf(ActionItem(R.string.pairing_confirm_yes, ActionTone.PRIMARY))),
            onPick = { onYes() },
            onCancel = onNo,
            footer = getString(R.string.pairing_confirm_no),
            paintScrim = false,
        )
    }

    /** Check [token] with the Homes at [server] off the main thread, then store it and say how it went. */
    private fun pair(server: String, token: String) {
        val app = applicationContext
        thread {
            val checked = Pairing.check(server, token)
            runOnUiThread {
                val outcome = if (checked == Pairing.Outcome.PAIRED) Pairing.store(app, server, token) else checked
                toast(
                    getString(
                        when (outcome) {
                            Pairing.Outcome.PAIRED -> R.string.pairing_success
                            Pairing.Outcome.REFUSED -> R.string.pairing_refused
                            Pairing.Outcome.UNREACHABLE -> R.string.pairing_unreachable
                            Pairing.Outcome.NOT_SAVED -> R.string.pairing_not_saved
                        },
                    ),
                )
                if (outcome == Pairing.Outcome.PAIRED) askExemptionOnce()
                Live.ensure(app)
                if (!isFinishing) render()
            }
        }
    }

    /**
     * Once paired, ask once — saying why — to be exempt from battery
     * optimization: only then may Android's restart of the live service after
     * its process died go foreground on Android 12+. Then the notification.
     */
    private fun askExemptionOnce() {
        val prefs = getSharedPreferences(PREFS_ASKED, MODE_PRIVATE)
        if (Live.exempt(this) || prefs.getBoolean(KEY_EXEMPTION, false)) {
            askToNotify()
            return
        }
        prefs.edit().putBoolean(KEY_EXEMPTION, true).apply()
        ActionDialog.show(
            activity = this,
            title = getString(R.string.live_battery_title),
            message = getString(R.string.live_battery_why),
            sheet = ActionSheet(listOf(ActionItem(R.string.live_battery_yes, ActionTone.PRIMARY))),
            onPick = {
                requestExemption()
                askToNotify()
            },
            onCancel = { askToNotify() },
            footer = getString(R.string.live_battery_no),
            paintScrim = false,
        )
    }

    /** The system's own question for the exemption, else its settings list. */
    private fun requestExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** Once paired, ask for the live widgets' notification on Android 13+; without it they still run. */
    private fun askToNotify() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notifyLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    private fun unpair() {
        StartDevices.clear(this)
        devices = null
        devicesFailed = false
        TokenStore.clear(this)
        ServerStore.clear(this)
        Assistent.clear(this)
        Pairing.cancel(this)
        HomesKonto.entfernen(applicationContext) // the household's contacts and calendars leave with the pairing (#206)
        AppShortcuts.refreshAsync(applicationContext) // takes the share target away (#188)
        findViewById<EditText>(R.id.server_url).setText("")
        Live.ensure(applicationContext)
        render()
        toast(getString(R.string.home_logged_out))
    }

    /** In a Custom Tab (it shares the browser's Authelia session), else the browser. */
    private fun open(uri: Uri) {
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val PREFS_ASKED = "homes_asked"
        const val KEY_EXEMPTION = "battery_exemption"
    }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
