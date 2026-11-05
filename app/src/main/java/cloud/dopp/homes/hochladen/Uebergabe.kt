package cloud.dopp.homes.hochladen

import android.app.Activity
import android.net.Uri
import cloud.dopp.homes.R
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone
import java.io.File
import kotlin.concurrent.thread

/**
 * A share handed to the Hochladen job (owner, 2026-11-04): the one dialog
 * says „Bereitet … vor“ while the files are copied into the app's own
 * storage — the shared files are readable only while the share is open —
 * then that the upload goes on in the background, its notification saying
 * how far it got. [fertig] words the end for the number of files taken.
 */
object Uebergabe {

    fun uebergeben(a: Activity, titel: String, dateien: List<Pair<Uri, String>>, ziel: Ziel, fertig: (Int) -> String) {
        val dialog = ActionDialog.show(
            activity = a, title = titel, message = a.getString(R.string.standort_import_beginnt),
            sheet = ActionSheet(emptyList()), onPick = {}, onCancel = {}, footer = null, cancelable = false,
        )
        val resolver = a.contentResolver
        val app = a.applicationContext
        thread {
            val kopien = dateien.mapNotNull { (u, name) -> Hochladen.kopie(app, resolver, u)?.let { it to name } }
            // a ZIP for the music with no song in it is asked about first (owner, 2026-11-04)
            val arten = kopien.associateWith { (f, n) -> if (ziel == Ziel.MUSIK && n.lowercase().endsWith(".zip")) MusikDatei.zip(f) else null }
            val fraglich = kopien.filter { arten[it] == MusikDatei.Zip.FREMD || arten[it] == MusikDatei.Zip.TAKEOUT }
            (kopien - fraglich.toSet()).forEach { (f, n) -> Hochladen.anstellen(app, f, n, ziel) }
            val genommen = kopien.size - fraglich.size
            a.runOnUiThread {
                dialog.dismiss()
                when {
                    a.isFinishing -> fraglich.forEach { it.first.delete() }
                    kopien.isEmpty() -> sagen(a, titel, a.getString(R.string.standort_import_unlesbar))
                    fraglich.isEmpty() -> sagen(a, titel, fertig(genommen))
                    else -> fragen(a, titel, fraglich, fraglich.any { arten[it] == MusikDatei.Zip.TAKEOUT }) { n, wohin ->
                        when {
                            wohin == Ziel.STANDORT -> sagen(a, titel, a.getString(R.string.standort_import_job))
                            genommen + n == 0 -> a.finish()
                            else -> sagen(a, titel, fertig(genommen + n))
                        }
                    }
                }
            }
        }
    }

    /** Music ZIPs without a song: „trotzdem hochladen?“, a Takeout „Zum Standortverlauf?“; Abbrechen drops their copies. */
    private fun fragen(a: Activity, titel: String, fraglich: List<Pair<File, String>>, takeout: Boolean, weiter: (Int, Ziel) -> Unit) {
        val wohin = if (takeout) Ziel.STANDORT else Ziel.MUSIK
        ActionDialog.show(
            activity = a, title = titel, message = a.getString(if (takeout) R.string.musik_zip_takeout else R.string.musik_zip_fremd),
            sheet = ActionSheet(listOf(ActionItem(if (takeout) R.string.musik_zip_standort else R.string.musik_zip_trotzdem, ActionTone.PRIMARY))),
            onPick = {
                fraglich.forEach { (f, n) -> Hochladen.anstellen(a.applicationContext, f, n, wohin) }
                weiter(fraglich.size, wohin)
            },
            onCancel = {
                fraglich.forEach { it.first.delete() }
                weiter(0, Ziel.MUSIK)
            },
        )
    }

    /** A Takeout ZIP for the Standortverlauf, as [name]. */
    fun standort(a: Activity, uri: Uri, name: String) =
        uebergeben(a, a.getString(R.string.standort_import_title), listOf(uri to name), Ziel.STANDORT) { a.getString(R.string.standort_import_job) }

    /** The one dialog with [message]; „Fertig“ closes the share. */
    fun sagen(a: Activity, title: String, message: String) {
        ActionDialog.show(
            activity = a, title = title, message = message, sheet = ActionSheet(emptyList()),
            onPick = { a.finish() }, onCancel = { a.finish() }, footer = a.getString(R.string.share_done),
        )
    }
}
