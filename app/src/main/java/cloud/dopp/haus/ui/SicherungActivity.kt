package cloud.dopp.haus.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cloud.dopp.haus.R
import cloud.dopp.haus.data.Napi.Quelle
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.SicherungClient
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.sicherung.Anzeige
import cloud.dopp.haus.sicherung.Auswahl
import cloud.dopp.haus.sicherung.Lauf
import cloud.dopp.haus.sicherung.Meldung
import cloud.dopp.haus.sicherung.SafOrdner
import cloud.dopp.haus.sicherung.SicherungStore
import cloud.dopp.haus.sicherung.SicherungWorker
import cloud.dopp.haus.sicherung.Zustand
import kotlin.concurrent.thread

/**
 * Mehr → Nachrichten sichern (#194, laib ADR 0029). The person grants two
 * folders through the system picker — WhatsApp's
 * (`Android/media/com.whatsapp/WhatsApp`, suggested) and the one they chose
 * in Signal — and sees per source whether the folder is granted, when a file
 * last went over, what Haus reports (`/napi/sicherung/stand`) and the last
 * word as a German sentence with the next step. „Jetzt sichern“ starts a run
 * at once and shows its progress. The guide is on the screen itself.
 *
 * No key is asked for or stored here: the keys go into the person's vault
 * on the box (laib#986).
 */
class SicherungActivity : AppCompatActivity() {

    private lateinit var store: SicherungStore

    /** What Haus last reported; null while it is read. */
    @Volatile
    private var stand: SicherungClient.Stand? = null

