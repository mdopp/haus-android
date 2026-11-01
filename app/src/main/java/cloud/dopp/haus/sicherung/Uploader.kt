package cloud.dopp.haus.sicherung

import cloud.dopp.haus.data.Napi
import cloud.dopp.haus.data.SicherungClient
import java.io.IOException
import java.io.InputStream

/**
 * A folder the person granted, read only: what is in it and a file's bytes.
 * On the phone it is the storage access framework ([SafOrdner]); in the tests
 * a directory.
 */
interface Ordner {
    /**
     * The files directly in [unter] (`""` is the folder itself), or with [tief]
     * every file below it, each with its path below the granted folder. A
     * dot-name is neither listed nor entered. A missing [unter] is empty; null
     * when the folder cannot be read any more (the grant was taken back, or
     * the folder is gone).
     */
    fun dateien(unter: String = "", tief: Boolean = false): List<Datei>?

    /** [pfad]'s bytes from [ab] on. */
    fun oeffnen(pfad: String, ab: Long): InputStream
}

/**
 * One file to Haus over the resumable upload (#194): in pieces of [teil]
 * bytes, and after any interruption it goes on where Haus stands instead of
 * starting over. The upload's id is remembered ([Merker]) until the file is
 * whole; the next run asks Haus for the offset (HEAD) and continues from it.
 * A 409 (or a 408 after a partial write) continues from the offset Haus
 * returns; a 404 starts a new upload. Haus keeps a finished upload known for
 * seven days: when the last answer was lost, HEAD says offset = size, and an
 * offset at the size — after HEAD or straight from the creation (a partial
 * already complete) — is a finished file.
 */
class Uploader(
    private val client: SicherungClient,
    private val merker: Merker,
    private val teil: Int = TEIL,
) {

    /** Where an unfinished upload's id is kept, per source and path, with the file's size and time. */
    interface Merker {
        fun upload(quelle: Napi.Quelle, pfad: String): String?
        fun merkeUpload(quelle: Napi.Quelle, pfad: String, zeile: String)
        fun vergissUpload(quelle: Napi.Quelle, pfad: String)
    }

    sealed class Ausgang {
        /** The file is whole on the box. */
        object Fertig : Ausgang()

        /** Asked to stop (constraints lost, time up): the next run goes on. */
        object Angehalten : Ausgang()

        /** The box is full (507): stop, say [satz]. */
        data class Voll(val satz: String) : Ausgang()

        /** The file is no longer what was listed (shorter): leave it to the next listing. */
        object Veraendert : Ausgang()

        /** Haus refused ([code], its [satz]), or no answer came (code 0). */
        data class Fehler(val code: Int, val satz: String?) : Ausgang()
    }

    /** [datei] of [ordner] as [quelle]'s file at [pfad]; [halt] is asked before every piece. */
    fun hochladen(
        quelle: Napi.Quelle,
        ordner: Ordner,
        datei: Datei,
        pfad: String = datei.pfad,
        halt: () -> Boolean = { false },
        fortschritt: (Long) -> Unit = {},
    ): Ausgang {
        var stream: InputStream? = null
        try {
            var (id, offset) = beginnen(quelle, pfad, datei)
            var stromAn = -1L
            var konflikte = 0
            var neu = 0
            val puffer = ByteArray(teil)
            while (true) {
                if (offset < 0 || offset > datei.groesse) return Ausgang.Fehler(0, null)
                fortschritt(offset)
                if (offset == datei.groesse) {
                    merker.vergissUpload(quelle, pfad)
                    return Ausgang.Fertig
                }
                if (halt()) return Ausgang.Angehalten
                if (stream == null || stromAn != offset) {
                    stream?.close()
                    stream = ordner.oeffnen(datei.pfad, offset)
                    stromAn = offset
                }
                val n = minOf(teil.toLong(), datei.groesse - offset).toInt()
                if (lies(stream, puffer, n) < n) return Ausgang.Veraendert
                stromAn += n
                when (val t = client.senden(id, offset, puffer, n)) {
                    is SicherungClient.Teil.Weiter -> {
                        konflikte = if (t.offset > offset || t.fertig) 0 else konflikte + 1
                        if (konflikte > MAX_KONFLIKTE) return Ausgang.Fehler(0, null)
                        if (t.fertig) {
                            merker.vergissUpload(quelle, pfad)
                            fortschritt(datei.groesse)
                            return Ausgang.Fertig
                        }
                        offset = t.offset
                    }
                    is SicherungClient.Teil.Versatz -> {
                        if (++konflikte > MAX_KONFLIKTE) return Ausgang.Fehler(409, null)
                        offset = t.offset
                    }
                    SicherungClient.Teil.Unbekannt -> {
                        if (++neu > MAX_NEU) return Ausgang.Fehler(404, null)
                        merker.vergissUpload(quelle, pfad)
                        val (nid, noff) = anlegen(quelle, pfad, datei)
                        id = nid
                        offset = noff
                    }
                    is SicherungClient.Teil.Voll -> return Ausgang.Voll(t.satz)
                }
            }
        } catch (e: SicherungClient.Abgelehnt) {
            if (e.code == 507) return Ausgang.Voll(e.satz ?: "Auf der Box ist kein Platz mehr für die Sicherung.")
            return Ausgang.Fehler(e.code, e.satz)
        } catch (e: IOException) {
            return if (halt()) Ausgang.Angehalten else Ausgang.Fehler(0, null)
        } finally {
            runCatching { stream?.close() }
        }
    }

    /** The upload remembered for this file, where Haus stands with it; else a new one. */
    private fun beginnen(quelle: Napi.Quelle, pfad: String, datei: Datei): Pair<String, Long> {
        val gemerkt = merker.upload(quelle, pfad)?.let { Zeile.aus(it) }
        if (gemerkt != null && gemerkt.groesse == datei.groesse && gemerkt.geaendert == datei.geaendert) {
            client.offset(gemerkt.id)?.let { return gemerkt.id to it }
        }
        if (gemerkt != null) merker.vergissUpload(quelle, pfad)
        return anlegen(quelle, pfad, datei)
    }

    private fun anlegen(quelle: Napi.Quelle, pfad: String, datei: Datei): Pair<String, Long> {
        val (id, offset) = client.anlegen(quelle, pfad, datei.groesse)
        merker.merkeUpload(quelle, pfad, Zeile(datei.groesse, datei.geaendert, id).text())
        return id to offset
    }

    /** What [Merker] keeps for a file: the upload's id, valid only for this size and time. */
    data class Zeile(val groesse: Long, val geaendert: Long, val id: String) {
        fun text() = "$groesse|$geaendert|$id"

        companion object {
            fun aus(s: String): Zeile? {
                val t = s.split('|', limit = 3)
                if (t.size != 3 || t[2].isBlank()) return null
                return Zeile(t[0].toLongOrNull() ?: return null, t[1].toLongOrNull() ?: return null, t[2])
            }
        }
    }

    companion object {
        /** 8 MiB a PATCH: Haus takes up to 16. */
        const val TEIL = 8 * 1024 * 1024

        /** PATCHes in a row that bring no progress before the run gives up on the file. */
        private const val MAX_KONFLIKTE = 3

        /** New uploads after a 404 before the run gives up on the file. */
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
