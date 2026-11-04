package cloud.dopp.homes.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import cloud.dopp.homes.R
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.StandortImportClient
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.standort.Import
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone
import cloud.dopp.homes.widget.HomesPages
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.text.NumberFormat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Share → „In den Standortverlauf übernehmen“ (owner 2026-11-03): an old
 * location history — Google Maps' Timeline.json, a Takeout Records.json, a
 * GPX or GeoJSON file — goes to Homes, which brings it into the person's
 * Reitti with their own token: no browser, no Reitti login ([Import]).
 *
 * The one dialog of the app says each step as it goes: the upload in per
 * cent and MB, then how much Homes has read and how many places Reitti has,
 * and at the end Homes' sentence („12.345 Orte von März 2014 bis Dezember
 * 2024 übernommen …“) or why not — with „Reitti verbinden“ when the person
 * has not connected Reitti yet. „Abbrechen“ stops the upload (shared again,
 * it goes on where it stopped); once the file is on the box, „Schließen“
 * leaves the import to the box. Nothing runs after the dialog is gone.
 */
class StandortImportActivity : Activity() {

    private var dialog: AlertDialog? = null

    @Volatile
    private var halt = false

    @Volatile
    private var aufDerBox = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActionDialog.scrim(this)
        val uri = sharedUri(intent)
        if (intent?.action != Intent.ACTION_SEND || uri == null) {
            finish()
            return
        }
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            said(getString(R.string.standort_import_nicht), getString(R.string.share_not_paired))
            return
        }
        val (name, groesse) = beschreibe(contentResolver, uri)
        val typ = contentResolver.getType(uri) ?: intent.type
        if (!Import.nimmt(name, typ)) {
            said(getString(R.string.standort_import_nicht), getString(R.string.standort_import_falsch))
            return
        }
        dialog = ActionDialog.show(
            activity = this, title = getString(R.string.standort_import_title), message = getString(R.string.standort_import_beginnt),
            sheet = ActionSheet(emptyList()), onPick = {}, onCancel = { anhalten() },
            footer = getString(R.string.standort_import_abbrechen), cancelable = false,
        )
        val resolver = contentResolver
        thread {
            var kopie: File? = null
            val ausgang = try {
                // a provider that does not say the size: a copy says it (and is read from then on)
                val (laenge, oeffnen) = if (groesse >= 0) {
                    groesse to { ab: Long -> vonAn(resolver.openInputStream(uri) ?: throw IOException("no stream"), ab) }
                } else {
                    val f = File(cacheDir, "standort-import").also { kopie = it }
                    resolver.openInputStream(uri)?.use { ein -> f.outputStream().use { ein.copyTo(it) } } ?: throw IOException("no stream")
                    f.length() to { ab: Long -> vonAn(FileInputStream(f), ab) }
                }
                Import(StandortImportClient(base, token)).uebernehmen(
                    name = Import.dateiname(name, typ),
                    groesse = laenge,
                    oeffnen = oeffnen,
                    fortschritt = { f -> runOnUiThread { zeige(f) } },
                    halt = { halt },
                )
            } catch (e: IOException) {
                Import.Ausgang.Fehler(getString(R.string.standort_import_unlesbar), null)
            } finally {
                kopie?.delete()
            }
            runOnUiThread { if (!isFinishing) ende(ausgang) }
        }
    }

    /** The waiting dialog's line for [f]; from the import on, the footer says „Schließen“. */
    private fun zeige(f: Import.Fortschritt) {
        val d = dialog ?: return
        val text = when (f) {
            is Import.Fortschritt.Hochladen ->
                getString(R.string.standort_import_hoch, prozent(f.gesendet, f.groesse), mb(f.gesendet), mb(f.groesse))
            is Import.Fortschritt.Uebernehmen -> {
                if (!aufDerBox) {
                    aufDerBox = true
                    d.findViewById<Button>(R.id.ad_cancel)?.text = getString(R.string.standort_import_schliessen)
                }
                getString(R.string.standort_import_liest, prozent(f.gelesen, f.groesse), zahl(f.punkte))
            }
        }
        d.findViewById<TextView>(R.id.ad_message)?.text = text
    }

    /** Abbrechen during the upload, Schließen once the box imports: the thread stops at its next step. */
    private fun anhalten() {
        halt = true
        dialog = null
        if (aufDerBox) {
            said(getString(R.string.standort_import_title), getString(R.string.standort_import_im_hintergrund))
        } else {
            said(getString(R.string.standort_import_title), getString(R.string.standort_import_angehalten))
        }
    }

    private fun ende(a: Import.Ausgang) {
        if (halt) return // the person closed it; that dialog says what goes on
        dialog?.dismiss()
        dialog = null
        when (a) {
            is Import.Ausgang.Fertig -> said(getString(R.string.standort_import_fertig), a.text)
            is Import.Ausgang.Fehler -> said(
                getString(R.string.standort_import_nicht),
                a.satz ?: getString(R.string.standort_import_abgelehnt),
                verbinden = a.grund == Napi.GRUND_KEIN_SCHLUESSEL || a.grund == Napi.GRUND_SCHLUESSEL_ABGELEHNT,
            )
            Import.Ausgang.NichtGekoppelt -> said(getString(R.string.standort_import_nicht), getString(R.string.share_not_paired))
            Import.Ausgang.Unerreichbar -> said(getString(R.string.standort_import_nicht), getString(R.string.standort_import_unerreichbar))
            Import.Ausgang.Still -> said(getString(R.string.standort_import_title), getString(R.string.standort_import_still))
            Import.Ausgang.Angehalten -> said(getString(R.string.standort_import_title), getString(R.string.standort_import_angehalten))
        }
    }

    /** What came of it, in the one dialog; [verbinden] adds „Reitti verbinden“ (Homes' Standortverlauf page). */
    private fun said(title: String, message: String, verbinden: Boolean = false) {
        val sheet = ActionSheet(listOfNotNull(if (verbinden) ActionItem(R.string.standort_import_verbinden, ActionTone.PRIMARY) else null))
        ActionDialog.show(
            activity = this, title = title, message = message, sheet = sheet,
            onPick = {
                runCatching { startActivity(HomesPages.intent(this, HomesPages.STANDORT)) }
                finish()
            },
            onCancel = { finish() },
            footer = getString(R.string.share_done),
        )
    }

    companion object {
        /** The file shared ([Intent.EXTRA_STREAM]), or null. */
        @Suppress("DEPRECATION")
        fun sharedUri(intent: Intent?): Uri? = when {
            intent == null -> null
            Build.VERSION.SDK_INT >= 33 -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } ?: intent?.data

        /** The shared file's name and size as its provider says them; -1 when it does not say the size. */
        fun beschreibe(resolver: ContentResolver, uri: Uri): Pair<String?, Long> {
            var name: String? = null
            var groesse = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) groesse = c.getLong(s)
                    }
                }
            }
            if (name == null && uri.scheme == ContentResolver.SCHEME_FILE) {
                name = uri.lastPathSegment
                groesse = uri.path?.let { File(it).takeIf { f -> f.isFile }?.length() } ?: -1L
            }
            return name to groesse
        }

        /** [s] from byte [n] on. */
        private fun vonAn(s: InputStream, n: Long): InputStream {
            if (n > 0 && !Import.ueberspringe(s, n)) {
                s.close()
                throw IOException("shorter than $n")
            }
            return s
        }

        fun prozent(teil: Long, ganz: Long): Int = if (ganz <= 0) 0 else ((teil * 100) / ganz).toInt().coerceIn(0, 100)

        /** Bytes as MB, one decimal, the German way: „12,3“. */
        fun mb(bytes: Long): String = String.format(Locale.GERMANY, "%.1f", bytes / 1_048_576.0)

        /** A count the German way: „12.345“. */
        fun zahl(n: Long): String = NumberFormat.getIntegerInstance(Locale.GERMANY).format(n)
    }
}
