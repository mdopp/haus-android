package cloud.dopp.haus.sicherung

import cloud.dopp.haus.data.SicherungClient
import java.io.File
import java.io.IOException

/** The phone's address book as vCards. On the phone it is [HandyKontakte]; in the tests a list. */
interface Adressbuch {
    /** Every contact as a vCard with a UID, concatenated into [datei]: how many cards. */
    fun exportieren(datei: File): Int
}

/** What the contacts' run remembers for the screen: its last word, and what Haus's mirror came to. */
interface Kontaktgedaechtnis {
    fun meldeKontakte(meldung: Meldung)
    fun merkeKontakte(zeit: Long, ergebnis: SicherungClient.Kontakte)
}

/**
 * „Kontakte übertragen“ (laib ADR 0029), once in the daily run: every
 * contact as a vCard to `POST /napi/kontakte`, the whole set each time, so
 * Haus's address book „Handy-Kontakte“ mirrors the phone. No contact on the
 * phone: nothing is sent (Haus would refuse an empty set, 409).
 */
class KontakteLauf(
    private val client: SicherungClient,
    private val gedaechtnis: Kontaktgedaechtnis,
    private val adressbuch: Adressbuch,
    /** The app's own folder for the file under way (the cache). */
    private val ordner: File,
    private val uhr: () -> Long = System::currentTimeMillis,
) {
    fun laufen(): Lauf.Ergebnis {
        ordner.mkdirs()
        val datei = File(ordner, DATEI)
        try {
            val n = try {
                adressbuch.exportieren(datei)
            } catch (e: IOException) {
                gedaechtnis.meldeKontakte(Meldung(uhr(), Meldung.Art.SPAETER, Lauf.SPAETER))
                return Lauf.Ergebnis.FERTIG
            } catch (e: SecurityException) {
                gedaechtnis.meldeKontakte(Meldung(uhr(), Meldung.Art.KEINE, KEINE_ERLAUBNIS))
                return Lauf.Ergebnis.FERTIG
            }
            if (n == 0) {
                gedaechtnis.meldeKontakte(Meldung(uhr(), Meldung.Art.KEINE, KEINE))
                return Lauf.Ergebnis.FERTIG
            }
            val e = try {
                client.kontakte(datei)
            } catch (e: SicherungClient.Abgelehnt) {
                return when {
                    e.code == 429 -> spaeter(Lauf.BESCHAEFTIGT, Lauf.Ergebnis.BESCHAEFTIGT)
                    // another upload of this person under way, or the book changed meanwhile: again soon
                    e.code == 409 -> spaeter(e.satz ?: Lauf.SPAETER, Lauf.Ergebnis.WEITER)
                    e.code >= 500 && e.code != 503 && e.code != 507 -> spaeter(e.satz ?: Lauf.UNERREICHBAR, Lauf.Ergebnis.UNERREICHBAR)
                    else -> {
                        val satz = if (e.code == 401) Lauf.NICHT_GEKOPPELT else e.satz ?: "Haus hat die Kontakte nicht angenommen (${e.code})."
                        gedaechtnis.meldeKontakte(Meldung(uhr(), Meldung.Art.HAUS, satz))
                        Lauf.Ergebnis.HALT
                    }
                }
            } catch (e: IOException) {
                return spaeter(Lauf.UNERREICHBAR, Lauf.Ergebnis.UNERREICHBAR)
            }
            val jetzt = uhr()
            gedaechtnis.merkeKontakte(jetzt, e)
            gedaechtnis.meldeKontakte(Meldung(jetzt, Meldung.Art.OK))
            return Lauf.Ergebnis.FERTIG
        } finally {
            datei.delete()
        }
    }

    private fun spaeter(satz: String, e: Lauf.Ergebnis): Lauf.Ergebnis {
        gedaechtnis.meldeKontakte(Meldung(uhr(), Meldung.Art.SPAETER, satz))
        return e
    }

    companion object {
        const val DATEI = "kontakte.vcf"
        const val KEINE = "Auf dem Handy sind keine Kontakte: nichts übertragen."
        const val KEINE_ERLAUBNIS = "Die App darf die Kontakte nicht mehr lesen: schalte „Kontakte übertragen“ hier noch einmal ein."
    }
}
