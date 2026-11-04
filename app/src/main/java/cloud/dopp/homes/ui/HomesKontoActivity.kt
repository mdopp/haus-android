package cloud.dopp.homes.ui

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.SyncStatusObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.HomesKonto.Teil
import cloud.dopp.homes.konto.KontoStore
import cloud.dopp.homes.sicherung.Anzeige
import cloud.dopp.homes.sicherung.Erlaubnis
import cloud.dopp.homes.sicherung.Lauf
import kotlin.concurrent.thread

/**
 * Mehr → Homes auf dem Handy (#206, the owner's call of 2026-11-03): the Homes
 * account's two switches, „Kontakte des Hauses“ and „Kalender des Hauses“,
 * each asking for its own permission when turned on. Under each its last
 * good run or what went wrong; „Jetzt abgleichen“ asks Android to sync now
 * and the line under it follows the run until it ends. Read only, it says so
 * at the top; the last line says which way the person's own data goes.
 */
class HomesKontoActivity : AppCompatActivity() {

    private lateinit var store: KontoStore
    private var setzt = false

    /** The part whose permission is being asked for, and since when. */
    private var fragt: Teil? = null
    private var gefragt = 0L

    /** Why a switch stayed off, until the next try; [appInfo]: a tap opens the app's settings. */
    private class Hinweis(val satz: String, val appInfo: Boolean)

    private val hinweise = HashMap<Teil, Hinweis>()

    /** While a run the person started is under way: since when. */
    private var laeuftSeit = 0L
    private var beobachter: Any? = null
    private val haupt = Handler(Looper.getMainLooper())

