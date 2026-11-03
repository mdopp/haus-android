package cloud.dopp.homes.standort

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile

/** One fix as the phone took it: when (ms since 1970, UTC), where, how sure (m), and if known height (m) and speed (m/s). */
data class Punkt(
    val zeit: Long,
    val breite: Double,
    val laenge: Double,
    val genauigkeit: Float,
    val hoehe: Double? = null,
    val tempo: Float? = null,
) {
    /** The point as one line of the buffer: numbers, `;` between them, empty for unknown. */
    fun zeile(): String = listOf(zeit, breite, laenge, genauigkeit, hoehe ?: "", tempo ?: "").joinToString(";")

    companion object {
        /** A buffer line back, or null when it is not one (a line cut by a killed process). */
        fun aus(zeile: String): Punkt? {
            val t = zeile.split(';')
            if (t.size != 6) return null
            return Punkt(
                zeit = t[0].toLongOrNull() ?: return null,
                breite = t[1].toDoubleOrNull() ?: return null,
                laenge = t[2].toDoubleOrNull() ?: return null,
                genauigkeit = t[3].toFloatOrNull() ?: return null,
                hoehe = t[4].toDoubleOrNull(),
                tempo = t[5].toFloatOrNull(),
            )
        }
    }
}

/**
 * The points not yet with Reitti, in a file of the app's own, oldest first.
 * They survive the process and offline days; a point leaves only after Homes
 * said Reitti has it ([weg]). The file holds at most [hoechstens] points:
 * beyond that the oldest go, and how many is returned, so the screen can say
 * so — never silently.
 */
class Puffer(private val datei: File, private val hoechstens: Int = HOECHSTENS) {

    /** The first lines of the buffer: their points, and how many lines they were (a broken line counts, and goes with them). */
    data class Stapel(val punkte: List<Punkt>, val zeilen: Int)

    private fun zeilen(): List<String> =
        if (datei.isFile) datei.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList()

    private fun schreibe(liste: List<String>) {
        datei.parentFile?.mkdirs()
        val neu = File(datei.path + ".neu")
        OutputStreamWriter(FileOutputStream(neu), Charsets.UTF_8).use { w -> liste.forEach { w.write(it); w.write("\n") } }
        if (!neu.renameTo(datei)) {
            datei.delete()
            neu.renameTo(datei)
        }
    }

    /** Adds [punkte] at the end: how many of the oldest had to go for room. */
    fun dazu(punkte: List<Punkt>): Int {
        if (punkte.isEmpty()) return 0
        synchronized(LOCK) {
            datei.parentFile?.mkdirs()
            // a line cut by a killed process has no end: the new points start on their own
            val offen = datei.length() > 0 && RandomAccessFile(datei, "r").use { f -> f.seek(f.length() - 1); f.read() != '\n'.code }
            OutputStreamWriter(FileOutputStream(datei, true), Charsets.UTF_8).use { w ->
                if (offen) w.write("\n")
                punkte.forEach { w.write(it.zeile()); w.write("\n") }
            }
            val alle = zeilen()
            if (alle.size <= hoechstens) return 0
            val zuviel = alle.size - hoechstens
            schreibe(alle.drop(zuviel))
            return zuviel
        }
    }

    fun anzahl(): Int = synchronized(LOCK) { zeilen().size }

    /** When the oldest waiting point was taken, or null when none waits. */
    fun aeltester(): Long? = synchronized(LOCK) { zeilen().firstNotNullOfOrNull { Punkt.aus(it) }?.zeit }

    /** The oldest [n] lines. */
    fun stapel(n: Int): Stapel = synchronized(LOCK) {
        val z = zeilen().take(n)
        Stapel(z.mapNotNull { Punkt.aus(it) }, z.size)
    }

    /** Drops the oldest [n] lines: Reitti has them. */
    fun weg(n: Int) {
        if (n <= 0) return
        synchronized(LOCK) { schreibe(zeilen().drop(n)) }
    }

    companion object {
        /** More than a week of „Detailliert“ on the move all day, months of „Sparsam“; some 3 MB. */
        const val HOECHSTENS = 50_000

        /** One process, one buffer: the receiver adds while the screen sends. */
        private val LOCK = Any()
    }
}
