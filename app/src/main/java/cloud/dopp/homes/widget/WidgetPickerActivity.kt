package cloud.dopp.homes.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.HomeActivity

/**
 * What the two widget pickers share ([WidgetConfigActivity] for a device,
 * [ToolWidgetConfigActivity] for a tool): the instance they bind, the answer
 * to the host, the pair-first hint and the retry after pairing, the heading
 * below the status bar. A subclass fills the list ([load]) and, where the
 * person picks, binds, starts the live service and answers RESULT_OK.
 *
 * Re-entered from the launcher's *Einrichten* entry the instance is already
 * bound (#96): [bound] names what it shows, and backing out leaves it alone —
 * see [ConfigEntry].
 */
abstract class WidgetPickerActivity : AppCompatActivity() {

    protected var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        private set

    protected var entry = ConfigEntry.FIRST_PLACEMENT
        private set

    /** What this instance shows now (an entity or a tool id), or null for a fresh widget. */
    protected abstract fun bound(): String?

    /** Fill the list; the app is paired. */
    protected abstract fun load()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default result: if the user backs out, the host drops the widget.
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        entry = ConfigEntry.of(bound() != null)
        // Re-entered on a live widget: backing out must leave it bound, so the
        // armed answer is a no-op RESULT_OK instead of "drop it" (#96).
        setResult(entry.backOutResult, resultIntent())
        setContentView(R.layout.activity_widget_config)
        applyStatusBarInset()
        if (!paired()) {
            findViewById<TextView>(R.id.config_status).setText(R.string.widget_config_pair_first)
            findViewById<Button>(R.id.config_pair).apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(context, HomeActivity::class.java)) }
            }
            return
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from pairing — retry if now connected.
        if (paired() && findViewById<ListView>(R.id.config_list).adapter == null) {
            findViewById<Button>(R.id.config_pair).visibility = View.GONE
            load()
        }
    }

    private fun paired() = ServerStore.isConfigured(this) && TokenStore.isPaired(this)

    /**
     * Keep the heading below the system status bar on notch/punch-hole phones:
     * the status-bar inset on top of the layout's own padding (#19).
     */
    private fun applyStatusBarInset() {
        val root = findViewById<View>(R.id.config_root)
        val basePad = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            v.updatePadding(top = basePad + insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
    }

    protected fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}
