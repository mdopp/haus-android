package cloud.dopp.homes.konto

import android.content.SyncResult
import cloud.dopp.homes.data.GemeinsamClient
import cloud.dopp.homes.sicherung.Lauf

/**
 * One sync of one part of the Homes account (#206), as its sync adapter
 * runs it: first send what changed on the phone ([senden], owner 2026-11-05),
 * then fetch Homes' state with the ETag of the last one written; on a
 * new state write it ([schreiben]); on Homes' 304 only check the phone still
 * holds what was written ([anzahl]), else fetch it whole again. Every
 * outcome leaves a sentence for the screen ([KontoStore]) and tells Android's
 * SyncManager whether to back off ([SyncResult]). Homes not reachable while
 * sending: nothing is fetched, so the phone's changes wait for the next run.
 */
abstract class KontoLauf(
    private val teil: HomesKonto.Teil,
    private val store: KontoStore,
    private val holen: (etag: String?) -> GemeinsamClient.Antwort,
) {

    /** Write Homes' state [text] to the phone: how many entries the account holds now. */
    protected abstract fun schreiben(text: String): Int

    /** How many entries the account holds on the phone. */
    protected abstract fun anzahl(): Int

    /** The sentence when Homes refused without one of its own. */
    protected abstract fun abgelehnt(code: Int): String

    /** The sentence when the app may no longer write the part. */
    protected abstract val keineErlaubnis: String

    /**
     * Send the phone's changed and deleted entries to Homes: a sentence when
     * Homes' version won over one of them (412) or it refused one, else null.
     * Throws [Spaeter] when Homes was not reachable.
     */
    protected abstract fun senden(): String?

    /** Homes did not answer a write: the entries stay changed until the next run. */
    class Spaeter(val satz: String = UNERREICHBAR) : Exception(satz)

    fun laufen(result: SyncResult) {
        try {
            val verworfen = senden()
            var antwort = holen(store.etag(teil))
            if (antwort == GemeinsamClient.Antwort.Gleich && store.stand(teil)?.anzahl != anzahl()) {
                store.vergissEtag(teil) // changed on the phone meanwhile: all of it again
                antwort = holen(null)
            }
            if (geholt(antwort, result)) verworfen?.let { store.melde(teil, it) }
        } catch (e: Spaeter) {
            result.stats.numIoExceptions++
            store.melde(teil, e.satz)
        } catch (e: SecurityException) {
            result.stats.numAuthExceptions++
            store.melde(teil, keineErlaubnis)
        } catch (e: Exception) {
            // the provider refused a batch (RemoteException, OperationApplicationException): again later
            result.stats.numIoExceptions++
            store.melde(teil, NICHT_GESCHRIEBEN)
        }
    }

    /** Homes' [antwort] written, or said: whether the run was a good one. */
    private fun geholt(antwort: GemeinsamClient.Antwort, result: SyncResult): Boolean {
        when (antwort) {
            is GemeinsamClient.Antwort.Neu -> store.geschafft(teil, antwort.etag, schreiben(antwort.text))
            GemeinsamClient.Antwort.Gleich -> store.geschafft(teil, null, null)
            is GemeinsamClient.Antwort.Abgelehnt -> {
                if (antwort.code == 401) {
                    result.stats.numAuthExceptions++
                    store.melde(teil, Lauf.NICHT_GEKOPPELT)
                } else {
                    if (antwort.code >= 500) result.stats.numIoExceptions++ else result.stats.numParseExceptions++
                    store.melde(teil, antwort.satz ?: abgelehnt(antwort.code))
                }
                return false
            }
            GemeinsamClient.Antwort.Unerreichbar -> {
                result.stats.numIoExceptions++
                store.melde(teil, UNERREICHBAR)
                return false
            }
        }
        return true
    }

    /**
     * What came of sending one entry ([GemeinsamClient.schreiben]): Homes' new ETag ("" when it named none) when
     * it took it; null when Homes' version wins (412, or a refusal) — then [verworfen] says so. Throws [Spaeter].
     */
    protected fun gesendet(a: GemeinsamClient.Antwort, verworfen: (String) -> Unit): String? = when (a) {
        is GemeinsamClient.Antwort.Neu -> a.etag.orEmpty()
        is GemeinsamClient.Antwort.Abgelehnt -> if (a.code >= 500 || a.code == 401) {
            throw Spaeter()
        } else {
            verworfen(if (a.code == 412) VERWORFEN else a.satz ?: VERWORFEN)
            null
        }
        else -> throw Spaeter()
    }

    companion object {
        const val VERWORFEN = "Ein Eintrag war im Haus inzwischen anders: Es gilt die Fassung aus dem Haus, die Änderung am Handy ist verworfen."
        const val UNERREICHBAR = "Homes war nicht erreichbar: Android versucht es bald von selbst wieder."
        const val NICHT_GESCHRIEBEN = "Das Handy hat die Einträge gerade nicht angenommen: Android versucht es bald wieder."
    }
}
