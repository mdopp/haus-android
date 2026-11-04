package cloud.dopp.homes.standort

import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.StandortImportClient
import cloud.dopp.homes.sicherung.Stueckweise
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Standortverlauf übernehmen (owner 2026-11-03): one shared history file to
 * Homes, which brings it into the person's Reitti, and what came of it.
 *
 * The file goes in pieces the way a backup does ([Stueckweise]): after a cut
 * it asks Homes where it stands (HEAD) and goes on from there; shared again
 * later, the same name and size resume Homes' partial upload. Then it asks
 * Homes every [FRAGE] how far the import got, until Homes says done or why
 * not — or nothing moved for [STILL], which is said, not waited out.
 *
 * Plain and blocking: the share screen runs it on its own thread and shows
 * [Fortschritt]; [halt] is asked before every piece and every question.
 */
class Import(
    private val client: StandortImportClient,
    private val teil: Int = Stueckweise.TEIL,
    private val schlafen: (Long) -> Unit = { Thread.sleep(it) },
    private val jetzt: () -> Long = { System.currentTimeMillis() },
) {

    /** What the screen shows while it runs. */
    sealed class Fortschritt {
        /** The file goes up: [gesendet] of [groesse] bytes are on the box. */
        data class Hochladen(val gesendet: Long, val groesse: Long) : Fortschritt()

        /** Homes reads it: [gelesen] of [groesse] bytes, [punkte] places with Reitti so far. */
        data class Uebernehmen(val gelesen: Long, val groesse: Long, val punkte: Long) : Fortschritt()
    }

    sealed class Ausgang {
        /** Reitti has them; [text] is Homes' sentence („12.345 Orte von … bis … übernommen …“). */
        data class Fertig(val text: String) : Ausgang()

        /** Homes or Reitti said no: [satz] why, [grund] Homes' word ([Napi.GRUND_KEIN_SCHLUESSEL] …). */
        data class Fehler(val satz: String?, val grund: String?) : Ausgang()

        /** 401: the app is not paired (any more). */
        object NichtGekoppelt : Ausgang()

        /** Homes was not reached, again and again. */
        object Unerreichbar : Ausgang()

        /** The import gave no sign of progress for [STILL]; it may still run on the box. */
        object Still : Ausgang()

        /** [halt] said stop: the upload goes on when the file is shared again. */
        object Angehalten : Ausgang()
    }

    /**
     * [name] ([groesse] bytes) to Homes and into Reitti. [oeffnen] gives the
     * file's bytes from an offset on.
     */
    fun uebernehmen(
        name: String,
        groesse: Long,
        oeffnen: (Long) -> InputStream,
        fortschritt: (Fortschritt) -> Unit = {},
        halt: () -> Boolean = { false },
    ): Ausgang = try {
        val id = hochladen(name, groesse, oeffnen, fortschritt, halt)
        if (id == null) Ausgang.Angehalten else warten(id, groesse, fortschritt, halt)
    } catch (e: StandortImportClient.Abgelehnt) {
        if (e.code == 401) Ausgang.NichtGekoppelt else Ausgang.Fehler(e.satz, e.grund)
    } catch (e: Kuerzer) {
        Ausgang.Fehler(KUERZER, null)
    } catch (e: Stueckweise.Unlesbar) {
        Ausgang.Fehler(UNLESBAR, null)
    } catch (e: IOException) {
        Ausgang.Unerreichbar
    }

    /** The upload: its id once the file is whole on the box, null when [halt] said stop. */
    private fun hochladen(
        name: String,
        groesse: Long,
        oeffnen: (Long) -> InputStream,
        fortschritt: (Fortschritt) -> Unit,
        halt: () -> Boolean,
    ): String? {
        val ruf = object : Stueckweise.Ruf {
            override fun anlegen() = client.anlegen(name, groesse)
            override fun senden(id: String, offset: Long, puffer: ByteArray, laenge: Int) = client.senden(id, offset, puffer, laenge)
        }
        // a cut: ask Homes where it stands, after a pause; Homes' refusals end it
        val nachSchnitt = { id: String, e: IOException, inFolge: Int ->
            if (e is StandortImportClient.Abgelehnt || inFolge > VERSUCHE) throw e
            schlafen(PAUSE * inFolge)
            mitVersuchen { client.offset(id) }?.let { id to it } ?: mitVersuchen { client.anlegen(name, groesse) }
        }
        val start = mitVersuchen { client.anlegen(name, groesse) }
        val stueckweise = Stueckweise(ruf, teil, halt, { fortschritt(Fortschritt.Hochladen(it, groesse)) }, nachSchnitt)
        return when (val e = stueckweise.laden(start, groesse, oeffnen)) {
            is Stueckweise.Ende.Fertig -> e.id
            Stueckweise.Ende.Angehalten -> null
            Stueckweise.Ende.Kuerzer -> throw Kuerzer()
            is Stueckweise.Ende.Voll -> throw StandortImportClient.Abgelehnt(507, e.satz, null)
            is Stueckweise.Ende.Fest -> throw IOException("no progress")
        }
    }

    private class Kuerzer : IOException("the file got shorter")

    /** Ask Homes how far import [id] got until it ended, or stood still for [STILL]. */
    private fun warten(id: String, groesse: Long, fortschritt: (Fortschritt) -> Unit, halt: () -> Boolean): Ausgang {
        var zuletzt: Pair<Long, Long>? = null
        var seit = jetzt()
        while (true) {
            if (halt()) return Ausgang.Angehalten
            val s = try {
                client.stand(id)
            } catch (e: StandortImportClient.Abgelehnt) {
                throw e
            } catch (e: IOException) {
                null
            }
            if (s != null) {
                when (s.stand) {
                    Napi.IMPORT_FERTIG -> return Ausgang.Fertig(s.text ?: FERTIG)
                    Napi.IMPORT_FEHLER -> return Ausgang.Fehler(s.fehler, s.grund)
                    else -> {
                        fortschritt(Fortschritt.Uebernehmen(s.gelesen, if (s.groesse > 0) s.groesse else groesse, s.punkte))
                        val stand = s.gelesen to s.punkte
                        if (stand != zuletzt) {
                            zuletzt = stand
                            seit = jetzt()
                        }
                    }
                }
            }
            if (jetzt() - seit > STILL) return if (s == null) Ausgang.Unerreichbar else Ausgang.Still
            schlafen(FRAGE)
        }
    }

    /** [ruf], again after a pause when Homes was not reached; Homes' refusals are not retried. */
    private fun <T> mitVersuchen(ruf: () -> T): T {
        var n = 0
        while (true) {
            try {
                return ruf()
            } catch (e: StandortImportClient.Abgelehnt) {
                throw e
            } catch (e: IOException) {
                if (++n > VERSUCHE) throw e
                schlafen(PAUSE * n)
            }
        }
    }

    companion object {
        /** How often the screen asks Homes while the import runs. */
        const val FRAGE = 2_000L

        /** How long no sign of progress is waited for. */
        const val STILL = 5 * 60_000L

        /** Tries of one call while Homes is not reached. */
        private const val VERSUCHE = 4

        /** The pause before the next try grows by this. */
        private const val PAUSE = 3_000L

        const val KUERZER = "Die Datei wurde beim Lesen kürzer. Teile sie noch einmal."
        const val UNLESBAR = "Die Datei ließ sich auf dem Handy nicht lesen."
        const val FERTIG = "Reitti hat den Standortverlauf übernommen."

        /** The names a history file has: Google's Timeline.json and Records.json, GPX, GeoJSON. */
        private val ENDUNGEN = listOf(".json", ".geojson", ".gpx")

        /** The types a share may come as with any of those names. */
        val TYPEN = listOf(
            "application/json", "application/geo+json", "application/gpx+xml", "application/gpx",
            "application/xml", "text/xml", "application/octet-stream",
        )

        /** Does a file of [name] and [mime] look like a history Homes reads? The name decides when there is one. */
        fun nimmt(name: String?, mime: String?): Boolean {
            val n = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (n.isNotEmpty()) return ENDUNGEN.any { n.endsWith(it) }
            val m = mime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()
            return m != null && m != "application/octet-stream" && m in TYPEN
        }

        /** [s] moved on by [n] bytes; false when it ended before. */
        fun ueberspringe(s: InputStream, n: Long): Boolean {
            var rest = n
            val puffer = ByteArray(64 * 1024)
            while (rest > 0) {
                val k = s.skip(rest)
                if (k > 0) {
                    rest -= k
                    continue
                }
                val r = s.read(puffer, 0, minOf(puffer.size.toLong(), rest).toInt())
                if (r < 0) return false
                rest -= r
            }
            return true
        }
    }
}
