package cloud.dopp.homes.data

import cloud.dopp.homes.data.Napi.GemeinsamJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
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

    /**
     * One entry back to Homes (owner, 2026-11-05): appointment [uid] of calendar [kalender] (null: card [uid])
     * as [text] (null: deleted) over [etag] (null: new). [Antwort.Neu] carries Homes' new ETag; 412 is
     * [Antwort.Abgelehnt]: Homes has another version, and it wins.
     */
    fun schreiben(kalender: String?, uid: String, text: String?, etag: String?): Antwort = try {
        val endpoint = when {
            kalender == null -> if (text == null) Napi.GEMEINSAM_KARTE_WEG.with(uid) else Napi.GEMEINSAM_KARTE.with(uid)
            text == null -> Napi.GEMEINSAM_TERMIN_WEG.with(kalender, uid)
            else -> Napi.GEMEINSAM_TERMIN.with(kalender, uid)
        }
        val body = text?.toRequestBody("${if (kalender == null) Napi.VCARD else Napi.ICAL}; charset=utf-8".toMediaType())
        val headers = mapOf(Napi.IF_MATCH to etag.orEmpty()) + (if (text == null) emptyMap() else mapOf(Napi.IF_NONE_MATCH to if (etag == null) "*" else ""))
        http.newCall(endpoint.request(base, token, endpoint.path, body, headers)).execute().use { resp ->
            if (resp.isSuccessful) {
                Antwort.Neu("", resp.replyHeader(endpoint, Napi.ETAG))
            } else {
                Antwort.Abgelehnt(resp.code, HomesHttp.satz(resp.body?.string(), GemeinsamJson.FEHLER))
            }
        }
    } catch (e: IOException) {
        Antwort.Unerreichbar
    }

    private fun hole(endpoint: Napi.Endpoint, etag: String?): Antwort = try {
        val req = endpoint.request(base, token, endpoint.target("1"), headers = mapOf(Napi.IF_NONE_MATCH to etag.orEmpty()))
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
