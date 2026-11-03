package cloud.dopp.haus.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
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
import cloud.dopp.haus.sicherung.Lauf
import cloud.dopp.haus.sicherung.RueckStore
import cloud.dopp.haus.sicherung.Rueckholer
import cloud.dopp.haus.sicherung.Rueckholung
import cloud.dopp.haus.sicherung.SafOrdner
import cloud.dopp.haus.sicherung.SafZiel
import cloud.dopp.haus.widget.ActionDialog
import cloud.dopp.haus.widget.ActionItem
import cloud.dopp.haus.widget.ActionSheet
import cloud.dopp.haus.widget.ActionTone
import kotlin.concurrent.thread

/**
 * Sicherung zurückholen (haus-android#197, haus#209): on a new phone where the
 * app is paired, the person's newest Signal and WhatsApp backup comes back
 * from the box.
 *
 * - Signal: the person picks a folder with write access (e.g. „Dokumente/Signal“);
 *   the newest `.backup` goes there, and the steps say how Signal restores it.
 * - WhatsApp: installed, not opened; the person grants
 *   `Android/media/com.whatsapp/WhatsApp` (or `Android/media` when it is not
 *   there yet); `Databases/msgstore.db.crypt15` and the media go there. The
 *   media come only while the archive is open: then a sentence and „Archiv öffnen“.
 *
 * The restore runs in [Rueckholer] while the screen stays on; „Anhalten“ and
 * „Weiter“ resume where the file stopped. Nothing already in a folder is
 * written over without the person's „Ersetzen“.
 */
class ZurueckholenActivity : AppCompatActivity() {

    private lateinit var store: RueckStore

    /** What the box has for a new phone; null while it is asked. */
    @Volatile
    private var letzte: SicherungClient.Letzte? = null

    /** While a source's folder is looked into before a run. */
    private var prueft: Quelle? = null

