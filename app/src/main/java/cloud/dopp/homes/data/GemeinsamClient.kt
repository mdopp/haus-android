package cloud.dopp.homes.data

import cloud.dopp.homes.data.Napi.GemeinsamJson
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * The Homes account (#206): the household's shared contacts and calendars
 * from Homes (`/napi/gemeinsam/…`, homes#218), for the account's two sync
 * adapters. Like [SicherungClient] it does not pass [ScreenGate]: Android
 * runs the syncs when it likes, mostly with the screen off.
 *
 * Blocking; call it off the main thread.
 */
class GemeinsamClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    /** What a fetch came to. */
    sealed class Antwort {
        /** The state Homes has: [text] (vCard or iCalendar), and its [etag] for next time. */
        data class Neu(val text: String, val etag: String?) : Antwort()

        /** 304: the phone has this state already. */
        object Gleich : Antwort()

        /** Homes refused with [code], in its sentence [satz] when it sent one. */
        data class Abgelehnt(val code: Int, val satz: String?) : Antwort()

        /** No answer: no network, or Homes not reachable. */
        object Unerreichbar : Antwort()
    }

    fun kontakte(etag: String?): Antwort = hole(Napi.GEMEINSAM_KONTAKTE, etag)

    fun kalender(etag: String?): Antwort = hole(Napi.GEMEINSAM_KALENDER, etag)

    private fun hole(endpoint: Napi.Endpoint, etag: String?): Antwort = try {
        val req = endpoint.request(base, token, headers = mapOf(Napi.IF_NONE_MATCH to etag.orEmpty()))
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 304 -> Antwort.Gleich
                resp.code in 200..299 -> Antwort.Neu(resp.body?.string().orEmpty(), resp.replyHeader(endpoint, Napi.ETAG))
                else -> Antwort.Abgelehnt(resp.code, HomesHttp.satz(resp.body?.string(), GemeinsamJson.FEHLER))
            }
        }
    } catch (e: IOException) {
        Antwort.Unerreichbar
    }

    companion object {
        private val HTTP: OkHttpClient by lazy {
            HomesHttp.client(connect = 15, read = 60)
        }
    }
}
