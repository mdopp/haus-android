package cloud.dopp.haus.standort

import cloud.dopp.haus.data.Napi
import cloud.dopp.haus.data.StandortClient.Ergebnis
import java.io.IOException

/** Where the points go: Haus's `/napi/standort` on the phone ([cloud.dopp.haus.data.StandortClient]), a fake in the tests. */
fun interface Kanal {
    /** Throws [IOException] when Haus was not reached. */
    fun senden(punkte: List<Punkt>): Ergebnis
}

/** What the sending remembers between wakes: the failures in a row, the last word, the last batch Reitti took. */
interface Sendegedaechtnis {
    val fehlschlaege: Int
    val letzterFehlschlag: Long
    fun fehlgeschlagen(zeit: Long, satz: String?)
    fun gesendet(zeit: Long, anzahl: Int, verworfen: Int)
}

/**
 * Sends the buffer's oldest points to Haus, a batch at a time, when
 * [Senden.faellig] says so. A batch leaves the buffer only when Haus says
 * Reitti has it; any other answer keeps it, counts a failure and sets the
 * pause before the next try.
 */
class Sender(
    private val puffer: Puffer,
    private val gedaechtnis: Sendegedaechtnis,
    private val kanal: Kanal,
    private val uhr: () -> Long = System::currentTimeMillis,
) {
    /** How a run came out. */
    enum class Lauf { NICHTS_FAELLIG, ALLES_GESENDET, TEIL_GESENDET, BEHALTEN }

    /** Up to [hoechstens] batches now, if due ([gedraengt]: the person asked, the pause does not hold). */
    fun senden(modus: Modus, gedraengt: Boolean = false, hoechstens: Int = 3): Lauf {
        val faellig = Senden.faellig(
            uhr(), modus, puffer.anzahl(), puffer.aeltester(),
            gedaechtnis.fehlschlaege, gedaechtnis.letzterFehlschlag, gedraengt,
        )
        if (!faellig) return Lauf.NICHTS_FAELLIG
        repeat(hoechstens) {
            val stapel = puffer.stapel(Senden.JE_ANFRAGE)
            if (stapel.zeilen == 0) return Lauf.ALLES_GESENDET
            if (stapel.punkte.isEmpty()) { // only broken lines: nothing to send, nothing lost
                puffer.weg(stapel.zeilen)
                return@repeat
            }
            val e = try {
                kanal.senden(stapel.punkte)
            } catch (e: IOException) {
                gedaechtnis.fehlgeschlagen(uhr(), UNERREICHBAR)
                return Lauf.BEHALTEN
            }
            when (e) {
                is Ergebnis.Angenommen -> {
                    puffer.weg(stapel.zeilen)
                    gedaechtnis.gesendet(uhr(), e.angenommen, e.verworfen)
                }
                is Ergebnis.Behalten -> {
                    gedaechtnis.fehlgeschlagen(uhr(), satz(e))
                    return Lauf.BEHALTEN
                }
                Ergebnis.NichtGekoppelt -> {
                    gedaechtnis.fehlgeschlagen(uhr(), NICHT_GEKOPPELT)
                    return Lauf.BEHALTEN
                }
            }
        }
        return if (puffer.anzahl() == 0) Lauf.ALLES_GESENDET else Lauf.TEIL_GESENDET
    }

    private fun satz(e: Ergebnis.Behalten): String = when {
        e.satz != null -> e.satz
        e.grund == Napi.GRUND_KEIN_SCHLUESSEL -> KEIN_SCHLUESSEL
        else -> "Haus hat die Punkte nicht angenommen (${e.code}). Die App behält sie und versucht es später noch einmal."
    }

    companion object {
        const val UNERREICHBAR = "Haus ist gerade nicht erreichbar. Die App behält die Punkte und schickt sie später."
        const val NICHT_GEKOPPELT = "Die Haus-App ist nicht mehr mit Haus gekoppelt. Kopple sie neu; die Punkte bleiben so lange auf dem Handy."
        const val KEIN_SCHLUESSEL = "Verbinde zuerst Reitti: füge in Haus unter Mehr → Standortverlauf einmal deinen Reitti-Schlüssel ein."
    }
}