    private val erlaube = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { beantwortet() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_homes_konto)
        store = KontoStore(this)
        padForSystemBars(findViewById(R.id.konto_root))
        Teil.values().forEach { teil ->
            schalter(teil).setOnCheckedChangeListener { _, an -> if (!setzt) schalte(teil, an) }
            stand(teil).setOnClickListener { if (hinweise[teil]?.appInfo == true) appInfo() }
        }
        findViewById<Button>(R.id.konto_jetzt).setOnClickListener { jetzt() }
    }

    override fun onResume() {
        super.onResume()
        // Android says when a run starts or ends; the line also looks once a second while one runs
        beobachter = runCatching {
            ContentResolver.addStatusChangeListener(
                ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE or ContentResolver.SYNC_OBSERVER_TYPE_PENDING,
                SyncStatusObserver { haupt.post { if (!isFinishing) folge() } },
            )
        }.getOrNull()
        render()
        folge()
    }

    override fun onPause() {
        beobachter?.let { runCatching { ContentResolver.removeStatusChangeListener(it) } }
        beobachter = null
        haupt.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun schalter(teil: Teil): SwitchCompat =
        findViewById(if (teil == Teil.KONTAKTE) R.id.konto_kontakte_an else R.id.konto_kalender_an)

    private fun stand(teil: Teil): TextView =
        findViewById(if (teil == Teil.KONTAKTE) R.id.konto_kontakte_stand else R.id.konto_kalender_stand)

    private fun rechte(teil: Teil): Array<String> = when (teil) {
        Teil.KONTAKTE -> arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        Teil.KALENDER -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    private fun an(teil: Teil): Boolean = HomesKonto.an(this, teil) && Erlaubnis.hat(this, rechte(teil))

    // --- the switches -----------------------------------------------------------

    private fun schalte(teil: Teil, an: Boolean) {
        hinweise.remove(teil)
        if (!an) {
            val app = applicationContext
            thread { // its entries leave the provider: not on the main thread
                HomesKonto.ausschalten(app, teil)
                runOnUiThread { if (!isFinishing) render() }
            }
            return
        }
        if (Erlaubnis.hat(this, rechte(teil))) {
            eingeschaltet(teil)
            return
        }
        fragt = teil
        gefragt = SystemClock.elapsedRealtime()
        try {
            erlaube.launch(rechte(teil))
        } catch (e: Exception) {
            beantwortet()
        }
    }

    private fun beantwortet() {
        val teil = fragt ?: return
        fragt = null
        if (Erlaubnis.hat(this, rechte(teil))) {
            eingeschaltet(teil)
            return
        }
        val ohneDialog = SystemClock.elapsedRealtime() - gefragt < OHNE_DIALOG_MS &&
            rechte(teil).none { shouldShowRequestPermissionRationale(it) }
        hinweise[teil] = if (ohneDialog) {
            Hinweis(getString(R.string.konto_einstellungen), appInfo = true)
        } else {
            Hinweis(getString(R.string.konto_abgelehnt), appInfo = false)
        }
        render()
    }

    private fun eingeschaltet(teil: Teil) {
        HomesKonto.einschalten(applicationContext, teil)
        laeuftSeit = SystemClock.elapsedRealtime()
        render()
        folge()
    }

    private fun appInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) {
            // no settings screen: the sentence stands
        }
    }

    // --- Jetzt abgleichen ---------------------------------------------------------

    private fun jetzt() {
        val teile = Teil.values().filter(::an)
        if (teile.isEmpty()) {
            zustand(getString(R.string.konto_nichts_an))
            return
        }
        teile.forEach(HomesKonto::jetzt)
        laeuftSeit = SystemClock.elapsedRealtime()
        folge()
    }

    /**
     * The line under the button while a run the person started is under way:
     * „Gleicht ab …“ until Android's sync of every part on is done, then the
     * outcome; past [GEDULD_MS] without a start, that Android will do it later.
     */
    private fun folge() {
        if (laeuftSeit == 0L) {
            zustand(grundsatz())
            return
        }
        val aktiv = Teil.values().filter(::an).any {
            ContentResolver.isSyncActive(HomesKonto.konto, it.authority) || ContentResolver.isSyncPending(HomesKonto.konto, it.authority)
        }
        val vergangen = SystemClock.elapsedRealtime() - laeuftSeit
        when {
            aktiv && vergangen < GEDULD_MS -> {
                findViewById<Button>(R.id.konto_jetzt).isEnabled = false
                zustand(getString(R.string.konto_gleicht_ab))
                haupt.removeCallbacksAndMessages(null)
                haupt.postDelayed({ if (!isFinishing) folge() }, minOf(1_000L, GEDULD_MS - vergangen))
            }
            aktiv -> {
                laeuftSeit = 0L
                findViewById<Button>(R.id.konto_jetzt).isEnabled = true
                zustand(getString(R.string.konto_dauert))
            }
            vergangen < START_MS -> { // asked, not begun yet: Android takes a moment
                findViewById<Button>(R.id.konto_jetzt).isEnabled = false
                zustand(getString(R.string.konto_gleicht_ab))
                haupt.removeCallbacksAndMessages(null)
                haupt.postDelayed({ if (!isFinishing) folge() }, 500)
            }
            else -> {
                laeuftSeit = 0L
                findViewById<Button>(R.id.konto_jetzt).isEnabled = true
                val fehler = Teil.values().filter(::an).mapNotNull { store.meldung(it) }
                zustand(if (fehler.isEmpty()) getString(R.string.konto_fertig) else fehler.joinToString("\n"))
                render()
            }
        }
    }

    /** What the line says while nothing runs: how Android syncs, or that it does not by itself. */
    private fun grundsatz(): String = when {
        !TokenStore.isPaired(this) -> Lauf.NICHT_GEKOPPELT
        Teil.values().none(::an) -> ""
        !HomesKonto.automatisch() -> getString(R.string.konto_aus_automatisch)
        else -> getString(R.string.konto_von_selbst)
    }

    // --- the state --------------------------------------------------------------

    private fun render() {
        val gekoppelt = TokenStore.isPaired(this)
        setzt = true
        Teil.values().forEach { teil ->
            schalter(teil).isChecked = an(teil)
            schalter(teil).isEnabled = gekoppelt
        }
        setzt = false
        Teil.values().forEach { teil ->
            val satz = hinweise[teil]?.satz ?: when {
                HomesKonto.an(this, teil) && !Erlaubnis.hat(this, rechte(teil)) -> getString(R.string.konto_einstellungen)
                !an(teil) -> null
                store.meldung(teil) != null -> store.meldung(teil)
                else -> store.stand(teil)?.let { s ->
                    getString(
                        if (teil == Teil.KONTAKTE) R.string.konto_kontakte_stand else R.string.konto_kalender_stand,
                        Anzeige.zeit(s.zeit), s.anzahl,
                    )
                } ?: getString(R.string.konto_noch_nie)
            }
            if (HomesKonto.an(this, teil) && !Erlaubnis.hat(this, rechte(teil))) {
                hinweise[teil] = Hinweis(getString(R.string.konto_einstellungen), appInfo = true)
            }
            val v = stand(teil)
            v.text = satz.orEmpty()
            v.visibility = if (satz.isNullOrEmpty()) View.GONE else View.VISIBLE
            v.setTextColor(getColor(if (hinweise[teil] != null || store.meldung(teil) != null) R.color.homes_accent else R.color.homes_text_muted))
        }
        findViewById<Button>(R.id.konto_jetzt).isEnabled = gekoppelt && laeuftSeit == 0L
        if (laeuftSeit == 0L) zustand(grundsatz())
    }

    private fun zustand(text: String) {
        val v = findViewById<TextView>(R.id.konto_zustand)
        v.text = text
        v.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private companion object {
        /** Faster than a person can read a dialog: Android answered without one. */
        const val OHNE_DIALOG_MS = 700L

        /** How long the line waits for Android to start a run it was asked for. */
        const val START_MS = 5_000L

        /** How long it follows a run before it says Android will finish it later. */
        const val GEDULD_MS = 60_000L
    }
}
