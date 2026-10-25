package cloud.dopp.haus.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ApiClient.Merken
import kotlin.concurrent.thread

/**
 * Share → Haus (haus#120): the phone's share sheet sends a link or a text
 * here — a recipe, an album review, a holiday flat from the browser — and
 * Haus keeps the page as a note, as "Aus Link merken" does on its Notizen
 * page and Joshua does on Signal.
 *
 * Every share answers at once ("Merkt sich …") and ends visibly: the line
 * Haus said with a way to the note, or Haus's own sentence why not, or that
 * Haus did not answer in time. Nothing runs in the background after the
 * dialog is gone.
 */
class ShareActivity : Activity() {

    private var busy: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = sharedText(intent?.getStringExtra(Intent.EXTRA_SUBJECT), intent?.getStringExtra(Intent.EXTRA_TEXT))
        if (intent?.action != Intent.ACTION_SEND || text == null) {
            finish()
            return
        }
        busy = dialog().setTitle(R.string.share_title).setMessage(R.string.share_running).setCancelable(false).show()
        val api = ApiClient(applicationContext)
        thread {
            val result = api.merken(text)
            runOnUiThread { if (!isFinishing) answer(result) }
        }
    }

    private fun dialog() = AlertDialog.Builder(this, R.style.Theme_Haus_ActionDialog)
        .setOnDismissListener { finish() }

    private fun answer(result: Merken) {
        busy?.setOnDismissListener(null)
        busy?.dismiss()
        val b = dialog()
        when (result) {
            is Merken.Kept -> {
                b.setTitle(if (result.already) R.string.share_already else R.string.share_kept).setMessage(result.said)
                result.note?.let { note ->
                    b.setNeutralButton(R.string.share_open) { _, _ ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(note))) }
                    }
                }
            }
            is Merken.Refused -> b.setTitle(R.string.share_not_kept).setMessage(result.sentence)
            Merken.NotPaired -> b.setTitle(R.string.share_not_kept).setMessage(R.string.share_not_paired)
            Merken.Unreachable -> b.setTitle(R.string.share_not_kept).setMessage(R.string.share_unreachable)
        }
        b.setPositiveButton(android.R.string.ok, null).show()
    }

    companion object {
        /**
         * What was shared, as one text: a browser shares the page's title as
         * the subject and the link as the text; either alone is fine. Null
         * when nothing came.
         */
        fun sharedText(subject: String?, text: String?): String? =
            listOfNotNull(subject?.trim(), text?.trim())
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" ")
                .ifBlank { null }
    }
}
