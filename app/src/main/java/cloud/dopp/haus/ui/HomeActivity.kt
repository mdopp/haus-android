package cloud.dopp.haus.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.PictureDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cloud.dopp.haus.HausConfig
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.BoxApiClient
import cloud.dopp.haus.data.PairLinks
import cloud.dopp.haus.data.PersonaStore
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.data.ToolDef
import cloud.dopp.haus.data.ToolDefs
import cloud.dopp.haus.realtime.LiveStream
import cloud.dopp.haus.widget.AppShortcuts
import cloud.dopp.haus.widget.ToolWidgetProvider
import cloud.dopp.haus.widget.WidgetPin
import cloud.dopp.haus.widget.WidgetStore
import cloud.dopp.haus.widget.WidgetTypes
import com.caverock.androidsvg.SVG
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.concurrent.thread

/**
 * The app's one screen (#176, laib ADR 0022 §1): pairing, then the widgets.
 *
 * **Pairing.** The person enters Haus's address; the app opens
 * `<haus>/pair-device` in a Custom Tab, where their Authelia session is; they
 * confirm; Haus redirects to `cloud.dopp.haus://pair#token=haus_device_…`, which
 * lands back here and is stored. Done. Haus's device page also shows a QR code
 * (`cloud.dopp.haus://setup?server=…&token=…`), scanned in-app — it has no
 * intent filter, so no web page can point the app at a foreign server.
 *
 * **Paired.** The persona (name, colours, icon from `/napi/persona`) heads the
 * screen; below it every widget type, the tools from Haus's catalog and the Box
 * widgets, each one tap from the home screen; and the way to unpair.
 */
