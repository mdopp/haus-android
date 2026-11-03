package cloud.dopp.homes.konto

import android.content.SyncResult
import cloud.dopp.homes.data.GemeinsamClient
import cloud.dopp.homes.sicherung.Lauf

/**
 * One sync of one part of the Homes account (#206), as its sync adapter
 * runs it: fetch Homes' state with the ETag of the last one written; on a
 * new state write it ([schreiben]); on Homes' 304 only check the phone still
 * holds what was written ([anzahl]), else fetch it whole again. Every
 * outcome leaves a sentence for the screen ([KontoStore]) and tells Android's
 * SyncManager whether to back off ([SyncResult]).
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

    fun laufen(result: SyncResult) {
        try {
            var antwort = holen(store.etag(teil))
            if (antwort == GemeinsamClient.Antwort.Gleich && store.stand(teil)?.anzahl != anzahl()) {
                store.vergissEtag(teil) // changed on the phone meanwhile: all of it again
                antwort = holen(null)
            }
            when (antwort) {
                is GemeinsamClient.Antwort.Neu -> {
                    val n = schreiben(antwort.text)
                    store.geschafft(teil, antwort.etag, n)
                }
                GemeinsamClient.Antwort.Gleich -> store.geschafft(teil, null, null)
                is GemeinsamClient.Antwort.Abgelehnt -> {
                    if (antwort.code == 401) {
                        result.stats.numAuthExceptions++
                        store.melde(teil, Lauf.NICHT_GEKOPPELT)
                    } else {
                        if (antwort.code >= 500) result.stats.numIoExceptions++ else result.stats.numParseExceptions++
                        store.melde(teil, antwort.satz ?: abgelehnt(antwort.code))
                    }
                }
                GemeinsamClient.Antwort.Unerreichbar -> {
                    result.stats.numIoExceptions++
                    store.melde(teil, UNERREICHBAR)
                }
            }
        } catch (e: SecurityException) {
            result.stats.numAuthExceptions++
            store.melde(teil, keineErlaubnis)
        } catch (e: Exception) {
            // the provider refused a batch (RemoteException, OperationApplicationException): again later
            result.stats.numIoExceptions++
            store.melde(teil, NICHT_GESCHRIEBEN)
        }
    }

    companion object {
        const val UNERREICHBAR = "Homes war nicht erreichbar: Android versucht es bald von selbst wieder."
        const val NICHT_GESCHRIEBEN = "Das Handy hat die Einträge gerade nicht angenommen: Android versucht es bald wieder."
    }
}
