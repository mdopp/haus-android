package cloud.dopp.homes.sicherung

import cloud.dopp.homes.data.SicherungClient
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * Homes' resumable upload (create, HEAD for the offset, PATCH the next bytes),
 * the loop its two users share: the backups ([Uploader]) and a
 * shared location history (`standort/Import`). The file goes in pieces of
 * [teil] bytes from where Homes stands. A 409 (or a 408 after a partial
 * write) goes on from Homes' offset, a 404 starts a new upload, and an
 * offset at the size is a whole file.
 *
 * What differs stays with the caller: how an upload starts and is resumed,
 * and what a cut connection means (`nachSchnitt`: the backup leaves it to
 * the next run, the import asks Homes where it stands).
 */
class Stueckweise(
    private val ruf: Ruf,
    private val teil: Int = TEIL,
    private val halt: () -> Boolean = { false },
    private val fortschritt: (Long) -> Unit = {},
    /**
     * A cut while sending, with the cuts in a row so far: answers where to go
     * on (id and offset), or throws. Without it the cut is thrown.
     */
    private val nachSchnitt: ((id: String, schnitt: IOException, inFolge: Int) -> Pair<String, Long>)? = null,
) {

    /** The calls of one upload. */
    interface Ruf {
        /** A new upload, or Homes' partial one: its id and offset. */
        fun anlegen(): Pair<String, Long>

        /** [laenge] bytes of [puffer] as upload [id]'s bytes from [offset] on. */
        fun senden(id: String, offset: Long, puffer: ByteArray, laenge: Int): SicherungClient.Teil
    }

    sealed class Ende {
        /** The file is whole on the box, as upload [id]. */
        data class Fertig(val id: String) : Ende()

        /** `halt` said stop, between two pieces. */
        object Angehalten : Ende()

        /** The file had fewer bytes than its size: it changed. */
        object Kuerzer : Ende()

        /** 507: the box is full; [satz] says so. */
        data class Voll(val satz: String) : Ende()

        /** No progress, again and again, or an offset outside the file; [code] the last answer's (0: none). */
        data class Fest(val code: Int) : Ende()
    }

    /** The file could not be read on the phone. */
    class Unlesbar(cause: IOException) : IOException("the file cannot be read", cause)

    /** Where the upload stands, and how often in a row it did not move on. */
    private class Lage(var id: String, var offset: Long) {
        var konflikte = 0
        var neu = 0
        var schnitte = 0

        fun nach(neu: Pair<String, Long>) {
            id = neu.first
            offset = neu.second
        }
    }

    /**
     * The file of [groesse] bytes from [start] (an upload's id and offset) on;
     * [oeffnen] gives its bytes from an offset. `halt` is asked and
     * `fortschritt` told the offset before every piece. Homes' refusals are
     * thrown as they come.
     */
    fun laden(start: Pair<String, Long>, groesse: Long, oeffnen: (Long) -> InputStream): Ende {
        val lage = Lage(start.first, start.second)
        val puffer = ByteArray(teil)
        Quelle(oeffnen).use { quelle ->
            while (true) {
                if (lage.offset !in 0..groesse) return Ende.Fest(0)
                fortschritt(lage.offset)
                if (lage.offset == groesse) return Ende.Fertig(lage.id)
                if (halt()) return Ende.Angehalten
                val n = minOf(teil.toLong(), groesse - lage.offset).toInt()
                if (quelle.lies(lage.offset, puffer, n) < n) return Ende.Kuerzer
                val t = try {
                    ruf.senden(lage.id, lage.offset, puffer, n)
                } catch (e: IOException) {
                    val weiter = nachSchnitt ?: throw e
                    lage.nach(weiter(lage.id, e, ++lage.schnitte))
                    continue
                }
                antwort(lage, t, groesse)?.let { return it }
            }
        }
    }

    /** What a PATCH's answer [t] means: the end, or null to go on from [lage]. */
    private fun antwort(lage: Lage, t: SicherungClient.Teil, groesse: Long): Ende? = when (t) {
        is SicherungClient.Teil.Weiter -> weiter(lage, t, groesse)
        is SicherungClient.Teil.Versatz ->
            if (++lage.konflikte > MAX_KONFLIKTE) Ende.Fest(409) else null.also { lage.offset = t.offset }
        SicherungClient.Teil.Unbekannt ->
            if (++lage.neu > MAX_NEU) Ende.Fest(404) else null.also { lage.nach(ruf.anlegen()) }
        is SicherungClient.Teil.Voll -> Ende.Voll(t.satz)
    }

    /** A PATCH taken: done, on from Homes' offset, or stuck when it brought nothing too often. */
    private fun weiter(lage: Lage, t: SicherungClient.Teil.Weiter, groesse: Long): Ende? {
        val voran = t.offset > lage.offset || t.fertig
        lage.konflikte = if (voran) 0 else lage.konflikte + 1
        if (voran) lage.schnitte = 0
        return when {
            lage.konflikte > MAX_KONFLIKTE -> Ende.Fest(0)
            t.fertig -> Ende.Fertig(lage.id).also { fortschritt(groesse) }
            else -> null.also { lage.offset = t.offset }
        }
    }

    /** The file's bytes; the stream is opened again only where a piece does not follow on. */
    private class Quelle(private val oeffnen: (Long) -> InputStream) : Closeable {
        private var stream: InputStream? = null
        private var an = -1L

        fun lies(ab: Long, puffer: ByteArray, n: Int): Int = try {
            val s = stream?.takeIf { an == ab } ?: oeffnen(ab).also {
                stream?.close()
                stream = it
                an = ab
            }
            lies(s, puffer, n).also { an += it }
        } catch (e: IOException) {
            throw Unlesbar(e)
        }

        override fun close() {
            runCatching { stream?.close() }
        }
    }

    companion object {
        /** 8 MiB a PATCH: Homes takes up to 16. */
        const val TEIL = 8 * 1024 * 1024

        /** PATCHes in a row that bring no progress before the file is given up. */
        private const val MAX_KONFLIKTE = 3

        /** New uploads after a 404 before the file is given up. */
        private const val MAX_NEU = 2

        /** Read [n] bytes into [puffer]; fewer only at the end of the file. */
        private fun lies(s: InputStream, puffer: ByteArray, n: Int): Int {
            var gelesen = 0
            while (gelesen < n) {
                val r = s.read(puffer, gelesen, n - gelesen)
                if (r < 0) break
                gelesen += r
            }
            return gelesen
        }
    }
}