class HomeActivity : AppCompatActivity() {

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScanned(it) }
    }

    @Volatile
    private var toolsLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        findViewById<Button>(R.id.connect_btn).setOnClickListener { onConnect() }
        findViewById<Button>(R.id.qr_scan_btn).setOnClickListener { startScan() }
        findViewById<Button>(R.id.logout_btn).setOnClickListener { unpair() }
        findViewById<TextView>(R.id.app_version).text = getString(R.string.app_version_fmt, appVersionName())
        applySystemBarInsets()
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
        render()
        // The app-icon long-press menu (#97): picks up a widget removed while away.
        if (paired()) AppShortcuts.refreshAsync(applicationContext)
        // A widget placed from the launcher tray while away: the live stream looks again.
        LiveStream.refresh(applicationContext)
    }

    private fun paired() = ServerStore.isConfigured(this) && TokenStore.isPaired(this)

    /** targetSdk 35 is edge-to-edge: keep the content below the status bar and above the navigation bar. */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.home_root)
        val top = root.paddingTop
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, top + bars.top, v.paddingRight, bottom + bars.bottom)
            insets
        }
    }

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
        renderPersona()
        if (!connected) return
        findViewById<TextView>(R.id.status).text = getString(R.string.home_connected, ServerStore.host(this) ?: "")
        renderWidgetTypes()
        renderTools()
        refreshPersona()
    }

    // --- the persona ----------------------------------------------------------

    private fun renderPersona() {
        val persona = if (paired()) PersonaStore.get(this) else null
        findViewById<TextView>(R.id.persona_name).text = persona?.display ?: getString(R.string.appName)
        findViewById<TextView>(R.id.persona_greeting).text =
            persona?.greeting?.ifBlank { null } ?: getString(R.string.home_claim)
        val accent = persona?.accent?.let(::color) ?: getColor(R.color.haus_accent)
        findViewById<View>(R.id.persona_rule).setBackgroundColor(accent)
        val ground = persona?.ground?.let(::color) ?: getColor(R.color.haus_bg)
        findViewById<View>(R.id.home_root).setBackgroundColor(ground)
        val icon = findViewById<ImageView>(R.id.persona_icon)
        val svg = if (persona?.hasIcon == true) PersonaStore.icon(this) else null
        val drawable = svg?.let { runCatching { PictureDrawable(SVG.getFromString(it).renderToPicture()) }.getOrNull() }
        if (drawable == null) {
            icon.visibility = View.GONE
        } else {
            icon.setLayerType(View.LAYER_TYPE_SOFTWARE, null) // a Picture draws in software only
            icon.setImageDrawable(drawable)
            icon.visibility = View.VISIBLE
        }
    }

    private fun color(hex: String): Int? = runCatching { Color.parseColor(hex.trim()) }.getOrNull()

    /** Ask Haus who the house is (laib#130); redraw when that changed. */
    private fun refreshPersona() {
        thread {
            val client = BoxApiClient(applicationContext)
            val fresh = runCatching { client.getPersona() }.getOrNull() ?: return@thread
            val svg = if (fresh.hasIcon) runCatching { client.getPersonaIcon() }.getOrNull() else null
            val changed = fresh != PersonaStore.get(applicationContext) || svg != PersonaStore.icon(applicationContext)
            if (!changed) return@thread
            PersonaStore.save(applicationContext, fresh)
            PersonaStore.saveIcon(applicationContext, svg)
            runOnUiThread { if (!isFinishing) renderPersona() }
        }
    }

    // --- the widgets ----------------------------------------------------------

    private fun renderWidgetTypes() {
        fill(findViewById(R.id.widgets_group), WidgetTypes.ALL.filter { !it.admin })
        fill(findViewById(R.id.box_group), WidgetTypes.ALL.filter { it.admin })
    }

    private fun fill(group: ViewGroup, entries: List<WidgetTypes.Entry>) {
        group.removeAllViews()
        entries.forEach { entry ->
            group.addView(row(group, entry.icon, getString(entry.label)) { pin(entry.provider) })
        }
    }

    private fun row(group: ViewGroup, icon: Int, label: String, onTap: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.item_widget_type, group, false)
        row.findViewById<ImageView>(R.id.type_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.type_label).text = label
        row.setOnClickListener { onTap() }
        return row
    }

    /**
     * The tools from Haus's catalog (`/napi/defs/tool`): the last one seen draws
     * at once, then the fresh one replaces it. Only tools that fill a list are
     * offered ([ToolDef.isListable]).
     */
    private fun renderTools() {
        showTools(listable(WidgetStore.toolCatalogJson(this)))
        if (toolsLoading) return
        toolsLoading = true
        thread {
            val body = runCatching { ApiClient(applicationContext).toolCatalogBody() }.getOrNull()
            if (body != null) WidgetStore.setToolCatalogJson(applicationContext, body)
            runOnUiThread {
                toolsLoading = false
                if (body != null && !isFinishing) showTools(listable(body))
            }
        }
    }

    private fun listable(json: String?): List<ToolDef> = ToolDefs.parseCatalog(json).filter { it.isListable }

    private fun showTools(defs: List<ToolDef>) {
        val group = findViewById<ViewGroup>(R.id.tools_group)
        group.removeAllViews()
        val vis = if (defs.isEmpty()) View.GONE else View.VISIBLE
        group.visibility = vis
        findViewById<View>(R.id.tools_group_title).visibility = vis
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

    private fun onConnect() {
        val url = findViewById<EditText>(R.id.server_url).text.toString().trim()
        if (url.isBlank()) {
            toast(getString(R.string.home_enter_url))
            return
        }
        ServerStore.setBaseUrl(this, url)
        openPairing()
    }

    private fun openPairing() {
        val base = ServerStore.baseUrl(this) ?: return
        open(Uri.parse(base.trimEnd('/') + HausConfig.PAIR_PATH))
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

    /** `cloud.dopp.haus://setup?server=…&token=…` from Haus's device page, or a plain https address. */
    private fun handleScanned(text: String) {
        val setup = PairLinks.parseSetup(text)
        when {
            setup != null -> {
                ServerStore.setBaseUrl(this, setup.server)
                if (setup.token != null) store(setup.token) else openPairing()
            }
            text.startsWith("https://", ignoreCase = true) -> {
                ServerStore.setBaseUrl(this, text)
                openPairing()
            }
            else -> toast(getString(R.string.qr_invalid))
        }
    }

    /** The redirect from /pair-device: `cloud.dopp.haus://pair#token=haus_device_…&id=…`. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!PairLinks.isPairLink(data)) return
        val token = PairLinks.pairToken(data)
        if (token == null) {
            toast(getString(R.string.pairing_failed))
            return
        }
        store(token)
    }

    private fun store(token: String) {
        TokenStore.save(this, token)
        toast(getString(R.string.pairing_success))
        LiveStream.refresh(applicationContext)
        render()
    }

    private fun unpair() {
        TokenStore.clear(this)
        ServerStore.clear(this)
        PersonaStore.clear(this)
        findViewById<EditText>(R.id.server_url).setText("")
        LiveStream.refresh(applicationContext)
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

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
