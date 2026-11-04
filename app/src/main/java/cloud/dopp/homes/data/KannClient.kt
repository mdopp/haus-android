package cloud.dopp.homes.data

import cloud.dopp.homes.data.Napi.AnfordernJson
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException

/**
 * What the box can do (homes ADR 0001 §2, §10): `GET /napi/kann` with the
 * ETag of the answer the app keeps, and `POST /napi/kann/anfordern`, a wish
 * for an off module. Blocking; call it off the main thread.
 */
class KannClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    /** What a read came to. */
    sealed class Antwort {
        /** A new answer: its [body], and its [etag] for next time. */
        data class Neu(val body: String, val etag: String?) : Antwort()

        /** 304: the app has this answer already. */
        object Gleich : Antwort()

        /** 404: an older Homes without `/napi/kann`; the app shows everything, as before. */
        object Fehlt : Antwort()

        /** Anything else, or no answer: the kept answer stays. */
        object Nichts : Antwort()
    }

    /** What a wish came to. */
    sealed class Wunsch {
        /** Homes filed it, or one was open: the module's entry with its new state, as JSON. */
        data class Angenommen(val eintrag: String) : Wunsch()

        /** Homes refused with [code], in its sentence [satz] when it sent one (401 sends none). */
        data class Abgelehnt(val code: Int, val satz: String?) : Wunsch()

        /** No answer in time: Homes may have filed it all the same. */
        object Zeit : Wunsch()

        /** No connection to Homes. */
        object Offline : Wunsch()
    }

    // No answer is an answer here: the kept state stays, the caller says it in its words.
    @Suppress("SwallowedException")
    fun lesen(etag: String?): Antwort = try {
        val req = Napi.KANN.request(base, token, headers = mapOf(Napi.IF_NONE_MATCH to etag.orEmpty()))
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 304 -> Antwort.Gleich
                resp.code == 404 -> Antwort.Fehlt
                resp.isSuccessful -> Antwort.Neu(resp.body?.string().orEmpty(), resp.replyHeader(Napi.KANN, Napi.ETAG))
                else -> Antwort.Nichts
            }
        }
    } catch (e: IOException) {
        Antwort.Nichts
    }

    @Suppress("SwallowedException") // as [lesen]: the outcome is the answer
    fun anfordern(modul: String): Wunsch = try {
        val req = Napi.KANN_ANFORDERN.request(base, token, body = Napi.KANN_ANFORDERN.json(modul).asBody())
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (resp.isSuccessful) {
                Wunsch.Angenommen(body.orEmpty())
            } else {
                Wunsch.Abgelehnt(resp.code, if (resp.code == 401) null else HomesHttp.satz(body, AnfordernJson.ERROR))
            }
        }
    } catch (e: InterruptedIOException) {
        Wunsch.Zeit // a socket timeout: Homes waits up to 20 s for Laib
    } catch (e: IOException) {
        Wunsch.Offline
    }

    companion object {
        /** Homes gives Laib 20 s to file a wish; the read waits a little longer. */
        private val HTTP: OkHttpClient by lazy { HomesHttp.client(connect = 10, read = 30) }
    }
}
