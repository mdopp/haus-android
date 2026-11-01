package cloud.dopp.haus.data

import cloud.dopp.haus.data.Napi.DateiJson
import cloud.dopp.haus.data.Napi.FehlendJson
import cloud.dopp.haus.data.Napi.StandJson
import cloud.dopp.haus.data.Napi.TeilJson
import cloud.dopp.haus.data.Napi.UploadJson
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Nachrichten sichern (#194, laib ADR 0029): the calls that bring the phone's
 * WhatsApp and Signal backups to Haus, against the contract Haus builds in
 * haus#207 — what reached the box, which media are missing, and a resumable
 * upload (create, HEAD for the offset, PATCH the next bytes).
 *
 * The one client that does **not** pass [ScreenGate]: the daily backup runs
 * while the phone charges, mostly with the screen off, and only on an
 * unmetered network (the owner's call in #194; docs/CONTRACT.md (e)). The
 * widgets' calls keep the gate.
 *
 * Blocking; call it off the main thread. A transport failure is an
 * [IOException]; an answer the caller cannot go on from is [Abgelehnt], with
 * Haus's German sentence when it sent one.
 */
class SicherungClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    /**
     * Haus answered [code], with its sentence [satz] (`{"fehler": …}`) when it
     * sent one. A 401 comes from the shared login check as `{"error":"unauthorized"}`:
     * no sentence, the app says "pair again" itself.
     */
    class Abgelehnt(val code: Int, val satz: String?) : IOException("Haus answered $code")

    /** One source as Haus reports it: when the last file arrived (RFC 3339), how many, how big, how many wait. */
    data class QuelleStand(val zuletzt: String?, val dateien: Long, val bytes: Long, val wartend: Long)

    /** What `GET /napi/sicherung/stand` came to. */
    sealed class Stand {
        data class Da(val quellen: Map<Napi.Quelle, QuelleStand>, val freiBytes: Long?) : Stand()

        /** Haus's own sentence why not, e.g. the box has no archive yet (503). */
        data class Fehler(val satz: String) : Stand()
        object NichtGekoppelt : Stand()
        object Unerreichbar : Stand()
    }

    /** How a PATCH came out. */
    sealed class Teil {
        /** Taken: Haus now holds [offset] bytes; [fertig] once the file is whole and in the inbox. */
        data class Weiter(val offset: Long, val fertig: Boolean) : Teil()

        /** 409: Haus holds [offset] bytes, not what the PATCH said; go on from there. */
        data class Versatz(val offset: Long) : Teil()

        /** 404: Haus does not know this upload (any more); start a new one. */
        object Unbekannt : Teil()

        /** 507: the box is full; [satz] says so. */
        data class Voll(val satz: String) : Teil()
    }

    @Volatile
    private var laufend: Call? = null

    /** Cut the request in flight, if any: the worker was stopped. */
    fun abbrechen() {
        laufend?.cancel()
    }

    private fun <T> rufe(req: Request, lies: (Response) -> T): T {
        val call = http.newCall(req)
        laufend = call
        try {
            return call.execute().use(lies)
        } finally {
            laufend = null
        }
    }

    private fun json(endpoint: Napi.Endpoint, body: JSONObject): Request =
        endpoint.request(base, token, body = body.asBody())

    /** What reached the box, per source. Never throws. */
    fun stand(): Stand = try {
        rufe(Napi.SICHERUNG_STAND.request(base, token)) { resp -> standAus(resp.code, resp.body?.string()) }
    } catch (e: IOException) {
        Stand.Unerreichbar
    }

    /**
     * Which of [dateien] (path, size) Haus does not have yet, at most
     * [Napi.FEHLEND_MAX] at a time. Only paths that were asked about come back.
     */
    fun fehlend(quelle: Napi.Quelle, dateien: List<Pair<String, Long>>): Set<String> {
        require(dateien.size <= Napi.FEHLEND_MAX) { "at most ${Napi.FEHLEND_MAX} files a call" }
        // One dot-name anywhere in one path makes Haus refuse the whole question (400).
        require(dateien.none { (pfad, _) -> pfad.split('/').any { it.startsWith(".") } }) { "a dot-name in a path" }
        val liste = JSONArray()
        dateien.forEach { (pfad, groesse) -> liste.put(JSONObject().put(DateiJson.PFAD, pfad).put(DateiJson.GROESSE, groesse)) }
        val gefragt = dateien.map { it.first }.toSet()
        return rufe(json(Napi.SICHERUNG_FEHLEND, Napi.SICHERUNG_FEHLEND.json(quelle.wire, liste))) { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) throw Abgelehnt(resp.code, satzAus(body))
            val arr = runCatching { JSONObject(body.orEmpty()).optJSONArray(FehlendJson.FEHLEND) }.getOrNull()
                ?: throw Abgelehnt(resp.code, null)
            (0 until arr.length()).map { arr.optString(it) }.filter { it in gefragt }.toSet()
        }
    }

    /** Start the upload of [pfad] ([groesse] bytes), or resume Haus's partial one: its id and offset. */
    fun anlegen(quelle: Napi.Quelle, pfad: String, groesse: Long): Pair<String, Long> {
        val body = Napi.SICHERUNG_UPLOADS.json(quelle.wire, pfad, groesse)
        return rufe(json(Napi.SICHERUNG_UPLOADS, body)) { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw Abgelehnt(resp.code, satzAus(text))
            val o = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: throw Abgelehnt(resp.code, null)
            val id = o.optString(UploadJson.ID)
            val offset = o.optLong(UploadJson.OFFSET, -1)
            if (id.isBlank() || offset < 0) throw Abgelehnt(resp.code, null)
            id to offset
        }
    }

    /** How many bytes of upload [id] Haus holds; null when it does not know the upload (404). */
    fun offset(id: String): Long? {
        val endpoint = Napi.SICHERUNG_UPLOAD_STAND.with(id)
        return rufe(endpoint.request(base, token, endpoint.path)) { resp ->
            if (resp.code == 404) return@rufe null
            if (!resp.isSuccessful) throw Abgelehnt(resp.code, null)
            resp.replyHeader(endpoint, Napi.UPLOAD_OFFSET)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw Abgelehnt(resp.code, null)
        }
    }

    /** Send [laenge] bytes of [puffer] as upload [id]'s bytes from [offset] on. */
    fun senden(id: String, offset: Long, puffer: ByteArray, laenge: Int): Teil {
        val endpoint = Napi.SICHERUNG_UPLOAD_TEIL.with(id)
        val body = puffer.toRequestBody(OFFSET_STREAM, 0, laenge)
        val req = endpoint.request(base, token, endpoint.path, body, mapOf(Napi.UPLOAD_OFFSET to offset.toString()))
        return rufe(req) { resp -> teilAus(resp.code, resp.body?.string()) }
    }

    companion object {
        /** One client for the backups: a PATCH is up to 8 MiB, and Haus writes it to disk before it answers. */
        private val HTTP: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        private val OFFSET_STREAM = Napi.OFFSET_STREAM.toMediaType()

        /** Haus's sentence in an error body, `{"fehler": …}`, or null. */
        fun satzAus(body: String?): String? =
            runCatching { JSONObject(body.orEmpty()).optString(UploadJson.FEHLER) }.getOrNull()?.trim()?.ifBlank { null }

        /** A `stand` reply as the screen shows it. */
        fun standAus(code: Int, body: String?): Stand {
            if (code == 401) return Stand.NichtGekoppelt
            if (code !in 200..299) {
                return Stand.Fehler(satzAus(body) ?: "Haus hat den Stand der Sicherungen nicht gemeldet ($code).")
            }
            val o = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
                ?: return Stand.Fehler("Haus hat den Stand der Sicherungen nicht lesbar gemeldet.")
            val quellen = o.optJSONObject(StandJson.QUELLEN)
            val stand = LinkedHashMap<Napi.Quelle, QuelleStand>()
            Napi.Quelle.entries.forEach { q ->
                val s = quellen?.optJSONObject(q.wire) ?: return@forEach
                stand[q] = QuelleStand(
                    zuletzt = if (s.isNull(StandJson.ZULETZT)) null else s.optString(StandJson.ZULETZT).ifBlank { null },
                    dateien = s.optLong(StandJson.DATEIEN),
                    bytes = s.optLong(StandJson.BYTES),
                    wartend = s.optLong(StandJson.WARTEND),
                )
            }
            val frei = if (o.has(StandJson.FREI_BYTES)) o.optLong(StandJson.FREI_BYTES, -1).takeIf { it >= 0 } else null
            return Stand.Da(stand, frei)
        }

        /**
         * A PATCH reply. A refusal after a partial write (409, and 408 when the
         * connection was cut while Haus wrote) carries Haus's offset: go on from
         * there. 507 stops; anything else is [Abgelehnt].
         */
        fun teilAus(code: Int, body: String?): Teil {
            val o = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            val offset = o?.optLong(TeilJson.OFFSET, -1) ?: -1
            return when (code) {
                200 -> {
                    if (offset < 0) throw Abgelehnt(code, null)
                    Teil.Weiter(offset, o?.optBoolean(TeilJson.FERTIG) ?: false)
                }
                409, 408 -> {
                    if (offset < 0) throw Abgelehnt(code, satzAus(body))
                    Teil.Versatz(offset)
                }
                404 -> Teil.Unbekannt
                507 -> Teil.Voll(satzAus(body) ?: "Auf der Box ist kein Platz mehr für die Sicherung.")
                else -> throw Abgelehnt(code, satzAus(body))
            }
        }
    }
}
