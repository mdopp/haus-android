package cloud.dopp.homes.data

import cloud.dopp.homes.data.Napi.ImportJson
import cloud.dopp.homes.data.Napi.ImportStandJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Standortverlauf übernehmen (owner 2026-11-03): a location history shared
 * to the app — Google Maps' Timeline.json, a Takeout Records.json, GPX,
 * GeoJSON — goes to Homes over the resumable upload backups use (create,
 * HEAD for the offset, PATCH the next bytes), and Homes brings it into the
 * person's Reitti; then the app asks how far that got.
 *
 * Blocking; call it off the main thread. A transport failure is an
 * [IOException]; an answer the caller cannot go on from is [Abgelehnt], with
 * Homes' German sentence and its word for why when it sent them.
 */
class StandortImportClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    /** Homes answered [code], with its sentence [satz] and its [grund] when it sent them. */
    class Abgelehnt(val code: Int, val satz: String?, val grund: String?) : IOException("Homes answered $code")

    /** How far an import got. [text] once it is [Napi.IMPORT_FERTIG], [fehler] and [grund] once it is [Napi.IMPORT_FEHLER]. */
    data class Stand(
        val stand: String,
        val gelesen: Long,
        val groesse: Long,
        val punkte: Long,
        val text: String?,
        val fehler: String?,
        val grund: String?,
    )

    /** Start (or resume) the upload of [name], [groesse] bytes: its id and how much Homes has. */
    fun anlegen(name: String, groesse: Long): Pair<String, Long> {
        val req = Napi.STANDORT_IMPORT.request(base, token, body = Napi.STANDORT_IMPORT.json(name, groesse).asBody())
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw abgelehnt(resp.code, text)
            val o = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: throw Abgelehnt(resp.code, null, null)
            val id = o.optString(ImportJson.ID)
            val offset = o.optLong(ImportJson.OFFSET, -1)
            if (id.isBlank() || offset < 0) throw Abgelehnt(resp.code, null, null)
            id to offset
        }
    }

    /** How many bytes of upload [id] Homes holds; null when it does not know it (404). */
    fun offset(id: String): Long? {
        val endpoint = Napi.STANDORT_IMPORT_OFFSET.with(id)
        return http.newCall(endpoint.request(base, token, endpoint.path)).execute().use { resp ->
            if (resp.code == 404) return@use null
            if (!resp.isSuccessful) throw Abgelehnt(resp.code, null, null)
            resp.replyHeader(endpoint, Napi.UPLOAD_OFFSET)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw Abgelehnt(resp.code, null, null)
        }
    }

    /** Send [laenge] bytes of [puffer] as upload [id]'s bytes from [offset] on; read as a backup's piece. */
    fun senden(id: String, offset: Long, puffer: ByteArray, laenge: Int): SicherungClient.Teil {
        val endpoint = Napi.STANDORT_IMPORT_TEIL.with(id)
        val body = puffer.toRequestBody(OFFSET_STREAM, 0, laenge)
        val req = endpoint.request(base, token, endpoint.path, body, mapOf(Napi.UPLOAD_OFFSET to offset.toString()))
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string()
            try {
                SicherungClient.teilAus(resp.code, text)
            } catch (e: SicherungClient.Abgelehnt) {
                throw abgelehnt(resp.code, text)
            }
        }
    }

    /** Where import [id] stands. */
    fun stand(id: String): Stand {
        val endpoint = Napi.STANDORT_IMPORT_STAND.with(id)
        return http.newCall(endpoint.request(base, token, endpoint.path)).execute().use { resp ->
            standAus(resp.code, resp.body?.string())
        }
    }

    companion object {
        /** A PATCH is up to 8 MiB, and Homes writes it to disk before it answers. */
        private val HTTP: OkHttpClient by lazy {
            HomesHttp.client(connect = 15, read = 60, write = 60)
        }

        private val OFFSET_STREAM = Napi.OFFSET_STREAM.toMediaType()

        /** A refusal with Homes' sentence and word, `{fehler, grund}`, when it sent them. */
        fun abgelehnt(code: Int, body: String?): Abgelehnt {
            val o = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            return Abgelehnt(
                code,
                o?.optString(ImportJson.FEHLER)?.trim()?.ifBlank { null },
                o?.optString(ImportJson.GRUND)?.trim()?.ifBlank { null },
            )
        }

        /** A `GET …/import/{id}` reply; a refusal is [Abgelehnt]. */
        fun standAus(code: Int, body: String?): Stand {
            if (code !in 200..299) throw abgelehnt(code, body)
            val o = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: throw Abgelehnt(code, null, null)
            val stand = o.optString(ImportStandJson.STAND)
            if (stand.isBlank()) throw Abgelehnt(code, null, null)
            fun text(key: String) = if (o.isNull(key)) null else o.optString(key).trim().ifBlank { null }
            return Stand(
                stand = stand,
                gelesen = o.optLong(ImportStandJson.GELESEN),
                groesse = o.optLong(ImportStandJson.GROESSE),
                punkte = o.optLong(ImportStandJson.PUNKTE),
                text = text(ImportStandJson.TEXT),
                fehler = text(ImportStandJson.FEHLER),
                grund = text(ImportStandJson.GRUND),
            )
        }
    }
}
