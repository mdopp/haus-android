package cloud.dopp.haus.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Device
import cloud.dopp.haus.data.DeviceSearch
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Widget configuration screen shown when the widget is placed: lists the
 * household's controllable actuators and binds the chosen one to this instance.
 * If the app isn't paired yet, it points the user to pairing first.
 *
 * The same screen is re-entered from the launcher's *Einrichten* entry (#96), on
 * an already bound instance — see [ConfigEntry] for why that path shows the
 * current device and answers a back-out differently.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** All addable devices, once loaded — the source the search filters against. */
    private var allDevices: List<Device> = emptyList()

    /** The device this instance is already bound to, if we were re-opened (#96). */
    private var boundEntityId: String? = null

    private var entry = ConfigEntry.FIRST_PLACEMENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default result: if the user backs out, the host drops the widget.
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        boundEntityId = WidgetStore.entityId(this, appWidgetId)
        entry = ConfigEntry.of(boundEntityId != null)
        // Re-entered on a live widget: backing out must leave it bound, so the
        // armed answer is a no-op RESULT_OK instead of "drop it" (#96).
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
        loadDevices()
    }

    /**
     * Keep the heading below the system status bar on notch/punch-hole phones:
     * add the status-bar top inset on top of the layout's base 20dp padding so
     * "Geräte wählen:" never collides with the clock (#19).
     */
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
        // Coming back from pairing — retry if now connected.
        if (ServerStore.isConfigured(this) && TokenStore.isPaired(this) &&
            findViewById<ListView>(R.id.config_list).adapter == null
        ) {
            findViewById<Button>(R.id.config_pair).visibility = View.GONE
            loadDevices()
        }
    }

    private fun loadDevices() {
        val status = findViewById<TextView>(R.id.config_status)
        val list = findViewById<ListView>(R.id.config_list)
        status.setText(R.string.widget_config_loading)
        thread {
            val devices = try {
                ApiClient(applicationContext).listAddable()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { showDevices(list, status, devices) }
        }
    }

    private fun showDevices(list: ListView, status: TextView, devices: List<Device>) {
        if (devices.isEmpty()) {
            status.setText(R.string.widget_config_empty)
            return
        }
        allDevices = devices
        // The app-icon menu is built from this same household (#100) — hand it the
        // list we just fetched instead of making it ask again.
        AppShortcuts.rememberCatalog(applicationContext, devices)
        status.setText(pickPrompt())

        val search = findViewById<EditText>(R.id.config_search)
        search.visibility = View.VISIBLE
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(list, status, s?.toString().orEmpty())
            }
        })

        // The adapter emits header + row items; the click maps back to the Device.
        list.setOnItemClickListener { _, _, pos, _ ->
            (list.adapter as? SectionedDeviceAdapter)?.deviceAt(pos)?.let { pick(it) }
        }
        applyFilter(list, status, "")
    }

    /** Live filter by device name AND room (case-insensitive), keeping grouping. */
    private fun applyFilter(list: ListView, status: TextView, query: String) {
        val matches = DeviceSearch.filter(allDevices, query)
        status.setText(if (matches.isEmpty()) R.string.widget_config_no_match else pickPrompt())
        val rows = groupByRoom(matches)
        // Reconfigure: mark the device the widget currently shows and start on it
        // instead of at the top of the household (#96). A filter that hides it
        // yields NONE — nothing marked, no scroll.
        val marked = ConfigPrefill.markedIndex(entry, rows) {
            it is Row.DeviceRow && it.device.entityId == boundEntityId
        }
        list.adapter = SectionedDeviceAdapter(this, rows, marked)
        ConfigPrefill.scrollTo(list, marked)
    }

    /** "Gerät wählen" on a fresh widget, "Gerät ändern" when re-configuring. */
    private fun pickPrompt() =
        if (entry.isReconfigure) R.string.widget_config_change else R.string.widget_config_pick

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    /**
     * Group devices by room (fallback: domain), rooms sorted alphabetically in
     * German, devices within a room by name. Returns a flat list of render items
     * (header, then its rows) for the [SectionedDeviceAdapter].
     */
    private fun groupByRoom(devices: List<Device>): List<Row> {
        val coll = java.text.Collator.getInstance(Locale.GERMANY)
        val byRoom = devices.groupBy { it.room ?: it.domain }
        val items = ArrayList<Row>()
        byRoom.keys.sortedWith(coll).forEach { room ->
            items += Row.Header(room)
            byRoom.getValue(room).sortedWith(compareBy(coll) { it.name })
                .forEach { items += Row.DeviceRow(it) }
        }
        return items
    }

    /** A rendered picker line: a room header or a device row. */
    private sealed class Row {
        data class Header(val room: String) : Row()
        data class DeviceRow(val device: Device) : Row()
    }

    /**
     * Sectioned picker adapter: two item view types (room header vs device row).
     * Headers are non-selectable so only device rows fire the click.
     */
    private class SectionedDeviceAdapter(
        private val ctx: Context,
        private val items: List<Row>,
        /** The instance's current binding, marked in the list on reconfigure (#96). */
        private val markedPosition: Int,
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(ctx)

        fun deviceAt(position: Int): Device? = (items.getOrNull(position) as? Row.DeviceRow)?.device

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) =
            if (items[position] is Row.Header) TYPE_HEADER else TYPE_ROW

        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = items[position] is Row.DeviceRow

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val row = items[position]) {
                is Row.Header -> {
                    val v = convertView ?: inflater.inflate(R.layout.item_room_header, parent, false)
                    (v as TextView).text = row.room
                    v
                }
                is Row.DeviceRow -> {
                    val v = convertView ?: inflater.inflate(R.layout.item_device_row, parent, false)
                    val d = row.device
                    val current = position == markedPosition
                    v.findViewById<ImageView>(R.id.row_icon).setImageResource(DeviceIcons.forDomain(d.domain))
                    v.findViewById<TextView>(R.id.row_name).text = d.name
                    val where = d.room ?: d.domain
                    // The name line is a device's own, so the marker rides the room
                    // line, where a long name can't push it out of view (#96).
                    v.findViewById<TextView>(R.id.row_room).text =
                        if (current) ctx.getString(R.string.widget_config_current, where) else where
                    ConfigPrefill.paint(v, current, v.findViewById(R.id.row_current))
                    v
                }
            }
        }

        private companion object {
            const val TYPE_HEADER = 0
            const val TYPE_ROW = 1
        }
    }

    private fun pick(d: Device) {
        // Re-bound to a *different* device: the last-good card cached for this
        // instance belongs to the old one, so drop it rather than let the widget
        // draw the previous device until the refresh lands (#96).
        if (boundEntityId != null && boundEntityId != d.entityId) {
            WidgetCache.clear(this, appWidgetId)
        }
        // Keyed by appWidgetId, so re-binding overwrites — never a second entry.
        WidgetStore.bind(this, appWidgetId, d.entityId, d.name, d.domain, d.deviceClass)
        DeviceWidgetProvider.requestRefresh(applicationContext, appWidgetId)
        // Refresh the watch set now that one more entity is bound (#48), and let
        // the live stream look again: this may be the first widget.
        cloud.dopp.haus.realtime.WatchSet.postCurrentAsync(applicationContext)
        cloud.dopp.haus.realtime.Live.ensure(applicationContext)
        // …and the app-icon long-press menu, which is that same device set (#97).
        AppShortcuts.refreshAsync(applicationContext)
        setResult(RESULT_OK, resultIntent())
        finish()
    }
}
