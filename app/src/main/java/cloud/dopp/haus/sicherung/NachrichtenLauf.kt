package cloud.dopp.haus.sicherung

import cloud.dopp.haus.data.Napi.Quelle
import cloud.dopp.haus.data.SicherungClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What is read from the phone, each with its own high-water mark: the largest date sent (ms). */
enum class Art(val wire: String) { SMS("sms"), MMS("mms"), ANRUFE("anrufe") }

/** The phone's messages and calls as SyncTech XML ([SmsXml]). On the phone it is [HandyTelefon]; in the tests a list. */
interface Telefon {
    /** The SMS and MMS newer than the marks into [datei]; null (and no file) when there is none. */
    fun nachrichten(datei: File, seitSms: Long, seitMms: Long): Auszug?

    /** The calls newer than [seit] into [datei]; null (and no file) when there is none. */
    fun anrufe(datei: File, seit: Long): Auszug?
}

/** A written file: how many entries, and the largest date per [Art] in it. */
data class Auszug(val anzahl: Int, val marken: Map<Art, Long>)

/**
 * The high-water marks, and which file under way will move them: a mark moves
 * only once its file is whole on the box.
 */
interface Marken {
    /** The largest date of [art] that reached the box, 0 before the first run. */
    fun marke(art: Art): Long

    /** The marks the file [name] moves once it is on the box; null when it is not under way. */
    fun ausstehend(name: String): Map<Art, Long>?

    /** [name] is under way and will move [marken]; null forgets it. Kept at once: it must survive a killed process. */
    fun merkeAusstehend(name: String, marken: Map<Art, Long>?)

    /** [art]'s mark is now [wert]: only once its file is whole on the box. */
    fun setzeMarke(art: Art, wert: Long)
}

/**
 * „SMS und Anrufliste übertragen“ (laib ADR 0029), in the daily run: the SMS,
 * MMS and calls newer than the marks, as SyncTech XML, age-encrypted on the
 * phone to the person's archive key (`GET /napi/sicherung/schluessel`), then
 * uploaded as `quelle` `sms` through the same resumable upload as the
 * messengers' backups. The plaintext lives in the app's cache only while it
 * is encrypted, and is deleted then; the encrypted file stays until it is
 * whole on the box, and the next run goes on with it before it writes anew.
 * The first run takes everything.
 */
