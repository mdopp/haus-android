package cloud.dopp.haus.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ApiClient.Merken
import cloud.dopp.haus.data.ApiClient.Upload
import cloud.dopp.haus.widget.ActionDialog
import kotlin.concurrent.thread

/**
 * Share → Haus (haus#120): the phone's share sheet sends a link or a text
 * here — a recipe, an album review, a holiday flat from the browser — and
 * Haus keeps the page as a note, as "Aus Link merken" does on its Notizen
 * page and the assistant does in the chat.
 *
 * A photo — one or several, as the camera app shares them — goes to Haus's
 * document inbox instead ([PhotoShare], owner 2026-11-02: „Foto machen und
 * mit der Haus App teilen“), one upload each.
 *
 * Every share answers at once ("Merkt sich …", "Legt ab …") and ends
 * visibly: the line Haus said with a way to the note, what became of the
 * photos, or the sentence why not, or that Haus did not answer in time.
 * Nothing runs in the background after the dialog is gone.
 */
class ShareActivity : Activity() {

    private var busy: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActionDialog.scrim(this) // the browser behind stays dimmed, not read through
        if (PhotoShare.isImageShare(intent)) {
            sharePhotos(PhotoShare.uris(intent))
            return
        }
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

    /** Each photo to Haus, one after the other, the dialog saying which one; then what became of them. */
    private fun sharePhotos(uris: List<Uri>) {
        if (uris.isEmpty()) {
            finish()
            return
        }
        val photos = uris.take(PhotoShare.MAX_PHOTOS)
        busy = dialog().setTitle(R.string.share_title)
            .setMessage(resources.getQuantityString(R.plurals.share_photos_running, photos.size, 1, photos.size))
            .setCancelable(false).show()
        val api = ApiClient(applicationContext)
        val resolver = contentResolver
        val at = java.util.Date()
        thread {
            val results = photos.mapIndexed { i, uri ->
                runOnUiThread {
                    busy?.setMessage(resources.getQuantityString(R.plurals.share_photos_running, photos.size, i + 1, photos.size))
                }
                val photo = PhotoShare.read(resolver, uri)
                if (photo == null) {
                    Upload.Refused(getString(R.string.share_photo_unreadable))
                } else {
                    api.upload(photo.bytes, PhotoShare.name(at, i, photos.size, photo.ext), photo.mime)
                }
            }
            runOnUiThread { if (!isFinishing) photosAnswer(results, uris.size - photos.size) }
        }
    }

    private fun photosAnswer(results: List<Upload>, left: Int) {
        busy?.setOnDismissListener(null)
        busy?.dismiss()
        val stored = results.count { it is Upload.Stored }
        val failed = results.firstOrNull { it !is Upload.Stored }
        val why = when (failed) {
            is Upload.Refused -> failed.sentence
            Upload.NotPaired -> getString(R.string.share_not_paired)
            Upload.Unreachable -> getString(R.string.share_photos_unreachable)
            else -> null
        }
        val (title, text) = when {
            failed == null -> R.string.share_photos_kept to resources.getQuantityString(R.plurals.share_photos_kept_text, stored, stored)
            stored == 0 -> R.string.share_photos_not_kept to why.orEmpty()
            else -> R.string.share_photos_partly to getString(R.string.share_photos_partly_text, stored, results.size, why)
        }
        val more = if (left > 0) " " + getString(R.string.share_photos_too_many, PhotoShare.MAX_PHOTOS, left) else ""
        dialog().setTitle(title).setMessage(text + more).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun dialog() = AlertDialog.Builder(this, R.style.Theme_Haus_ShareDialog)
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
