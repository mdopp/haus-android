package cloud.dopp.homes.hochladen

import android.app.Activity
import android.net.Uri
import cloud.dopp.homes.R
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionSheet
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
            val genommen = dateien.count { (u, name) -> Hochladen.uebernehmen(app, resolver, u, name, ziel) }
            a.runOnUiThread {
                dialog.dismiss()
                if (a.isFinishing) return@runOnUiThread
                if (genommen == 0) sagen(a, titel, a.getString(R.string.standort_import_unlesbar)) else sagen(a, titel, fertig(genommen))
            }
        }
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
