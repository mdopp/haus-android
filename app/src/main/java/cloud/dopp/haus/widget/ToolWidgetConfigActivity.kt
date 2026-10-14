package cloud.dopp.haus.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.data.ToolDef
import cloud.dopp.haus.data.ToolDefs
import cloud.dopp.haus.ui.HomeActivity
import kotlin.concurrent.thread

/**
 * Binds a freshly placed generic tool widget to one `.tool` (#70/#72). The list is
 * the **live catalog** (`/napi/defs/tool`), not a hardcoded set — that is what lets
 * a new plugin become a widget without an app rebuild — with the last-seen catalog
 * as the offline fallback.
 *
 * Only tools that can actually fill a list card are offered ([ToolDef.isListable]):
 * a tool without a `tool-api-path` (`.note`) has nothing to fetch, and one with an
 * empty `tool-cell-schema` (`.energy`, `.home`) is a bespoke browser card. Pinning
 * either would leave a permanently blank widget.
 *
 * When the user picked the tool in the in-app section (#72) the id is already
 * parked ([WidgetStore.consumePendingToolId]) and this screen binds and closes
 * without asking again.
 *
 * Re-entered from the launcher's *Einrichten* entry the instance is already bound
 * (#96): the list then shows the current tool and a back-out leaves the widget
 * alone — see [ConfigEntry].
 */
class ToolWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** The tool this instance already shows, if we were re-opened (#96). */
    private var boundToolId: String? = null

    private var entry = ConfigEntry.FIRST_PLACEMENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        boundToolId = WidgetStore.toolId(this, appWidgetId)
        entry = ConfigEntry.of(boundToolId != null)
        // Backing out of a reconfigure must not cost the user the widget (#96).
        setResult(entry.backOutResult, resultIntent())
        setContentView(R.layout.activity_widget_config)
        applyStatusBarInset()

        if (!ServerStore.isConfigured(this) || !TokenStore.isPaired(this)) {
            findViewById<TextView>(R.id.config_status).setText(R.string.widget_config_pair_first)
            findViewById<Button>(R.id.config_pair).apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(context, HomeActivity::class.java)) }
            }
            return
        }
        loadTools()
    }

    private fun applyStatusBarInset() {
        val root = findViewById<View>(R.id.config_root)
        val basePad = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = basePad + top)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (ServerStore.isConfigured(this) && TokenStore.isPaired(this) &&
            findViewById<ListView>(R.id.config_list).adapter == null
        ) {
            findViewById<Button>(R.id.config_pair).visibility = View.GONE
            loadTools()
        }
    }

    private fun loadTools() {
        val status = findViewById<TextView>(R.id.config_status)
        val list = findViewById<ListView>(R.id.config_list)
        status.setText(R.string.tool_config_loading)
        // The parked id belongs to a *pending placement*, not to a widget that is
        // already bound — a reconfigure must ask, not silently re-bind (#96).
        val pending = if (entry.isReconfigure) {
            null
        } else {
            WidgetStore.consumePendingToolId(applicationContext)
        }
        thread {
            val defs = loadCatalog().filter { it.isListable }
            val preset = pending?.let { id -> defs.firstOrNull { it.id == id } }
            runOnUiThread {
                if (preset != null) pick(preset) else showTools(list, status, defs)
            }
        }
    }

    /** Fresh catalog when reachable (and remembered), else the last-seen one. */
    private fun loadCatalog(): List<ToolDef> {
        val body = try {
            ApiClient(applicationContext).toolCatalogBody()
        } catch (e: Exception) {
            null
        }
        if (body != null) {
            WidgetStore.setToolCatalogJson(applicationContext, body)
            val fresh = ToolDefs.parseCatalog(body)
            if (fresh.isNotEmpty()) return fresh
        }
        return ToolDefs.parseCatalog(WidgetStore.toolCatalogJson(applicationContext))
    }

    private fun showTools(list: ListView, status: TextView, defs: List<ToolDef>) {
        if (defs.isEmpty()) {
            status.setText(R.string.tool_config_empty)
            return
        }
        status.setText(
            if (entry.isReconfigure) R.string.tool_config_change else R.string.tool_config_pick,
        )
        // Reconfigure: mark the tool the widget currently shows and open on it (#96).
        val marked = ConfigPrefill.markedIndex(entry, defs) { it.id == boundToolId }
        list.adapter = ToolAdapter(this, defs, marked)
        list.setOnItemClickListener { _, _, pos, _ -> pick(defs[pos]) }
        ConfigPrefill.scrollTo(list, marked)
    }

    private class ToolAdapter(
        private val ctx: Context,
        private val defs: List<ToolDef>,
        /** The instance's current tool, marked in the list on reconfigure (#96). */
        private val markedPosition: Int,
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(ctx)

        override fun getCount() = defs.size
        override fun getItem(position: Int) = defs[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: inflater.inflate(R.layout.item_widget_type, parent, false)
            val def = defs[position]
            val current = position == markedPosition
            v.findViewById<ImageView>(R.id.type_icon).setImageResource(R.drawable.ic_services)
            v.findViewById<TextView>(R.id.type_label).text =
                if (current) ctx.getString(R.string.widget_config_current, def.label) else def.label
            ConfigPrefill.paint(v, current, v.findViewById(R.id.type_current))
            return v
        }
    }

    private fun pick(def: ToolDef) {
        // Keyed by appWidgetId and it drops the cached rows, so re-binding replaces
        // the instance's entry instead of adding one (#96).
        WidgetStore.bindTool(this, appWidgetId, def.id, def.label)
        ToolWidgetProvider.requestRefresh(applicationContext, appWidgetId)
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}
