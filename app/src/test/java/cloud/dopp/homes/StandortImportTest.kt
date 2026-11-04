package cloud.dopp.homes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.StandortImportClient
import cloud.dopp.homes.standort.Import
import cloud.dopp.homes.standort.Import.Ausgang
import cloud.dopp.homes.ui.StandortImportActivity
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Standortverlauf übernehmen (owner 2026-11-03): the share sheet offers
 * „In den Standortverlauf übernehmen“ for a JSON or GPX file, and the file
 * goes to a fake Homes that keeps mdopp/homes#234's contract — in pieces,
 * on after a cut, then asked until the import ended — and every end is a
 * sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StandortImportTest {

    /** Homes' side in memory: one upload per name and size, the import done after [laeuftNoch] questions. */
    class FakeImport : Dispatcher() {
        val server = MockWebServer().also { it.dispatcher = this }
        val base: String get() = server.url("/").toString().trimEnd('/')
        val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(5, TimeUnit.SECONDS).build()

        val daten = ByteArrayOutputStream()
        var groesse = -1L
        var name: String? = null
        val anfragen = ArrayList<String>()

        @Volatile var keinReitti = false

        /** An older Homes: a ZIP is refused at its start, with its sentence. */
        @Volatile var keinZip = false
        @Volatile var laeuftNoch = 2
        @Volatile var bleibtStehen = false

        /** The next PATCH is cut before Homes answers; Homes keeps nothing of it. */
        @Volatile var schneide = false

        override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
            val path = request.path.orEmpty()
            val body = request.body.readByteArray()
            anfragen += "${request.method} $path ${request.getHeader("Upload-Offset") ?: ""} ${request.getHeader("Content-Type") ?: ""}".trim()
            if (request.getHeader("Authorization") != "Bearer $TOKEN") return json(401, JSONObject().put("error", "unauthorized"))
            when {
                request.method == "POST" && path == "/napi/standort/import" -> {
                    if (keinReitti) {
                        return json(409, JSONObject().put("fehler", KEIN_REITTI).put("grund", Napi.GRUND_KEIN_SCHLUESSEL))
                    }
                    val o = JSONObject(String(body))
                    if (keinZip && o.getString("name").endsWith(".zip")) return json(400, JSONObject().put("fehler", ALT_ZIP))
                    if (o.getString("name") != name || o.getLong("groesse") != groesse) {
                        name = o.getString("name")
                        groesse = o.getLong("groesse")
                        daten.reset()
                    }
                    json(201, JSONObject().put("id", ID).put("offset", daten.size()))
                }
                request.method == "HEAD" && path == "/napi/standort/import/$ID" ->
                    MockResponse().setResponseCode(204).setHeader("Upload-Offset", daten.size().toString())
                request.method == "PATCH" && path == "/napi/standort/import/$ID" -> {
                    if (schneide) {
                        schneide = false
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    val at = request.getHeader("Upload-Offset")!!.toLong()
                    if (at != daten.size().toLong()) return json(409, JSONObject().put("fehler", "woanders").put("offset", daten.size()))
                    daten.write(body)
                    json(200, JSONObject().put("offset", daten.size()).put("fertig", daten.size().toLong() == groesse))
                }
                request.method == "GET" && path == "/napi/standort/import/$ID" -> {
                    val o = JSONObject().put("groesse", groesse).put("name", name)
                    if (bleibtStehen || laeuftNoch > 0) {
                        if (!bleibtStehen) laeuftNoch--
                        json(200, o.put("stand", Napi.IMPORT_LAEUFT).put("gelesen", groesse / 2).put("punkte", 6000))
                    } else {
                        json(200, o.put("stand", Napi.IMPORT_FERTIG).put("gelesen", groesse).put("punkte", 12345).put("text", FERTIG))
                    }
                }
                else -> json(404, JSONObject().put("error", "not found"))
            }
        }

        companion object {
            const val ID = "0123456789abcdef0123456789abcdef"
            const val ALT_ZIP = "Diese Datei kann Homes nicht lesen: JSON, GeoJSON oder GPX."
            fun json(code: Int, o: JSONObject): MockResponse =
                MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(o.toString())
        }
    }

    private lateinit var homes: FakeImport
    private var uhr = 0L

    @Before
    fun auf() {
        homes = FakeImport()
    }

    @After
    fun zu() = homes.server.shutdown()

    private fun import(teil: Int = 1000) = Import(
        StandortImportClient(homes.base, TOKEN, homes.http), teil = teil,
        schlafen = { uhr += it }, jetzt = { uhr },
    )

    private fun datei(n: Int) = ByteArray(n) { i -> ((i * 31 + 7) and 0xff).toByte() }

    @Test
    fun theFileGoesInPiecesAndHomesSentenceComesBack() {
        val inhalt = datei(4321)
        val gesehen = ArrayList<Import.Fortschritt>()
        val a = import().uebernehmen("Timeline.json", inhalt.size.toLong(), { ab -> ByteArrayInputStream(inhalt, ab.toInt(), inhalt.size) }, { gesehen += it })
        assertEquals(Ausgang.Fertig(FERTIG), a)
        assertArrayEquals(inhalt, homes.daten.toByteArray())
        assertEquals("Timeline.json", homes.name)
        val patches = homes.anfragen.filter { it.startsWith("PATCH") }
        assertEquals(listOf(0, 1000, 2000, 3000, 4000).map { "PATCH /napi/standort/import/${FakeImport.ID} $it ${Napi.OFFSET_STREAM}" }, patches)
        assertTrue(gesehen.contains(Import.Fortschritt.Hochladen(4321, 4321)))
        assertTrue(gesehen.contains(Import.Fortschritt.Uebernehmen(2160, 4321, 6000)))
    }

    /** A cut mid-file: the app asks Homes where it stands (HEAD) and goes on from there. */
    @Test
    fun aCutGoesOnWhereHomesStands() {
        val inhalt = datei(3500)
        homes.laeuftNoch = 0
        var patches = 0
        val a = import().uebernehmen("Records.json", inhalt.size.toLong(), { ab -> ByteArrayInputStream(inhalt, ab.toInt(), inhalt.size) }, {
            if (it is Import.Fortschritt.Hochladen && it.gesendet == 2000L && patches++ == 0) homes.schneide = true
        })
        assertEquals(Ausgang.Fertig(FERTIG), a)
        assertArrayEquals(inhalt, homes.daten.toByteArray())
        assertTrue(homes.anfragen.toString(), homes.anfragen.any { it.startsWith("HEAD /napi/standort/import/") })
    }

    /** Shared again after it stopped, the same name and size go on where Homes stands. */
    @Test
    fun sharedAgainItResumes() {
        val inhalt = datei(5000)
        var n = 0
        val halt = import().uebernehmen("spur.gpx", inhalt.size.toLong(), { ab -> ByteArrayInputStream(inhalt, ab.toInt(), inhalt.size) }, halt = { n++ >= 2 })
        assertEquals(Ausgang.Angehalten, halt)
        assertEquals(2000, homes.daten.size())
        homes.laeuftNoch = 0
        assertEquals(Ausgang.Fertig(FERTIG), import().uebernehmen("spur.gpx", inhalt.size.toLong(), { ab -> ByteArrayInputStream(inhalt, ab.toInt(), inhalt.size) }))
        assertArrayEquals(inhalt, homes.daten.toByteArray())
        assertEquals(3, homes.anfragen.count { it.startsWith("PATCH") && !it.contains(" 0 ") && !it.contains(" 1000 ") })
    }

    /** Without Reitti on Homes: Homes' sentence and its word, so the dialog offers „Reitti verbinden“. */
    @Test
    fun withoutReittiHomesSaysWhereToConnect() {
        homes.keinReitti = true
        val a = import().uebernehmen("Timeline.json", 10, { ByteArrayInputStream(ByteArray(10)) })
        assertEquals(Ausgang.Fehler(KEIN_REITTI, Napi.GRUND_KEIN_SCHLUESSEL), a)
        assertTrue(homes.anfragen.none { it.startsWith("PATCH") })
    }

    /** An import that stops moving is said after five minutes, not waited out. */
    @Test
    fun noProgressEndsInASentence() {
        homes.bleibtStehen = true
        val a = import().uebernehmen("Timeline.json", 10, { ByteArrayInputStream(ByteArray(10)) })
        assertEquals(Ausgang.Still, a)
        assertTrue(uhr in Import.STILL..Import.STILL + 2 * Import.FRAGE)
    }

    @Test
    fun notPairedAndAFileThatGotShorter() {
        val a = Import(StandortImportClient(homes.base, "falsch", homes.http)).uebernehmen("a.json", 10, { ByteArrayInputStream(ByteArray(10)) })
        assertEquals(Ausgang.NichtGekoppelt, a)
        val b = import().uebernehmen("a.json", 10, { ByteArrayInputStream(ByteArray(4)) })
        assertEquals(Ausgang.Fehler(Import.KUERZER, null), b)
    }

    @Test
    fun theNameDecides() {
        assertTrue(Import.nimmt("Timeline.json", "application/json"))
        assertTrue(Import.nimmt("Records.JSON", "application/octet-stream"))
        assertTrue(Import.nimmt("runde.gpx", "application/octet-stream"))
        assertTrue(Import.nimmt("punkte.geojson", null))
        assertTrue(Import.nimmt(null, "application/gpx+xml"))
        assertFalse(Import.nimmt("foto.jpg", "application/octet-stream"))
        assertFalse(Import.nimmt(null, "application/octet-stream"))
    }

    /** A Google Takeout comes as a ZIP (owner, 2026-11-04): taken by its type, or by its name from a file manager. */
    @Test
    fun aTakeoutZipIsTaken() {
        assertTrue(Import.nimmt("takeout-20261104.zip", "application/zip"))
        assertTrue(Import.nimmt("Takeout.ZIP", "application/octet-stream"))
        assertTrue(Import.nimmt(null, "application/zip"))
        assertTrue(Import.nimmt(null, "application/x-zip-compressed"))
        assertTrue(Import.nimmt(null, "application/x-zip"))
        assertFalse(Import.nimmt("musik.mp3", "application/octet-stream"))
        assertEquals("takeout.zip", Import.dateiname("takeout.zip", "application/zip"))
        assertEquals("a ZIP without a name keeps its kind", "Standortverlauf.zip", Import.dateiname(null, "application/zip"))
        assertEquals("Standortverlauf.json", Import.dateiname(" ", "application/json"))
    }

    /** The ZIP goes as it is, in pieces from the stream: the app neither unpacks nor holds it. */
    @Test
    fun aZipGoesAsItIs() {
        val inhalt = datei(2500).also { it[0] = 0x50; it[1] = 0x4b; it[2] = 3; it[3] = 4 }
        val a = import().uebernehmen("takeout.zip", inhalt.size.toLong(), { ab -> ByteArrayInputStream(inhalt, ab.toInt(), inhalt.size) })
        assertEquals(Ausgang.Fertig(FERTIG), a)
        assertEquals("takeout.zip", homes.name)
        assertArrayEquals(inhalt, homes.daten.toByteArray())
    }

    /** A Takeout part is large and the line drops: after a cut the ZIP goes on where Homes stands (homes#274). */
    @Test
    fun aZipGoesOnAfterTheLineDropped() {
        val inhalt = datei(3500)
        var cut = 0
        val a = import().uebernehmen("takeout-001.zip", inhalt.size.toLong(), { ab -> ByteArrayInputStream(inhalt, ab.toInt(), inhalt.size) }, {
            if (it is Import.Fortschritt.Hochladen && it.gesendet == 1000L && cut++ == 0) homes.schneide = true
        })
        assertEquals(Ausgang.Fertig(FERTIG), a)
        assertArrayEquals(inhalt, homes.daten.toByteArray())
        assertTrue(homes.anfragen.toString(), homes.anfragen.any { it.startsWith("HEAD /napi/standort/import/") })
    }

    /** An older Homes refuses the ZIP: its sentence is what the person reads, as for any refusal. */
    @Test
    fun anOlderHomesSaysItCannotReadAZip() {
        homes.keinZip = true
        val a = import().uebernehmen("takeout.zip", 10, { ByteArrayInputStream(ByteArray(10)) })
        assertTrue("$a", a is Ausgang.Fehler && a.satz == FakeImport.ALT_ZIP)
        assertEquals(0, homes.daten.size())
    }

    // --- the share target ------------------------------------------------------

    private val manifest by lazy { File(RepoFiles.main, "AndroidManifest.xml").readText() }

    @Test
    fun theShareSheetOffersItForHistoryFiles() {
        val activity = manifest.substringAfter("""android:name=".ui.StandortImportActivity"""").substringBefore("</activity>")
        assertTrue(activity, activity.contains("""android:exported="true""""))
        assertTrue(activity, activity.contains("""android:label="@string/standort_import_label""""))
        assertTrue(activity, activity.contains("""android:taskAffinity="""""))
        assertTrue(activity, activity.contains("android.intent.action.SEND\""))
        assertFalse("one file at a time", activity.contains("SEND_MULTIPLE"))
        Import.TYPEN.forEach { assertTrue("$it", activity.contains("""android:mimeType="$it"""")) }
        assertFalse("no image, no text", activity.contains("image/") || activity.contains("text/plain"))
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals("In den Standortverlauf übernehmen", ctx.getString(R.string.standort_import_label))
    }

    /** Android itself sends a JSON or GPX share here, and a photo or a link not. */
    @Test
    fun androidResolvesTheShare() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val ziel = ComponentName(ctx, StandortImportActivity::class.java)
        fun ziele(typ: String) = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType(typ).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://x/y")), PackageManager.MATCH_DEFAULT_ONLY,
        ).map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        listOf("application/json", "application/gpx+xml", "text/xml", "application/octet-stream").forEach {
            assertTrue("$it: ${ziele(it)}", ziel in ziele(it))
        }
        listOf("image/jpeg", "text/plain").forEach { assertFalse("$it", ziel in ziele(it)) }
    }

    @Test
    fun theDialogIsTheAppsOneDialog() {
        val src = File(RepoFiles.root, "app/src/main/java/cloud/dopp/homes/ui/StandortImportActivity.kt").readText()
        assertTrue(src.contains("ActionDialog.scrim(this)") && src.contains("ActionDialog.show("))
        assertFalse(src.contains("AlertDialog.Builder"))
        assertEquals("12.345", StandortImportActivity.zahl(12345))
        assertEquals("1,5", StandortImportActivity.mb(1_572_864))
        assertEquals(50, StandortImportActivity.prozent(5, 10))
    }

    companion object {
        const val TOKEN = "homes_device_TEST"
        const val FERTIG = "12.345 Orte von März 2014 bis Dezember 2024 übernommen. Reitti ordnet sie in den nächsten Minuten in deine Zeitleiste ein."
        const val KEIN_REITTI = "Verbinde zuerst Reitti: öffne in Homes Mehr → Standortverlauf und füge dort einmal deinen Reitti-Schlüssel ein. Dann teile die Datei noch einmal."
    }
}
