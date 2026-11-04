package cloud.dopp.homes.data

import cloud.dopp.homes.data.Napi.PunktJson
import cloud.dopp.homes.data.Napi.StandortJson
import cloud.dopp.homes.data.Napi.StandortStandJson
import cloud.dopp.homes.standort.Punkt
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Standortverlauf (owner 2026-11-02): the app's points to Homes, which passes
 * them on to the person's Reitti (`/napi/standort`). Like [SicherungClient]
 * it does not pass [ScreenGate]: the points go in the background, with the
 * screen off, piggybacked on the location deliveries that woke the phone
 * anyway.
 *
 * Blocking; call it off the main thread. A transport failure is an
 * [IOException]: the caller keeps the batch.
 */
class StandortClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    /** What Homes made of a batch. */
    sealed class Ergebnis {
        /** Reitti has the points; [verworfen] of them Homes could not read and left out. */
        data class Angenommen(val angenommen: Int, val verworfen: Int) : Ergebnis()

        /** Homes kept nothing: the batch stays and comes again. [grund] is Homes' word for why, [satz] its sentence. */
        data class Behalten(val code: Int, val grund: String?, val satz: String?) : Ergebnis()

        /** 401: the device is not paired (any more). */
        object NichtGekoppelt : Ergebnis()
    }

    /** Whether the person connected Reitti on Homes. */
    sealed class Verbindung {
        data class Verbunden(val seit: String?) : Verbindung()
        object NichtVerbunden : Verbindung()

        /** Homes' sentence why not, e.g. the box has no Reitti (503). */
        data class Fehler(val satz: String) : Verbindung()
        object NichtGekoppelt : Verbindung()
        object Unerreichbar : Verbindung()
    }

    fun verbindung(): Verbindung = try {
        http.newCall(Napi.STANDORT_STAND.request(base, token)).execute().use { resp ->
            verbindungAus(resp.code, resp.body?.string())
        }
    } catch (e: IOException) {
        Verbindung.Unerreichbar
    }

    /** Sends [punkte] (at most [cloud.dopp.homes.standort.Senden.JE_ANFRAGE]); throws [IOException] when Homes was not reached. */
    fun senden(punkte: List<Punkt>): Ergebnis {
        val body = Napi.STANDORT.json(JSONArray().apply { punkte.forEach { put(json(it)) } }).asBody()
        return http.newCall(Napi.STANDORT.request(base, token, body = body)).execute().use { resp ->
            ergebnisAus(resp.code, resp.body?.string())
        }
    }

    companion object {
        /** Short: a batch goes while a location delivery holds the phone awake. */
        private val HTTP: OkHttpClient by lazy {
            HomesHttp.client(connect = 10, read = 25, write = 15)
        }

        /** [ms] as RFC 3339 in UTC, to the millisecond. */
        fun zeit(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        /** One point as Homes reads it. */
        fun json(p: Punkt): JSONObject = JSONObject().apply {
            put(PunktJson.ZEIT, zeit(p.zeit))
            put(PunktJson.BREITE, p.breite)
            put(PunktJson.LAENGE, p.laenge)
            put(PunktJson.GENAUIGKEIT, p.genauigkeit.toDouble())
            p.hoehe?.let { put(PunktJson.HOEHE, it) }
            p.tempo?.let { put(PunktJson.TEMPO, it.toDouble()) }
        }

        /** A `POST /napi/standort` reply. Only a 2xx with `angenommen` lets the batch go. */
        fun ergebnisAus(code: Int, body: String?): Ergebnis {
            if (code == 401) return Ergebnis.NichtGekoppelt
            val o = HomesHttp.json(body)
            if (code in 200..299 && o != null && o.has(StandortJson.ANGENOMMEN)) {
                return Ergebnis.Angenommen(o.optInt(StandortJson.ANGENOMMEN), o.optInt(StandortJson.VERWORFEN))
            }
            return Ergebnis.Behalten(code, HomesHttp.text(o, StandortJson.GRUND), HomesHttp.text(o, StandortJson.FEHLER))
        }

        /** A `GET /napi/standort` reply. */
        fun verbindungAus(code: Int, body: String?): Verbindung {
            if (code == 401) return Verbindung.NichtGekoppelt
            val o = HomesHttp.json(body)
            if (code !in 200..299) {
                return Verbindung.Fehler(HomesHttp.text(o, StandortStandJson.FEHLER) ?: "Homes hat nicht gesagt, ob Reitti verbunden ist ($code).")
            }
            if (o == null || !o.has(StandortStandJson.VERBUNDEN)) {
                return Verbindung.Fehler("Homes hat nicht lesbar gesagt, ob Reitti verbunden ist.")
            }
            if (!o.optBoolean(StandortStandJson.VERBUNDEN)) return Verbindung.NichtVerbunden
            val seit = if (o.isNull(StandortStandJson.SEIT)) null else o.optString(StandortStandJson.SEIT).ifBlank { null }
            return Verbindung.Verbunden(seit)
        }
    }
}
