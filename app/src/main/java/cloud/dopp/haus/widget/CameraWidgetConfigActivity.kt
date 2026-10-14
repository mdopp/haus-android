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
 * Configuration screen for the camera widget (#30): mirrors
 * [WidgetConfigActivity] but restricts the picker to `camera.*` entities. It
 * reuses the same addable list endpoint and simply filters to `domain ==
 * "camera"`. NOTE: if the household's cameras are **not** returned by
 * `/napi/portal/start/addable` (that list is scoped to controllable actuators),
 * this picker will show "Keine Kameras gefunden." — a dedicated camera-list
 * endpoint on the server would then be a Haus follow-up. We deliberately
 * do not invent one here.
 */
class CameraWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** All camera devices, once loaded — the source the search filters against. */
    private var allCameras: List<Device> = emptyList()

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
        loadCameras()
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
            loadCameras()
        }
    }

    private fun loadCameras() {
        val status = findViewById<TextView>(R.id.config_status)
        val list = findViewById<ListView>(R.id.config_list)
        status.setText(R.string.widget_config_loading)
        thread {
            val cameras = try {
                ApiClient(applicationContext).listAddable().filter { it.domain == "camera" }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { showCameras(list, status, cameras) }
        }
    }

    private fun showCameras(list: ListView, status: TextView, cameras: List<Device>) {
        if (cameras.isEmpty()) {
            status.setText(R.string.camera_config_empty)
            return
        }
        allCameras = cameras
        status.setText(R.string.camera_config_pick)

        val search = findViewById<EditText>(R.id.config_search)
        search.visibility = View.VISIBLE
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(list, status, s?.toString().orEmpty())
            }
        })

        list.setOnItemClickListener { _, _, pos, _ ->
            (list.adapter as? SectionedCameraAdapter)?.deviceAt(pos)?.let { pick(it) }
        }
        applyFilter(list, status, "")
    }

    private fun applyFilter(list: ListView, status: TextView, query: String) {
        val matches = DeviceSearch.filter(allCameras, query)
        status.setText(if (matches.isEmpty()) R.string.widget_config_no_match else R.string.camera_config_pick)
        list.adapter = SectionedCameraAdapter(this, groupByRoom(matches))
    }

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

    private sealed class Row {
        data class Header(val room: String) : Row()
        data class DeviceRow(val device: Device) : Row()
    }

    private class SectionedCameraAdapter(
        ctx: Context,
        private val items: List<Row>,
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
                    v.findViewById<ImageView>(R.id.row_icon).setImageResource(R.drawable.ic_camera)
                    v.findViewById<TextView>(R.id.row_name).text = d.name
                    v.findViewById<TextView>(R.id.row_room).text = d.room ?: d.domain
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
        WidgetStore.bind(this, appWidgetId, d.entityId, d.name, d.domain, d.deviceClass)
        // Re-render the tile now that it's bound (trigger widget — no snapshot fetch).
        AppWidgetManager.getInstance(applicationContext).let { mgr ->
            CameraWidgetProvider().onUpdate(applicationContext, mgr, intArrayOf(appWidgetId))
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        finish()
    }
}
