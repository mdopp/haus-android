package cloud.dopp.homes.hochladen

import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.MusikJson
import cloud.dopp.homes.data.request
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException

/**
 * `POST /napi/musik` (owner, 2026-11-04): one song or ZIP as one `file`
 * part, streamed from the app's copy — never held in memory — saying how
 * many bytes went. Homes takes up to 4 GB and gives a slow line an hour.
 * Blocking; the Hochladen job calls it.
 */
class MusikClient(private val base: String, private val token: String, private val http: OkHttpClient = HTTP) {

    /** How one file ended. */
    sealed class Ausgang {
        /** Homes has it, under [dateien] (its names for them). */
        data class Fertig(val dateien: List<String>) : Ausgang()

        /** Homes said no with [code], in its sentence [satz] when it sent one. */
        data class Abgelehnt(val code: Int, val satz: String?) : Ausgang()

        /** 404: a box without music, or a Homes before it. */
        object Fehlt : Ausgang()

        /** No answer: the line or the job stopped; it goes again from the start. */
        object Offline : Ausgang()
    }

    // No answer is an answer here: the job tries again later.
    @Suppress("SwallowedException")
    fun senden(datei: File, name: String, gesendet: (Long) -> Unit = {}, halt: () -> Boolean = { false }): Ausgang = try {
        val teil = DateiBody(datei, OCTET, gesendet, halt)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart(Napi.MUSIK.body[0], name, teil).build()
        http.newCall(Napi.MUSIK.request(base, token, body = body)).execute().use { resp ->
            val text = resp.body?.string()
            val o = HomesHttp.json(text)
            when {
                resp.isSuccessful && o?.optBoolean(MusikJson.OK) == true -> Ausgang.Fertig(
                    o.optJSONArray(MusikJson.DATEIEN)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                )
                resp.code == 404 -> Ausgang.Fehlt
                else -> Ausgang.Abgelehnt(resp.code, HomesHttp.text(o, MusikJson.ERROR))
            }
        }
    } catch (e: IOException) {
        Ausgang.Offline
    }

    /** The file as a request body, read in pieces while it is written, each counted; [halt] ends it between two. */
    private class DateiBody(
        private val datei: File,
        private val typ: MediaType,
        private val gesendet: (Long) -> Unit,
        private val halt: () -> Boolean,
    ) : RequestBody() {
        override fun contentType() = typ
        override fun contentLength() = datei.length()
        override fun writeTo(sink: BufferedSink) {
            datei.source().use { src ->
                var summe = 0L
                while (true) {
                    if (halt()) throw IOException("stopped")
                    val n = src.read(sink.buffer, STUECK)
                    if (n < 0) break
                    sink.flush()
                    summe += n
                    gesendet(summe)
                }
            }
        }
    }

    companion object {
        private const val STUECK = 256 * 1024L
        private val OCTET = "application/octet-stream".toMediaType()

        /** Homes reads for up to an hour; the answer comes once the file is whole. */
        private val HTTP: OkHttpClient by lazy { HomesHttp.client(connect = 15, read = 60 * 60, write = 60 * 60) }
    }
}
