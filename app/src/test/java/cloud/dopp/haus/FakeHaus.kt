package cloud.dopp.haus

import cloud.dopp.haus.sicherung.Datei
import cloud.dopp.haus.sicherung.Ordner
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Haus's side of Nachrichten sichern (#194) as the contract states it and
 * haus#208 builds it (`internal/napi/sicherung.go`), in memory: the id is the
 * same for the same file; a finished upload stays known (HEAD says its size,
 * a PATCH at its size answers `fertig`); a path with a dot-name at any level
 * is refused, and one such path fails a whole `fehlend` (400). Knobs for what
 * goes wrong: a connection cut mid-piece, a write lost (409), an upload
 * forgotten (404), a busy box (429), a full box (507), no archive (503).
 */
class FakeHaus : Dispatcher() {

    class Upload(val id: String, val quelle: String, val pfad: String, val groesse: Long) {
        val daten = ByteArrayOutputStream()
        var fertig = false
        val offset: Long get() = if (fertig) groesse else daten.size().toLong()
    }

    /** One request as it came: method, path, the offset header, the content type, the body size. */
    data class Anfrage(val method: String, val path: String, val offset: String?, val typ: String?, val auth: String?, val laenge: Long)

    val server = MockWebServer().also { it.dispatcher = this }
    val base: String get() = server.url("/").toString().trimEnd('/')

    /** A client that does not retry on its own: each test says when the next run starts. */
    val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(5, TimeUnit.SECONDS).build()

    val uploads = LinkedHashMap<String, Upload>()

    /** Files that arrived whole, by `quelle|pfad`. */
    val fertig = LinkedHashMap<String, ByteArray>()

    /** Media Haus has already, by `quelle|pfad`: `fehlend` leaves them out. */
    val vorhanden = HashSet<String>()

    val anfragen = ArrayList<Anfrage>()

    /** The next PATCH keeps only this many of its bytes, then the connection drops without an answer. */
    @Volatile var schneideNach: Int? = null

    /** Before the next PATCH, Haus loses this many bytes of that upload (a write lost in a crash). */
    @Volatile var verliere: Int? = null

    /** Haus forgets every upload (expired): HEAD and PATCH answer 404. */
    fun vergiss() = uploads.clear()

    /** The next this many PATCHes answer 429: the box takes other pieces. */
    @Volatile var beschaeftigt = 0

