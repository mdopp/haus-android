package cloud.dopp.homes.sicherung

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * The one place the app writes a document (homes-android#197): a folder the
 * person granted for a restore, through the system folder picker with write
 * access — Signal's target folder, or WhatsApp's `Android/media/…` on a new
 * phone. It makes folders and files and writes into them; it never deletes,
 * renames or moves one, and [Rueckholung] decides that nothing of the
 * person's is written over without asking. The backup's folders stay
 * [SafOrdner]'s, read only.
 */
class SafZiel(private val ctx: Context, private val baum: Uri) : Ziel {

    /** The children of each folder looked at so far, by name: a restore of 18 000 media asks each folder once. */
    private val kinder = HashMap<String, MutableMap<String, Kind>>()

    private class Kind(val id: String, val ordner: Boolean, val groesse: Long)

    override fun groesse(pfad: String): Long? {
        val teile = pfad.split('/')
        var id = DocumentsContract.getTreeDocumentId(baum)
        for (name in teile.dropLast(1)) {
            id = liste(id)[name]?.takeIf { it.ordner }?.id ?: return null
        }
        val k = liste(id)[teile.last()] ?: return null
        if (k.ordner) return null
        // the size as it is now, not as listed: the restore itself made it grow
        return groesseVon(k.id) ?: k.groesse
    }

    override fun schreiben(pfad: String, ab: Long): OutputStream {
        val teile = pfad.split('/')
        var id = DocumentsContract.getTreeDocumentId(baum)
        for (name in teile.dropLast(1)) {
            val da = liste(id)[name]
            id = when {
                da?.ordner == true -> da.id
                da != null -> throw FileNotFoundException("$name is a file")
                else -> neu(id, name, Document.MIME_TYPE_DIR)
            }
        }
        val name = teile.last()
        val datei = liste(id)[name]?.also { if (it.ordner) throw FileNotFoundException("$name is a folder") }?.id
            ?: neu(id, name, BINAER)
        val uri = DocumentsContract.buildDocumentUriUsingTree(baum, datei)
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: throw FileNotFoundException(pfad)
        val aus = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        try {
            aus.channel.truncate(ab)
            aus.channel.position(ab)
        } catch (e: Exception) {
            aus.close()
            throw FileNotFoundException("$pfad: cannot write from $ab")
        }
        return aus
    }

    /** A new folder or file [name] in [eltern]; Android must keep the name as given, or it is not ours to use. */
    private fun neu(eltern: String, name: String, typ: String): String {
        val uri = DocumentsContract.createDocument(
            ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(baum, eltern), typ, name,
        ) ?: throw FileNotFoundException(name)
        val id = DocumentsContract.getDocumentId(uri)
        kinder.getValue(eltern)[name] = Kind(id, typ == Document.MIME_TYPE_DIR, 0)
        return id
    }

    private fun liste(id: String): Map<String, Kind> = kinder.getOrPut(id) {
        val out = HashMap<String, Kind>()
        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(baum, id), SPALTEN, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val kid = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out[name] = Kind(kid, c.getString(2) == Document.MIME_TYPE_DIR, if (c.isNull(3)) 0L else c.getLong(3))
            }
        } ?: throw FileNotFoundException(id)
        out
    }

    private fun groesseVon(id: String): Long? =
        ctx.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(baum, id), arrayOf(Document.COLUMN_SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    companion object {
        private val SPALTEN = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)

        /** Android keeps a file's name as given for this type: no extension added. */
        private const val BINAER = "application/octet-stream"

        private const val LESEN_SCHREIBEN = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** Keep the picker's read and write grant for [baum] across restarts; false when Android refuses. */
        fun behalten(ctx: Context, baum: Uri): Boolean = runCatching {
            ctx.contentResolver.takePersistableUriPermission(baum, LESEN_SCHREIBEN)
            true
        }.getOrDefault(false)

        /** Whether the persisted write grant for [baum] still stands. */
        fun freigegeben(ctx: Context, baum: Uri): Boolean =
            ctx.contentResolver.persistedUriPermissions.any { it.uri == baum && it.isWritePermission && it.isReadPermission }

        /** Give the grant back: the folder was a wrong one, or another was chosen. */
        fun loslassen(ctx: Context, baum: Uri) {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(baum, LESEN_SCHREIBEN) }
        }
    }
}