class NachrichtenLauf(
    private val client: SicherungClient,
    private val gedaechtnis: Gedaechtnis,
    private val marken: Marken,
    private val telefon: Telefon,
    /** The app's own folder for the files under way (in the cache). */
    private val ordner: File,
    private val halt: () -> Boolean = { false },
    private val melde: (Fortschritt) -> Unit = {},
    private val uhr: () -> Long = System::currentTimeMillis,
    teil: Int = Uploader.TEIL,
) {
    private val q = Quelle.SMS
    private val uploader = Uploader(client, gedaechtnis, teil)
    private var gesendet = 0

    fun laufen(): Lauf.Ergebnis {
        ordner.mkdirs()
        gesendet = 0
        // a plaintext left by a killed run never goes anywhere
        ordner.listFiles()?.filter { !it.name.endsWith(ENDUNG) }?.forEach { it.delete() }
        // first the files under way: their marks must move before anything new is read
        val unterwegs = ordner.listFiles()?.filter { it.name.endsWith(ENDUNG) }?.sortedBy { it.name }.orEmpty()
        for (f in unterwegs) {
            if (marken.ausstehend(f.name) == null) {
                f.delete() // written, but never noted: written again from the marks
                continue
            }
            senden(f)?.let { return it }
        }
        val stempel = stempel(uhr())
        val neu = ArrayList<File>()
        var ohneErlaubnis = false
        try {
            val xml = File(ordner, "$stempel.xml")
            var empfaenger: ByteArray? = null
            val teile = listOf(
                "$SMS-$stempel" to { telefon.nachrichten(xml, marken.marke(Art.SMS), marken.marke(Art.MMS)) },
                "$ANRUFE-$stempel" to { telefon.anrufe(xml, marken.marke(Art.ANRUFE)) },
            )
            for ((name, lies) in teile) {
                val auszug = try {
                    lies()
                } catch (e: SecurityException) {
                    xml.delete()
                    ohneErlaubnis = true
                    break
                } ?: continue
                if (auszug.anzahl == 0) {
                    xml.delete()
                    continue
                }
                val an = empfaenger ?: archivSchluessel()?.also { empfaenger = it }
                if (an == null) {
                    xml.delete()
                    return Lauf.Ergebnis.HALT
                }
                val ziel = File(ordner, "$name.xml$ENDUNG")
                val tmp = File(ordner, "$name.tmp")
                FileInputStream(xml).use { ein -> FileOutputStream(tmp).use { aus -> Age.verschluesseln(an, ein, aus) } }
                xml.delete()
                if (!tmp.renameTo(ziel)) throw IOException("rename")
                marken.merkeAusstehend(ziel.name, auszug.marken)
                neu += ziel
            }
        } catch (e: Ende) {
            ordner.listFiles()?.filter { !it.name.endsWith(ENDUNG) }?.forEach { it.delete() }
            return e.ergebnis
        } catch (e: IOException) {
            ordner.listFiles()?.filter { !it.name.endsWith(ENDUNG) }?.forEach { it.delete() }
            gedaechtnis.melde(q, Meldung(uhr(), Meldung.Art.SPAETER, Lauf.SPAETER))
            return Lauf.Ergebnis.WEITER
        }
        for (f in neu) senden(f)?.let { return it }
        if (gesendet > 0) gedaechtnis.merkeUebertragen(q, uhr(), gesendet)
        gedaechtnis.melde(q, if (ohneErlaubnis) Meldung(uhr(), Meldung.Art.KEINE, KEINE_ERLAUBNIS) else Meldung(uhr(), Meldung.Art.OK))
        return Lauf.Ergebnis.FERTIG
    }

    private class Ende(val ergebnis: Lauf.Ergebnis) : Exception()

    /** The archive key, or null when Haus has none (its sentence is said). */
    private fun archivSchluessel(): ByteArray? = try {
        Age.empfaenger(client.schluessel())
    } catch (e: SicherungClient.Abgelehnt) {
        when {
            e.code == 429 -> throw Ende(spaeter(Lauf.BESCHAEFTIGT, Lauf.Ergebnis.BESCHAEFTIGT))
            e.code >= 500 && e.code != 503 -> throw Ende(spaeter(Lauf.UNERREICHBAR, Lauf.Ergebnis.UNERREICHBAR))
            else -> {
                val satz = if (e.code == 401) Lauf.NICHT_GEKOPPELT else e.satz ?: KEIN_SCHLUESSEL
                gedaechtnis.melde(q, Meldung(uhr(), Meldung.Art.HAUS, satz))
                null
            }
        }
    } catch (e: IllegalArgumentException) {
        gedaechtnis.melde(q, Meldung(uhr(), Meldung.Art.HAUS, KEIN_SCHLUESSEL))
        null
    } catch (e: IOException) {
        throw Ende(spaeter(Lauf.UNERREICHBAR, Lauf.Ergebnis.UNERREICHBAR))
    }

    /** One encrypted file to the box; on success its marks move and it is deleted. Null: go on. */
    private fun senden(f: File): Lauf.Ergebnis? {
        val d = Datei(f.name, f.length(), f.lastModified())
        val a = uploader.hochladen(q, Eigener(ordner), d, d.pfad, halt) { n -> melde(Fortschritt(q, d.name, n, d.groesse, 1, 1)) }
        return when (a) {
            Uploader.Ausgang.Fertig -> {
                marken.ausstehend(f.name)?.let { m -> m.forEach { (art, wert) -> setze(art, wert) } }
                marken.merkeAusstehend(f.name, null)
                f.delete()
                gesendet++
                null
            }
            Uploader.Ausgang.Angehalten, Uploader.Ausgang.Veraendert -> spaeter(Lauf.SPAETER, Lauf.Ergebnis.WEITER)
            is Uploader.Ausgang.Voll -> haus(a.satz)
            is Uploader.Ausgang.Fehler -> when {
                a.code in Lauf.HAUS_NEIN -> haus(Lauf.satzFuer(a.code, a.satz))
                a.code == 429 -> spaeter(Lauf.BESCHAEFTIGT, Lauf.Ergebnis.BESCHAEFTIGT)
                a.code == 0 || a.code >= 500 -> spaeter(Lauf.UNERREICHBAR, Lauf.Ergebnis.UNERREICHBAR)
                a.code == 404 || a.code == 409 -> spaeter(Lauf.SPAETER, Lauf.Ergebnis.WEITER)
                else -> spaeter(a.satz ?: "Haus hat „${d.name}“ nicht angenommen (${a.code}); die App versucht es beim nächsten Mal wieder.", Lauf.Ergebnis.WEITER)
            }
        }
    }

    /** A mark only grows: a file of an older run never moves it back. */
    private fun setze(art: Art, wert: Long) {
        if (wert > marken.marke(art)) marken.setzeMarke(art, wert)
    }

    private fun haus(satz: String): Lauf.Ergebnis {
        if (gesendet > 0) gedaechtnis.merkeUebertragen(q, uhr(), gesendet)
        gedaechtnis.melde(q, Meldung(uhr(), Meldung.Art.HAUS, satz))
        return Lauf.Ergebnis.HALT
    }

    private fun spaeter(satz: String, e: Lauf.Ergebnis): Lauf.Ergebnis {
        if (gesendet > 0) gedaechtnis.merkeUebertragen(q, uhr(), gesendet)
        gedaechtnis.melde(q, Meldung(uhr(), Meldung.Art.SPAETER, satz))
        return e
    }

    /** The app's own folder as an [Ordner], for the [Uploader]. */
    private class Eigener(private val wurzel: File) : Ordner {
        override fun dateien(unter: String, tief: Boolean): List<Datei>? =
            wurzel.listFiles()?.filter { it.isFile }?.map { Datei(it.name, it.length(), it.lastModified()) }

        override fun oeffnen(pfad: String, ab: Long): InputStream {
            val f = File(wurzel, pfad)
            if (!f.isFile) throw FileNotFoundException(pfad)
            return FileInputStream(f).also { it.channel.position(ab) }
        }
    }

    companion object {
        const val ENDUNG = ".age"
        const val SMS = "sms"
        const val ANRUFE = "anrufe"

        const val KEIN_SCHLUESSEL =
            "Auf der Box gibt es noch keinen Schlüssel für dein Archiv: frag die Person, die sich um die Box kümmert."
        const val KEINE_ERLAUBNIS =
            "Die App darf SMS oder Anrufliste nicht mehr lesen: schalte „SMS und Anrufliste übertragen“ hier noch einmal ein."

        /** `20261102T031245Z`: when the file was written, in UTC. */
        fun stempel(ms: Long): String =
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    }
}