    @Volatile var voll = false
    @Volatile var keinArchiv = false
    @Volatile var fehlendAntworten = 0

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val body = request.body.readByteArray()
        synchronized(this) {
            anfragen += Anfrage(
                request.method.orEmpty(), path, request.getHeader("Upload-Offset"),
                request.getHeader("Content-Type"), request.getHeader("Authorization"), body.size.toLong(),
            )
        }
        if (request.getHeader("Authorization") != "Bearer haus_device_TEST") return json(401, JSONObject().put("error", "unauthorized"))
        if (keinArchiv) return fehler(503, "Auf dieser Box ist noch kein Nachrichten-Archiv eingerichtet.")
        return synchronized(this) {
            when {
                request.method == "GET" && path == "/napi/sicherung/stand" -> stand()
                request.method == "POST" && path == "/napi/sicherung/fehlend" -> fehlend(JSONObject(String(body)))
                request.method == "POST" && path == "/napi/sicherung/uploads" -> anlegen(JSONObject(String(body)))
                request.method == "HEAD" && path.startsWith("/napi/sicherung/uploads/") -> head(path.substringAfterLast('/'))
                request.method == "PATCH" && path.startsWith("/napi/sicherung/uploads/") ->
                    patch(path.substringAfterLast('/'), request.getHeader("Upload-Offset"), body)
                else -> json(404, JSONObject().put("error", "not found"))
            }
        }
    }

    private fun stand(): MockResponse {
        val q = JSONObject()
        listOf("whatsapp", "signal", "discord").forEach { name ->
            val dateien = fertig.filterKeys { it.startsWith("$name|") }
            q.put(
                name,
                JSONObject().put("zuletzt", if (dateien.isEmpty()) JSONObject.NULL else "2026-11-01T03:12:00Z")
                    .put("dateien", dateien.size).put("bytes", dateien.values.sumOf { it.size }).put("wartend", dateien.size),
            )
        }
        return json(200, JSONObject().put("quellen", q).put("frei_bytes", 1_000_000_000_000L))
    }

    private fun fehlend(o: JSONObject): MockResponse {
        fehlendAntworten++
        val quelle = o.getString("quelle")
        val dateien = o.getJSONArray("dateien")
        if (dateien.length() > 5000) return fehler(400, "Höchstens 5000 Dateien je Frage.")
        for (i in 0 until dateien.length()) {
            if (versteckt(dateien.getJSONObject(i).getString("pfad"))) return fehler(400, "Der Pfad geht nicht: nichts Verstecktes.")
        }
        val out = JSONArray()
        for (i in 0 until dateien.length()) {
            val pfad = dateien.getJSONObject(i).getString("pfad")
            if ("$quelle|$pfad" !in vorhanden && "$quelle|$pfad" !in fertig) out.put(pfad)
        }
        return json(200, JSONObject().put("fehlend", out))
    }

    private fun anlegen(o: JSONObject): MockResponse {
        val quelle = o.getString("quelle")
        val pfad = o.getString("pfad")
        val groesse = o.getLong("groesse")
        if (versteckt(pfad)) return fehler(400, "Der Pfad geht nicht: nichts Verstecktes.")
        val id = "%032x".format((quelle + "\u0000" + pfad + "\u0000" + groesse).hashCode().toLong() and 0xffffffffL)
        val alt = uploads[id]
        // a file put in place before is sent again from the start
        val u = if (alt == null || alt.fertig) Upload(id, quelle, pfad, groesse).also { uploads[id] = it } else alt
        if (u.offset == groesse) setzeHin(u)
        return json(201, JSONObject().put("id", u.id).put("offset", u.offset))
    }

    private fun head(id: String): MockResponse {
        val u = uploads[id] ?: return MockResponse().setResponseCode(404)
        return MockResponse().setResponseCode(204).setHeader("Upload-Offset", u.offset)
    }

    private fun patch(id: String, offsetHeader: String?, body: ByteArray): MockResponse {
        if (beschaeftigt > 0) {
            beschaeftigt--
            return fehler(429, "Die Box nimmt gerade schon andere Sicherungen an. Versuch es gleich noch einmal.")
        }
        val u = uploads[id] ?: return fehler(404, "Diesen Upload kennt Haus nicht.")
        if (u.fertig) {
            // the last answer got lost: it is in place
            if (offsetHeader?.toLongOrNull() != u.groesse || body.isNotEmpty()) {
                return json(409, JSONObject().put("fehler", "Schon vollständig.").put("offset", u.groesse))
            }
            return json(200, JSONObject().put("offset", u.groesse).put("fertig", true))
        }
        if (voll) return fehler(507, "Auf der Box ist kein Platz mehr frei.")
        verliere?.let { n ->
            if (u.fertig) return@let
            verliere = null
            val behalten = u.daten.toByteArray().copyOf((u.offset - n).coerceAtLeast(0).toInt())
            u.daten.reset()
            u.daten.write(behalten)
        }
        if (body.size > 16 * 1024 * 1024) return fehler(413, "Zu groß.")
        val offset = offsetHeader?.toLongOrNull()
        if (offset != u.offset) return json(409, JSONObject().put("fehler", "Versatz passt nicht.").put("offset", u.offset))
        val schnitt = schneideNach
        if (schnitt != null) {
            schneideNach = null
            u.daten.write(body, 0, minOf(schnitt, body.size))
            if (u.daten.size().toLong() == u.groesse) setzeHin(u)
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        u.daten.write(body)
        if (u.daten.size().toLong() == u.groesse) setzeHin(u)
        return json(200, JSONObject().put("offset", u.offset).put("fertig", u.fertig))
    }

    /** The whole file goes into the inbox under its name; the upload stays known. */
    private fun setzeHin(u: Upload) {
        u.fertig = true
        fertig["${u.quelle}|${u.pfad}"] = u.daten.toByteArray()
    }

    private fun versteckt(pfad: String) = pfad.split('/').any { it.isEmpty() || it.startsWith(".") }

    fun patches(): List<Anfrage> = synchronized(this) { anfragen.filter { it.method == "PATCH" } }

    fun schliessen() = server.shutdown()

    companion object {
        const val TOKEN = "haus_device_TEST"

        fun json(code: Int, o: JSONObject): MockResponse =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(o.toString())

        fun fehler(code: Int, satz: String): MockResponse = json(code, JSONObject().put("fehler", satz))

        /** [n] bytes that differ from piece to piece, so a piece in the wrong place shows. */
        fun bytes(n: Int, saat: Int = 7): ByteArray = ByteArray(n) { i -> ((i * 31 + saat + i / 1024) and 0xff).toByte() }
    }
}

/** A granted folder as a plain directory, read only, like [cloud.dopp.haus.sicherung.SafOrdner]. */
class DateiOrdner(private val wurzel: File) : Ordner {

    /** Taken back: every listing is null, as when the grant is withdrawn. */
    var entzogen = false

    /** List dot-names too, as a careless folder reader would: the run must leave them out itself. */
    var zeigtVersteckte = false

    override fun dateien(unter: String, tief: Boolean): List<Datei>? {
        if (entzogen) return null
        val start = if (unter.isEmpty()) wurzel else File(wurzel, unter)
        if (!start.isDirectory) return emptyList()
        val out = ArrayList<Datei>()
        fun gehe(dir: File, pfad: String) {
            dir.listFiles()!!.sortedBy { it.name }.forEach { f ->
                if (f.name.startsWith(".") && !zeigtVersteckte) return@forEach
                val p = if (pfad.isEmpty()) f.name else "$pfad/${f.name}"
                if (f.isDirectory) {
                    if (tief) gehe(f, p)
                } else {
                    out += Datei(p, f.length(), f.lastModified())
                }
            }
        }
        gehe(start, unter)
        return out
    }

    override fun oeffnen(pfad: String, ab: Long): InputStream {
        val s = FileInputStream(File(wurzel, pfad))
        s.channel.position(ab)
        return s
    }

    /** Write [inhalt] at [pfad], changed [alterMs] ago. */
    fun lege(pfad: String, inhalt: ByteArray, alterMs: Long = 60 * 60 * 1000L, jetzt: Long = System.currentTimeMillis()): File {
        val f = File(wurzel, pfad)
        f.parentFile!!.mkdirs()
        f.writeBytes(inhalt)
        // file systems keep whole seconds
        f.setLastModified((jetzt - alterMs) / 1000 * 1000)
        return f
    }
}