    private val waehleWhatsapp = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { gewaehlt(Quelle.WHATSAPP, it) }
    }

    private val waehleSignal = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { gewaehlt(Quelle.SIGNAL, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sicherung)
        store = SicherungStore(this)
        applySystemBarInsets()
        karte(Quelle.WHATSAPP).findViewById<TextView>(R.id.quelle_name).setText(R.string.sicherung_whatsapp)
        karte(Quelle.SIGNAL).findViewById<TextView>(R.id.quelle_name).setText(R.string.sicherung_signal)
        Lauf.QUELLEN.forEach { q ->
            karte(q).findViewById<Button>(R.id.quelle_waehlen).setOnClickListener { waehle(q) }
            karte(q).findViewById<TextView>(R.id.quelle_meldung).setOnClickListener { if (ordnerFehlt(q)) waehle(q) }
        }
        findViewById<Button>(R.id.sicherung_jetzt).setOnClickListener { jetzt() }
        SicherungWorker.beobachten(this, this, ::zeige)
    }

    override fun onResume() {
        super.onResume()
        render()
        ladeStand()
    }

    private fun karte(q: Quelle): View =
        findViewById(if (q == Quelle.SIGNAL) R.id.sicherung_signal else R.id.sicherung_whatsapp)

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.sicherung_root)
        val top = root.paddingTop
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, top + bars.top, v.paddingRight, bottom + bars.bottom)
            insets
        }
    }

    // --- the folders ----------------------------------------------------------

    private fun freigegeben(q: Quelle): Boolean = store.ordner(q)?.let { SafOrdner.freigegeben(this, it) } == true

    /** A folder was chosen once and its grant is gone: the tap on the sentence opens the picker. */
    private fun ordnerFehlt(q: Quelle): Boolean =
        (store.ordner(q) != null && !freigegeben(q)) || store.meldung(q)?.art == Meldung.Art.ORDNER

    private fun waehle(q: Quelle) {
        try {
            if (q == Quelle.WHATSAPP) waehleWhatsapp.launch(SafOrdner.WHATSAPP_START) else waehleSignal.launch(store.ordner(q))
        } catch (e: Exception) {
            zustand(getString(R.string.sicherung_kein_picker))
        }
    }

    /** Keep the grant, check WhatsApp's folder is WhatsApp's, give back the folder chosen before. */
    private fun gewaehlt(q: Quelle, baum: Uri) {
        if (!SafOrdner.behalten(this, baum)) {
            zustand(getString(R.string.sicherung_ordner_verweigert))
            return
        }
        val app = applicationContext
        thread {
            val passt = q != Quelle.WHATSAPP || SafOrdner(app, baum).let { o ->
                o.hat(Auswahl.DATENBANKEN) || o.hat(Auswahl.MEDIEN)
            }
            runOnUiThread {
                if (!passt) {
                    runCatching { contentResolver.releasePersistableUriPermission(baum, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    zustand(getString(R.string.sicherung_ordner_falsch))
                    return@runOnUiThread
                }
                val vorher = store.ordner(q)
                if (vorher != null && vorher != baum) {
                    runCatching { contentResolver.releasePersistableUriPermission(vorher, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                store.setzeOrdner(q, baum)
                SicherungWorker.planen(app)
                if (!isFinishing) render()
            }
        }
    }

    // --- the state ------------------------------------------------------------

    private fun render() {
        Lauf.QUELLEN.forEach(::renderQuelle)
        val s = stand
        findViewById<TextView>(R.id.sicherung_frei).text = when {
            !gekoppelt() -> Lauf.NICHT_GEKOPPELT
            s is SicherungClient.Stand.Da -> s.freiBytes?.let { getString(R.string.sicherung_box_frei, Anzeige.groesse(it)) }.orEmpty()
            s is SicherungClient.Stand.Fehler -> s.satz
            s is SicherungClient.Stand.NichtGekoppelt -> Lauf.NICHT_GEKOPPELT
            else -> ""
        }
    }

    private fun renderQuelle(q: Quelle) {
        val k = karte(q)
        val frei = freigegeben(q)
        k.findViewById<TextView>(R.id.quelle_ordner).setText(if (frei) R.string.sicherung_ordner_ja else R.string.sicherung_ordner_nein)
        k.findViewById<Button>(R.id.quelle_waehlen).setText(
            if (store.ordner(q) == null) R.string.sicherung_ordner_waehlen else R.string.sicherung_ordner_neu,
        )
        k.findViewById<TextView>(R.id.quelle_zuletzt).text = store.uebertragen(q)?.let { (zeit, n) ->
            resources.getQuantityString(R.plurals.sicherung_zuletzt, n, Anzeige.zeit(zeit), n)
        } ?: getString(R.string.sicherung_zuletzt_nie)
        val box = k.findViewById<TextView>(R.id.quelle_box)
        val boxText = boxZeile(q)
        box.text = boxText
        box.visibility = if (boxText.isEmpty()) View.GONE else View.VISIBLE

        val m = store.meldung(q)
        val satz = when {
            store.ordner(q) != null && !frei -> if (q == Quelle.SIGNAL) Lauf.ORDNER_SIGNAL else Lauf.ORDNER_WHATSAPP
            m == null -> null
            m.art == Meldung.Art.OK -> getString(R.string.sicherung_geprueft, Anzeige.zeit(m.zeit))
            else -> m.satz
        }
        val meldung = k.findViewById<TextView>(R.id.quelle_meldung)
        meldung.text = satz.orEmpty()
        meldung.visibility = if (satz == null) View.GONE else View.VISIBLE
        val ruhig = m?.art == Meldung.Art.OK && frei
        meldung.setTextColor(getColor(if (ruhig) R.color.haus_text_muted else R.color.haus_accent))
    }

    /** What Haus reports for [q]. */
    private fun boxZeile(q: Quelle): String = when (val s = stand) {
        null -> if (gekoppelt()) getString(R.string.sicherung_box_laedt) else ""
        is SicherungClient.Stand.Da -> {
            val qs = s.quellen[q]
            if (qs == null || (qs.dateien == 0L && qs.zuletzt == null)) {
                getString(R.string.sicherung_box_nie)
            } else {
                val zuletzt = Anzeige.rfc3339(qs.zuletzt)?.let { Anzeige.zeit(it) } ?: qs.zuletzt.orEmpty()
                val zeile = resources.getQuantityString(R.plurals.sicherung_box, qs.dateien.toInt(), qs.dateien.toInt(), Anzeige.groesse(qs.bytes), zuletzt)
                if (qs.wartend > 0) zeile + "\n" + resources.getQuantityString(R.plurals.sicherung_box_wartend, qs.wartend.toInt(), qs.wartend.toInt()) else zeile
            }
        }
        SicherungClient.Stand.Unerreichbar -> getString(R.string.sicherung_box_unerreichbar)
        else -> "" // Haus's sentence stands once, above the cards
    }

    private fun gekoppelt(): Boolean = ServerStore.isConfigured(this) && TokenStore.isPaired(this)

    /** Ask Haus what reached the box; the screen shows it as it comes. */
    private fun ladeStand() {
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base == null || token.isNullOrBlank()) return
        thread {
            val s = SicherungClient(base, token).stand()
            runOnUiThread {
                stand = s
                if (!isFinishing) render()
            }
        }
    }

    // --- „Jetzt sichern“ ------------------------------------------------------

    private fun jetzt() {
        if (Lauf.QUELLEN.none(::freigegeben)) {
            zustand(getString(R.string.sicherung_jetzt_ohne_ordner))
            return
        }
        SicherungWorker.jetzt(applicationContext)
        zustand(getString(R.string.sicherung_jetzt_angestossen))
    }

    private var lief = false

    private fun zeige(z: Zustand) {
        val balken = findViewById<ProgressBar>(R.id.sicherung_balken)
        when (z) {
            is Zustand.Laeuft -> {
                lief = true
                val f = z.fortschritt
                if (f == null || f.gesamt <= 0) {
                    balken.isIndeterminate = true
                    zustand(getString(R.string.sicherung_laeuft))
                } else {
                    balken.isIndeterminate = false
                    balken.progress = (f.gesendet * 1000 / f.gesamt).toInt()
                    val quelle = getString(if (f.quelle == Quelle.SIGNAL) R.string.sicherung_signal else R.string.sicherung_whatsapp)
                    zustand(
                        getString(
                            R.string.sicherung_laeuft_datei, quelle, f.name, f.nr, f.von,
                            Anzeige.groesse(f.gesendet), Anzeige.groesse(f.gesamt),
                        ),
                    )
                }
                balken.visibility = View.VISIBLE
            }
            Zustand.Wartet -> {
                balken.visibility = View.GONE
                zustand(getString(R.string.sicherung_wartet))
            }
            Zustand.Ruht -> {
                balken.visibility = View.GONE
                zustand(getString(R.string.sicherung_ruht))
                if (lief) {
                    lief = false
                    render()
                    ladeStand()
                }
            }
        }
    }

    private fun zustand(text: String) {
        findViewById<TextView>(R.id.sicherung_zustand).text = text
    }
}
