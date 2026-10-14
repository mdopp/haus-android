package cloud.dopp.solaris.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisApp
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.SbApiClient
import cloud.dopp.solaris.data.SbHome
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.realtime.RealtimeLog
import cloud.dopp.solaris.realtime.RealtimePoll
import java.io.File
import kotlin.concurrent.thread

/**
 * A no-frills diagnostics screen (#48): app version + device, the last crash trace
 * (if any, from [SolarisApp.CRASH_FILE]), and the realtime connection log
 * ([RealtimeLog]) — so "app keeps stopping" or reconnect churn is inspectable and
 * shareable without adb. Reached by tapping the version line on the home screen —
 * deliberately discreet, this is a service hatch, not a feature.
 *
 * Since #110 it also holds the **notification self-test** ([runNotifyCheck]): the
 * one way to see the #45 alert path end-to-end without an update pending on the box.
 */
class DiagnosticsActivity : AppCompatActivity() {

    /** Result of the last "Meldungen prüfen" run (#110), shown above the log. */
    private var lastCheck: String? = null

    /**
     * Which resident the server thinks this phone is (#160), or a reason it could
     * not be asked. Fetched once per open, off the main thread.
     */
    private var whoami: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        render()
        loadWhoami()
        findViewById<Button>(R.id.diag_copy).setOnClickListener {
            val text = findViewById<TextView>(R.id.diag_text).text.toString()
            runCatching {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Solaris Diagnose", text))
            }
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.diag_clear).setOnClickListener {
            RealtimeLog.clear(this)
            runCatching { File(filesDir, SolarisApp.CRASH_FILE).delete() }
            lastCheck = null
            render()
        }
        findViewById<Button>(R.id.diag_notify_check).setOnClickListener { runNotifyCheck(it as Button) }
    }

    /**
     * The notification self-test (#110): fetch `/napi/box/home` **once**,
     * bypassing the screen-off + rise gate that makes #45 unprovokable, report the
     * counts and the tap target, deliver one marked test alert per kind through the
     * real builders, and drop the persisted baseline so a standing count alerts
     * again. Nothing about the trigger condition itself changes.
     */
    private fun runNotifyCheck(btn: Button) {
        btn.isEnabled = false
        btn.setText(R.string.diag_notify_running)
        val app = applicationContext
        thread {
            val base = ServerStore.baseUrl(app)
            var home: SbHome? = null
            var error: String? = null
            try {
                home = SbApiClient(app).getHome()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            if (home == null && error == null) error = "keine Antwort vom Server"
            RealtimePoll.notifyTest(app, home?.pendingApprovals ?: 0, home?.pendingUpdates ?: 0)
            RealtimePoll.resetBaseline(app)
            val enabled = runCatching {
                NotificationManagerCompat.from(app).areNotificationsEnabled()
            }.getOrDefault(true)
            val text = NotifyCheckReport.format(
                base, home?.pendingApprovals, home?.pendingUpdates, error, enabled,
            )
            runOnUiThread {
                lastCheck = text
                render()
                btn.setText(R.string.diag_notify_check)
                btn.isEnabled = true
            }
        }
    }

    private fun render() {
        findViewById<TextView>(R.id.diag_text).text = buildText()
    }

    /**
     * Ask the server who it thinks we are (#160). Off-thread, best effort: an
     * unreachable server is itself a finding and is written into the line rather
     * than left blank.
     */
    private fun loadWhoami() {
        thread {
            val uid = runCatching { ApiClient(applicationContext).whoamiUid() }.getOrNull()
            runOnUiThread {
                whoami = uid ?: "unbekannt (Server nicht erreichbar oder nicht gekoppelt)"
                runCatching { render() }
            }
        }
    }

    private fun buildText(): String {
        val sb = StringBuilder()
        sb.append("Version ").append(appVersion())
            .append("  ·  ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append("  ·  Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n")
        sb.append("Server sieht dieses Telefon als: ")
            .append(whoami ?: "wird abgefragt …").append('\n')
            .append("(Eine Meldung an einen anderen Bewohner erreicht dieses Telefon nicht.)\n\n")
        lastCheck?.let { sb.append(it).append("\n\n") }
        readCrash()?.let { sb.append("── Letzter Absturz ──\n").append(it.trim()).append("\n\n") }
        sb.append("── Verbindungs-Log ──\n")
        val log = RealtimeLog.formatted(this)
        sb.append(if (log.isBlank()) "(noch nichts)" else log)
        return sb.toString()
    }

    private fun readCrash(): String? = runCatching {
        val f = File(filesDir, SolarisApp.CRASH_FILE)
        if (f.exists()) f.readText() else null
    }.getOrNull()

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