    private val waehleSignal = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { gewaehlt(Quelle.SIGNAL, it) }
    }

    private val waehleWhatsapp = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { gewaehlt(Quelle.WHATSAPP, it) }
    }

    /** The last run's end this screen reacted to: a newer backup on the box loads the list again, once. */
    private var gesehen: Rueckholung.Ausgang? = null

    private val zeige: (Rueckholer.Zustand) -> Unit = { z ->
        val a = (z as? Rueckholer.Zustand.Ruht)?.ausgang
        if (a != null && a !== gesehen) {
            gesehen = a
            if (a.art == Rueckholung.Art.VERALTET) ladeLetzte()
        }
        if (!isFinishing) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zurueckholen)
        store = RueckStore(this)
        applySystemBarInsets()
        findViewById<Button>(R.id.signal_waehlen).setOnClickListener { waehle(Quelle.SIGNAL) }
        findViewById<Button>(R.id.whatsapp_waehlen).setOnClickListener { waehle(Quelle.WHATSAPP) }
        findViewById<Button>(R.id.signal_holen).setOnClickListener { holen(Quelle.SIGNAL) }
        findViewById<Button>(R.id.whatsapp_holen).setOnClickListener { holen(Quelle.WHATSAPP) }
        findViewById<Button>(R.id.zurueck_anhalten).setOnClickListener { Rueckholer.anhalten() }
        findViewById<Button>(R.id.whatsapp_archiv).setOnClickListener { archivOeffnen() }
    }

    override fun onStart() {
        super.onStart()
        Rueckholer.anmelden(zeige)
    }

    override fun onStop() {
        Rueckholer.abmelden(zeige)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        render()
        ladeLetzte()
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.zurueck_root)
        val top = root.paddingTop
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, top + bars.top, v.paddingRight, bottom + bars.bottom)
            insets
        }
    }

    private fun client(): SicherungClient? {
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base.isNullOrBlank() || token.isNullOrBlank()) return null
        return SicherungClient(base, token)
    }

    /** Ask the box what lies ready; the cards show it as it comes. */
    private fun ladeLetzte() {
        val c = client() ?: run {
            letzte = SicherungClient.Letzte.NichtGekoppelt
            render()
            return
        }
        thread {
            val l = c.letzte()
            runOnUiThread {
                letzte = l
                if (!isFinishing) render()
            }
        }
    }

    private fun dateien(q: Quelle): List<SicherungClient.LetzteDatei> =
        (letzte as? SicherungClient.Letzte.Da)?.quellen?.get(q).orEmpty()

    // --- the folders ----------------------------------------------------------

    private fun waehle(q: Quelle) {
        try {
            if (q == Quelle.WHATSAPP) waehleWhatsapp.launch(store.ziel(q) ?: SafOrdner.WHATSAPP_START) else waehleSignal.launch(store.ziel(q))
        } catch (e: Exception) {
            meldung(q, getString(R.string.zurueck_kein_picker))
        }
    }

    private fun gewaehlt(q: Quelle, baum: Uri) {
        if (!SafZiel.behalten(this, baum)) {
            meldung(q, getString(R.string.zurueck_ordner_verweigert))
            return
        }
        if (q == Quelle.WHATSAPP && unter(baum) == null) {
            SafZiel.loslassen(this, baum)
            meldung(q, getString(R.string.zurueck_ordner_falsch))
            return
        }
        val vorher = store.ziel(q)
        if (vorher != null && vorher != baum) SafZiel.loslassen(this, vorher)
        store.setzeZiel(q, baum)
        meldung(q, null)
        render()
    }

    /** Where WhatsApp's files go below the granted folder ([Rueckholung.whatsappUnter]); "" for Signal's. */
    private fun unter(baum: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(baum) }.getOrNull()
        ?.let(Rueckholung::whatsappUnter)

    private fun freigegeben(q: Quelle): Boolean = store.ziel(q)?.let { SafZiel.freigegeben(this, it) } == true

    private fun ordnerZeile(q: Quelle): String {
        val baum = store.ziel(q) ?: return getString(R.string.zurueck_ordner_nein)
        val name = runCatching { Anzeige.ordner(DocumentsContract.getTreeDocumentId(baum)) }.getOrDefault(baum.toString())
        return getString(if (freigegeben(q)) R.string.zurueck_ordner_ja else R.string.zurueck_ordner_weg, name)
    }

    // --- the run --------------------------------------------------------------

    /** Look into the folder off the main thread; ask before anything would be replaced; then run. */
    private fun holen(q: Quelle) {
        val c = client() ?: return
        if (Rueckholer.laeuft()) {
            meldung(q, getString(R.string.zurueck_laeuft_schon))
            return
        }
        val baum = store.ziel(q)?.takeIf { freigegeben(q) } ?: run {
            meldung(q, getString(R.string.zurueck_erst_ordner))
            return
        }
        val dateien = dateien(q)
        if (dateien.isEmpty()) {
            meldung(q, getString(R.string.zurueck_nichts))
            return
        }
        val unter = if (q == Quelle.WHATSAPP) unter(baum) ?: return else ""
        prueft = q
        meldung(q, null)
        render()
        val app = applicationContext
        thread {
            val r = Rueckholung(c, store)
            val plan = runCatching { r.planen(Rueckholung.eintraege(q, dateien, unter), SafZiel(app, baum)) }.getOrNull()
            runOnUiThread {
                prueft = null
                when {
                    isFinishing -> Unit
                    plan == null -> {
                        meldung(q, Rueckholung.ORDNER_FEHLER)
                        render()
                    }
                    plan.konflikte.isEmpty() -> starten(c, q, plan, baum, emptySet())
                    else -> fragen(c, q, plan, baum)
                }
            }
        }
    }

    /** Files there already, not ours, another size: replace them, or keep them and fetch the rest. */
    private fun fragen(c: SicherungClient, q: Quelle, plan: Rueckholung.Plan, baum: Uri) {
        val n = plan.konflikte.size
        ActionDialog.show(
            activity = this,
            title = getString(R.string.zurueck_schon_da_titel),
            message = resources.getQuantityString(R.plurals.zurueck_schon_da, n, n, plan.konflikte.first().ziel.substringAfterLast('/')),
            sheet = ActionSheet(
                listOf(ActionItem(R.string.zurueck_ersetzen, ActionTone.PRIMARY), ActionItem(R.string.zurueck_behalten)),
            ),
            onPick = { i -> starten(c, q, plan, baum, if (i == 0) plan.konflikte.map { it.ziel }.toSet() else emptySet()) },
            onCancel = { render() },
        )
    }

    private fun starten(c: SicherungClient, q: Quelle, plan: Rueckholung.Plan, baum: Uri, ersetzen: Set<String>) {
        if (!Rueckholer.starten(this, c, q, plan, baum, ersetzen)) meldung(q, getString(R.string.zurueck_laeuft_schon))
        render()
    }

    // --- the screen -----------------------------------------------------------

    private val meldungen = HashMap<Quelle, String?>()

    private fun meldung(q: Quelle, satz: String?) {
        meldungen[q] = satz
        renderQuelle(q)
    }

    private fun render() {
        val l = letzte
        val haus = findViewById<TextView>(R.id.zurueck_haus)
        val hausSatz = when (l) {
            is SicherungClient.Letzte.Fehler -> l.satz
            SicherungClient.Letzte.NichtGekoppelt -> Lauf.NICHT_GEKOPPELT
            SicherungClient.Letzte.Unerreichbar -> getString(R.string.sicherung_box_unerreichbar)
            else -> null
        }
        haus.text = hausSatz.orEmpty()
        haus.visibility = if (hausSatz == null) View.GONE else View.VISIBLE
        renderQuelle(Quelle.SIGNAL)
        renderQuelle(Quelle.WHATSAPP)
        renderLauf()
    }

    private fun renderQuelle(q: Quelle) {
        val signal = q == Quelle.SIGNAL
        val bereit = findViewById<TextView>(if (signal) R.id.signal_bereit else R.id.whatsapp_bereit)
        bereit.text = bereitZeile(q)
        findViewById<TextView>(if (signal) R.id.signal_ordner else R.id.whatsapp_ordner).text = ordnerZeile(q)
        findViewById<Button>(if (signal) R.id.signal_waehlen else R.id.whatsapp_waehlen).setText(
            when {
                store.ziel(q) != null -> R.string.zurueck_ordner_neu
                signal -> R.string.zurueck_ordner_waehlen
                else -> R.string.zurueck_ordner_freigeben
            },
        )
        val z = Rueckholer.zustand
        val ausgang = (z as? Rueckholer.Zustand.Ruht)?.takeIf { it.quelle == q }?.ausgang
        val holen = findViewById<Button>(if (signal) R.id.signal_holen else R.id.whatsapp_holen)
        holen.isEnabled = !Rueckholer.laeuft() && prueft == null && dateien(q).isNotEmpty()
        holen.text = getString(
            when {
                prueft == q -> R.string.zurueck_prueft
                (z as? Rueckholer.Zustand.Laeuft)?.quelle == q -> R.string.zurueck_laeuft
                ausgang != null && ausgang.art != Rueckholung.Art.FERTIG -> R.string.zurueck_weiter
                else -> R.string.zurueck_holen
            },
        )
        val satz = meldungen[q] ?: ausgang?.let { satzFuer(q, it) }
        val m = findViewById<TextView>(if (signal) R.id.signal_meldung else R.id.whatsapp_meldung)
        m.text = satz.orEmpty()
        m.visibility = if (satz == null) View.GONE else View.VISIBLE
        if (!signal) {
            val archiv = ausgang?.medienSatz != null && archivAdresse() != null
            findViewById<Button>(R.id.whatsapp_archiv).visibility = if (archiv) View.VISIBLE else View.GONE
        }
    }

    /** What the box keeps for [q], with date and size. */
    private fun bereitZeile(q: Quelle): String {
        val l = letzte ?: return getString(R.string.zurueck_laedt)
        if (l !is SicherungClient.Letzte.Da) return ""
        val dateien = dateien(q)
        if (q == Quelle.SIGNAL) {
            val neueste = dateien.maxByOrNull { it.geaendert } ?: return getString(R.string.zurueck_signal_keine)
            return getString(R.string.zurueck_signal_bereit, Anzeige.zeit(neueste.geaendert), Anzeige.groesse(neueste.groesse))
        }
        val medien = dateien.filter { it.pfad.startsWith("Media/") }
        val db = dateien.firstOrNull { !it.pfad.startsWith("Media/") } ?: return getString(R.string.zurueck_whatsapp_keine)
        val zeile = getString(R.string.zurueck_whatsapp_bereit, Anzeige.zeit(db.geaendert), Anzeige.groesse(db.groesse))
        if (medien.isEmpty()) return zeile
        return zeile + " " + resources.getQuantityString(
            R.plurals.zurueck_whatsapp_medien, medien.size, Anzeige.zahl(medien.size), Anzeige.groesse(medien.sumOf { it.groesse }),
        )
    }

    /** The last run's word: done with the next step, or why not with what to do. */
    private fun satzFuer(q: Quelle, a: Rueckholung.Ausgang): String? = when (a.art) {
        Rueckholung.Art.FERTIG -> when {
            q == Quelle.SIGNAL -> getString(R.string.zurueck_signal_fertig)
            a.medienSatz != null -> getString(R.string.zurueck_whatsapp_fertig_ohne_medien) + " " + a.medienSatz
            else -> getString(R.string.zurueck_whatsapp_fertig)
        }
        Rueckholung.Art.ANGEHALTEN -> getString(R.string.zurueck_angehalten)
        else -> listOfNotNull(a.satz, a.medienSatz).joinToString(" ").ifBlank { null }
    }

    private fun renderLauf() {
        val z = Rueckholer.zustand
        val balken = findViewById<ProgressBar>(R.id.zurueck_balken)
        val zustand = findViewById<TextView>(R.id.zurueck_zustand)
        val anhalten = findViewById<Button>(R.id.zurueck_anhalten)
        if (z is Rueckholer.Zustand.Laeuft) {
            // a restore of GB takes a while: the screen stays on while it runs, never longer
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            anhalten.visibility = View.VISIBLE
            balken.visibility = View.VISIBLE
            val f = z.fortschritt
            if (f == null || f.gesamt <= 0) {
                balken.isIndeterminate = true
                zustand.setText(R.string.zurueck_laeuft)
            } else {
                balken.isIndeterminate = false
                balken.progress = (f.bytes.coerceIn(0, f.gesamt) * 1000 / f.gesamt).toInt()
                zustand.text = resources.getQuantityString(
                    R.plurals.zurueck_laeuft_gesamt, f.von,
                    getString(if (f.quelle == Quelle.SIGNAL) R.string.sicherung_signal else R.string.sicherung_whatsapp),
                    Anzeige.zahl(f.fertig), Anzeige.zahl(f.von), Anzeige.anteil(f.bytes, f.gesamt),
                )
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            anhalten.visibility = View.GONE
            balken.visibility = View.GONE
            zustand.text = ""
        }
    }

    /** The person's archive, where the vault opens: archiv.<domain> beside haus.<domain>. */
    private fun archivAdresse(): String? = ServerStore.baseUrl(this)?.let(Rueckholung::archivAdresse)

    private fun archivOeffnen() {
        val url = archivAdresse() ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }
}
