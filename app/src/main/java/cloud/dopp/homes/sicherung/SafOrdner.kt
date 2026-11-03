package cloud.dopp.homes.sicherung

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A folder the person granted through the system folder picker
 * (`ACTION_OPEN_DOCUMENT_TREE`, kept with `takePersistableUriPermission`):
 * the storage access framework, no storage permission (#194). Read only:
 * nothing here writes, renames or deletes a document.
 */
class SafOrdner(private val ctx: Context, private val baum: Uri) : Ordner {

    /** The document of each path listed so far, so a file opens without a second walk. */
    private val dokumente = HashMap<String, Uri>()

    /** Whether the grant for [baum] still stands. */
    fun freigegeben(): Boolean = freigegeben(ctx, baum)

    override fun dateien(unter: String, tief: Boolean): List<Datei>? {
        if (!freigegeben()) return null
        return try {
            val start = finde(unter) ?: return emptyList()
            val out = ArrayList<Datei>()
            gehe(start, unter, tief, out)
            out
        } catch (e: SecurityException) {
            null
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null // the folder is gone: its document id no longer resolves
        }
    }

    /** Whether the folder [unter] is there; false also when the folder cannot be read. */
    fun hat(unter: String): Boolean = freigegeben() && runCatching { finde(unter) != null }.getOrDefault(false)

    override fun oeffnen(pfad: String, ab: Long): InputStream {
        val uri = dokumente[pfad] ?: throw FileNotFoundException(pfad)
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(pfad)
        val s = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (ab > 0) vorspulen(s, ab)
        return s
    }

    /** The document id of [unter] below the granted folder, or null when it is not there. */
    private fun finde(unter: String): String? {
        var id = DocumentsContract.getTreeDocumentId(baum)
        if (unter.isEmpty()) return id
        for (name in unter.split('/')) {
            id = kinder(id).firstOrNull { it.name == name && it.ordner }?.id ?: return null
        }
        return id
    }

    private fun gehe(id: String, pfad: String, tief: Boolean, out: MutableList<Datei>) {
        for (k in kinder(id)) {
            if (k.name.startsWith(".")) continue
            val p = if (pfad.isEmpty()) k.name else "$pfad/${k.name}"
            if (k.ordner) {
                if (tief) gehe(k.id, p, true, out)
            } else {
                dokumente[p] = DocumentsContract.buildDocumentUriUsingTree(baum, k.id)
                out.add(Datei(p, k.groesse, k.geaendert))
            }
        }
    }

    private class Kind(val id: String, val name: String, val ordner: Boolean, val groesse: Long, val geaendert: Long)

    private fun kinder(id: String): List<Kind> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(baum, id)
        val out = ArrayList<Kind>()
        ctx.contentResolver.query(uri, SPALTEN, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val kid = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out.add(
                    Kind(
                        id = kid,
                        name = name,
                        ordner = c.getString(2) == Document.MIME_TYPE_DIR,
                        groesse = if (c.isNull(3)) 0L else c.getLong(3),
                        geaendert = if (c.isNull(4)) 0L else c.getLong(4),
                    ),
                )
            }
        } ?: throw FileNotFoundException(id)
        return out
    }

    companion object {
        private val SPALTEN = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        /** The external storage's document provider. */
        private const val SPEICHER = "com.android.externalstorage.documents"

        /**
         * Where the picker opens for WhatsApp: `Android/media/com.whatsapp/WhatsApp`
         * on the phone's own storage. Android 11+ keeps `Android/data` and
         * `Android/obb` out of the picker, not `Android/media`.
         */
        val WHATSAPP_START: Uri =
            DocumentsContract.buildDocumentUri(SPEICHER, "primary:Android/media/com.whatsapp/WhatsApp")

        /** Whether the persisted read grant for [baum] still stands. */
        fun freigegeben(ctx: Context, baum: Uri): Boolean =
            ctx.contentResolver.persistedUriPermissions.any { it.uri == baum && it.isReadPermission }

        /** Keep the read grant the picker gave for [baum] across restarts; false when Android refuses. */
        fun behalten(ctx: Context, baum: Uri): Boolean = runCatching {
            ctx.contentResolver.takePersistableUriPermission(baum, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)

        /** Skip to [ab]: by position when the stream is a file, else by reading past. */
        private fun vorspulen(s: FileInputStream, ab: Long) {
            val gesetzt = runCatching { s.channel.position(ab) }.isSuccess
            if (gesetzt) return
            var rest = ab
            while (rest > 0) {
                val n = s.skip(rest)
                if (n <= 0) throw FileNotFoundException("shorter than $ab")
                rest -= n
            }
        }
    }
}
