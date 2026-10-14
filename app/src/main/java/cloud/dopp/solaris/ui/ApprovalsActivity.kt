package cloud.dopp.solaris.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.SbApiClient
import cloud.dopp.solaris.data.SbApproval
import cloud.dopp.solaris.widget.PwaLauncher
import kotlin.concurrent.thread

/**
 * In-app list of pending ServiceBay approvals (#43), read from Solaris'
 * aggregated `/napi/box/approvals`. Read-only: each row opens the PWA to
 * actually approve/reject, because the verdict needs the admin's Authelia session
 * (only the Custom Tab has it) — a device-token verdict is forbidden by design
 * (#2249). Reached from the ServiceBay overview widget's approvals count and
 * (once built) from the approval notification.
 */
class ApprovalsActivity : AppCompatActivity() {

    private lateinit var listView: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_approvals)
        applyStatusBarInset()
        listView = findViewById(R.id.approvals_list)
        status = findViewById(R.id.approvals_status)
        findViewById<TextView>(R.id.approvals_refresh).setOnClickListener { load() }
        load()
    }

    /**
     * Keep the "Freigaben" heading below the status bar on edge-to-edge (targetSdk
     * 35): add the status-bar top inset onto the header's base padding so the title
     * never collides with the clock (same fix as #19 for the config screen).
     */
    private fun applyStatusBarInset() {
        val root = findViewById<View>(R.id.approvals_root)
        val basePad = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = basePad + bars.top, bottom = bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from acting in the PWA should reflect the new state.
        load()
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = getString(R.string.approvals_loading)
        thread {
            val approvals = try {
                SbApiClient(applicationContext).listApprovals()
            } catch (e: Exception) {
                null
            }
            runOnUiThread { render(approvals) }
        }
    }

    private fun render(approvals: List<SbApproval>?) {
        // Clear every row except the status line (the first child).
        if (listView.childCount > 1) listView.removeViews(1, listView.childCount - 1)
        when {
            approvals == null -> {
                status.visibility = View.VISIBLE
                status.text = getString(R.string.approvals_error)
                return
            }
            approvals.isEmpty() -> {
                status.visibility = View.VISIBLE
                status.text = getString(R.string.approvals_empty)
                return
            }
        }
        status.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        approvals!!.forEach { a ->
            val row = inflater.inflate(R.layout.item_approval_row, listView, false)
            row.findViewById<TextView>(R.id.ap_title).text = a.title.ifBlank { a.id }
            row.findViewById<TextView>(R.id.ap_service).text = a.service
            row.findViewById<TextView>(R.id.ap_desc).apply {
                text = a.description
                visibility = if (a.description.isBlank()) View.GONE else View.VISIBLE
            }
            row.setOnClickListener {
                // Open this approval's verdict page in the Custom Tab (Authelia
                // session lives there; device token can't POST the verdict, #2249).
                PwaLauncher.open(this, PwaLauncher.Routes.approval(a.id))
            }
            listView.addView(row)
        }
    }
}
