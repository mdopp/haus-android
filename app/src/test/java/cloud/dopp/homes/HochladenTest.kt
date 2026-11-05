package cloud.dopp.homes

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.testing.TestWorkerBuilder
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.hochladen.Auftraege
import cloud.dopp.homes.hochladen.Auftrag
import cloud.dopp.homes.hochladen.Hochladen
import cloud.dopp.homes.hochladen.HochladenWorker
import cloud.dopp.homes.hochladen.MusikClient
import cloud.dopp.homes.hochladen.MusikDatei
import cloud.dopp.homes.hochladen.Stand
import cloud.dopp.homes.ui.HomeActivity
import android.view.View
import android.widget.TextView
import org.robolectric.Robolectric
import cloud.dopp.homes.hochladen.Ziel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

/**
 * Hochladen, the app's second background job (owner, 2026-11-04): what
 * „Zur Musik hinzufügen“ takes, the queue that survives a killed process,
 * the music's one streamed request, and how a run ends — Homes has it, an
 * older Homes says „kommt mit dem nächsten Update“, or the line is gone and
 * the notification says it goes on later. Never a silent stop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HochladenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val server = MockWebServer()

    @Before fun auf() = server.start()

    @After fun zu() {
        runCatching { server.shutdown() }
        Auftraege.alle(ctx()).forEach { Auftraege.weg(ctx(), it.id) }
        ctx().getSharedPreferences("homes_hochladen_stand", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // --- what the music takes ----------------------------------------------------

    @Test fun theMusicTakesSongsAndZipsByNameOrType() {
        for (n in listOf("Lied.mp3", "Album.FLAC", "a.m4a", "b.ogg", "c.opus", "d.wav", "Album.zip")) {
            assertTrue(n, MusikDatei.nimmt(n, "application/octet-stream"))
        }
        assertTrue(MusikDatei.nimmt(null, "audio/mpeg"))
        assertTrue(MusikDatei.nimmt(null, "audio/flac; charset=binary"))
        assertTrue(MusikDatei.nimmt(null, "application/zip"))
        assertTrue(MusikDatei.nimmt(null, "application/x-zip-compressed"))
        assertTrue(MusikDatei.nimmt(null, "application/x-zip"))
        assertFalse("the name decides", MusikDatei.nimmt("Hörbuch.m4b", "audio/mp4"))
        assertFalse(MusikDatei.nimmt("foto.jpg", "application/octet-stream"))
        assertFalse(MusikDatei.nimmt(null, "application/octet-stream"))
        assertFalse(MusikDatei.nimmt(null, null))
    }

    @Test fun aFileWithoutANameIsNamedByItsType() {
        assertEquals("Lied.mp3", MusikDatei.name(" Lied.mp3 ", "audio/mpeg", 1))
        assertEquals("Musik-2.zip", MusikDatei.name(null, "application/zip", 2))
        assertEquals("Musik-1.flac", MusikDatei.name("", "audio/flac", 1))
        assertEquals("Musik-3.mp3", MusikDatei.name(null, "audio/mpeg", 3))
    }

    // --- a ZIP that is no music (owner, 2026-11-04) ---------------------------------

    private fun zip(name: String, vararg eintraege: String): File = File(ctx().cacheDir, name).apply {
        java.util.zip.ZipOutputStream(outputStream()).use { z ->
            eintraege.forEach { e -> z.putNextEntry(java.util.zip.ZipEntry(e)); z.write(ByteArray(10)); z.closeEntry() }
        }
    }

    @Test fun aZipIsJudgedByTheNamesInItsDirectory() {
        assertEquals(MusikDatei.Zip.MUSIK, MusikDatei.zip(zip("a.zip", "Album/cover.jpg", "Album/01 Lied.FLAC")))
        assertEquals(MusikDatei.Zip.TAKEOUT, MusikDatei.zip(zip("t.zip", "Takeout/Standortverlauf/Records.json", "Takeout/archive_browser.html")))
        assertEquals(MusikDatei.Zip.FREMD, MusikDatei.zip(zip("f.zip", "Urlaub/IMG_0001.jpg", "notizen.txt")))
        assertEquals("not a ZIP at all", MusikDatei.Zip.FREMD, MusikDatei.zip(lied(100)))
    }

    private fun teilen(datei: File): android.app.AlertDialog {
        koppeln("https://homes.example")
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip")
            .putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.fromFile(datei))
        Robolectric.buildActivity(cloud.dopp.homes.hochladen.MusikTeilenActivity::class.java, i).create()
        repeat(40) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog?
            if (d != null && d.findViewById<TextView>(R.id.ad_message)?.text?.contains("?") == true) return d
            Thread.sleep(50)
        }
        throw AssertionError("no question")
    }

    private fun ja(d: android.app.AlertDialog) = d.findViewById<android.view.ViewGroup>(R.id.ad_items)!!.getChildAt(0).performClick()

    @Test fun aZipWithoutMusicIsAskedAboutFirst() {
        val d = teilen(zip("urlaub.zip", "Urlaub/IMG_0001.jpg"))
        assertEquals("Das sieht nicht nach Musik aus – trotzdem hochladen?", d.findViewById<TextView>(R.id.ad_message)!!.text.toString())
        assertTrue("nothing queued before the answer", Auftraege.alle(ctx()).isEmpty())
        ja(d)
        assertEquals(listOf(Ziel.MUSIK to "urlaub.zip"), Auftraege.alle(ctx()).map { it.ziel to it.name })
    }

    @Test fun aTakeoutSharedToTheMusicIsOfferedToTheStandortverlauf() {
        val d = teilen(zip("takeout-20261104.zip", "Takeout/Standortverlauf/Records.json"))
        assertEquals("Das sieht nach einer Google-Sicherung aus, nicht nach Musik. Zum Standortverlauf?", d.findViewById<TextView>(R.id.ad_message)!!.text.toString())
        ja(d)
        assertEquals(listOf(Ziel.STANDORT to "takeout-20261104.zip"), Auftraege.alle(ctx()).map { it.ziel to it.name })
    }

    @Test fun cancelledTheCopyGoesAndNothingIsQueued() {
        val d = teilen(zip("urlaub.zip", "Urlaub/IMG_0001.jpg"))
        d.findViewById<android.view.View>(R.id.ad_cancel)!!.performClick()
        assertTrue(Auftraege.alle(ctx()).isEmpty())
        assertTrue("no copy left", File(ctx().filesDir, "hochladen").listFiles().orEmpty().isEmpty())
    }

    // --- the queue ---------------------------------------------------------------

    @Test fun theQueueKeepsItsOrderAndReadsNoJunk() {
        val a = Auftrag("1", Ziel.MUSIK, "/x/1", "Lied.mp3", 3)
        val b = Auftrag("2", Ziel.STANDORT, "/x/2", "takeout.zip", 5)
        assertEquals(listOf(a, b), Auftraege.lesen(Auftraege.schreiben(listOf(a, b))))
        for (junk in listOf(null, "", "{}", """[{"id":"1","ziel":"WAS","datei":"/x","name":"n"}]""")) {
            assertTrue("junk $junk", Auftraege.lesen(junk).isEmpty())
        }
    }

    // --- the music's request ----------------------------------------------------

    private fun lied(n: Int = 300_000): File = File(ctx().filesDir, "lied-$n").apply { writeBytes(ByteArray(n) { (it % 251).toByte() }) }

    private fun client() = MusikClient(server.url("/").toString().trimEnd('/'), "homes_device_T")

    @Test fun aSongGoesAsOneStreamedFilePart() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"dateien":["Lied.mp3"]}"""))
        val datei = lied()
        val gesendet = ArrayList<Long>()
        val r = client().senden(datei, "Lied.mp3", { gesendet += it })
        assertEquals(MusikClient.Ausgang.Fertig(listOf("Lied.mp3")), r)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/napi/musik", req.path)
        assertEquals("Bearer homes_device_T", req.getHeader("Authorization"))
        val body = req.body.readByteArray()
        assertTrue(String(body, 0, 400, Charsets.ISO_8859_1).contains("""name="file"; filename="Lied.mp3""""))
        assertTrue("the whole file went", body.size > datei.length())
        assertEquals("counted to the end, in pieces", datei.length(), gesendet.last())
        assertTrue(gesendet.size > 1)
    }

    @Test fun homesWordsComeBackAndAnOlderHomesIsMissing() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"ok":false,"error":"Das ist keine Musik.","dateien":[]}"""))
        assertEquals(MusikClient.Ausgang.Abgelehnt(400, "Das ist keine Musik."), client().senden(lied(10), "x.mp3"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}"""))
        assertEquals(MusikClient.Ausgang.Fehlt, client().senden(lied(10), "x.mp3"))
    }

    @Test fun aStoppedJobEndsTheRequest() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"dateien":[]}"""))
        assertEquals(MusikClient.Ausgang.Offline, client().senden(lied(), "x.mp3", halt = { true }))
    }

    // --- the job -----------------------------------------------------------------

    private fun koppeln(base: String) {
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().putString("base_url", base).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
    }

    private fun anstellen(name: String): File {
        val f = lied(2000)
        Auftraege.hinzu(ctx(), Auftrag(f.name, Ziel.MUSIK, f.path, name, f.length()))
        return f
    }

    private fun lauf(): ListenableWorker.Result =
        TestWorkerBuilder<HochladenWorker>(ctx(), Executors.newSingleThreadExecutor()).build().doWork()

    private fun gesagt(): List<String> {
        val nm = ctx().getSystemService(NotificationManager::class.java)
        return shadowOf(nm).allNotifications.map {
            it.extras.getString(Notification.EXTRA_TITLE) + ": " + it.extras.getCharSequence(Notification.EXTRA_TEXT)
        }
    }

    @Test fun aSongReachesHomesAndTheNotificationSaysSo() {
        koppeln(server.url("/").toString().trimEnd('/'))
        server.enqueue(MockResponse().setBody("""{"ok":true,"dateien":["Lied.mp3"]}"""))
        val f = anstellen("Lied.mp3")
        assertEquals(ListenableWorker.Result.success(), lauf())
        assertTrue(Auftraege.alle(ctx()).isEmpty())
        assertFalse("the copy is gone", f.exists())
        assertTrue(gesagt().toString(), gesagt().any { it.startsWith("Bei der Musik: „Lied.mp3“ ist auf der Box") })
    }

    @Test fun anOlderHomesSaysItComesWithTheNextUpdate() {
        koppeln(server.url("/").toString().trimEnd('/'))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}"""))
        anstellen("Lied.mp3")
        assertEquals(ListenableWorker.Result.success(), lauf())
        assertTrue(gesagt().toString(), gesagt().any { it == "Nicht hinzugefügt: Die Box kann das noch nicht – kommt mit dem nächsten Update." })
    }

    @Test fun noLineTheUploadGoesOnLaterAndSaysSo() {
        koppeln(server.url("/").toString().trimEnd('/'))
        server.shutdown()
        anstellen("Lied.mp3")
        assertEquals(ListenableWorker.Result.retry(), lauf())
        assertEquals("the file waits", 1, Auftraege.alle(ctx()).size)
        assertTrue(gesagt().toString(), gesagt().any { it.startsWith("Hochladen pausiert: Das Hochladen geht später von selbst weiter") })
    }

    // --- the same in the open app (owner, 2026-11-04) -----------------------------

    @Test fun theRowSaysWhatTheNotificationSays() {
        val r = ctx().resources
        assertEquals("Lädt hoch: Lied.mp3 · 40 %", Stand.text(r, "Lied.mp3", 40, null, 0, null))
        assertEquals("Lädt hoch: Lied.mp3", Stand.text(r, "Lied.mp3", -1, null, 2, null))
        assertEquals("Hochladen pausiert – geht später weiter", Stand.text(r, null, -1, "Das Hochladen geht später …", 2, null))
        assertEquals("3 Dateien in der Musik", Stand.text(r, null, -1, null, 3, null))
        assertEquals(
            "1 Datei in der Musik · Standortverlauf: 12.345 Orte übernommen.",
            Stand.text(r, null, -1, null, 1, "Standortverlauf: 12.345 Orte übernommen."),
        )
        assertEquals(null, Stand.text(r, null, -1, null, 0, null))
    }

    @Test fun aResultSeenInTheAppGoesWithItsNotification() {
        repeat(3) { Stand.fertig(ctx(), musik = true, zeile = null) }
        assertEquals("3 Dateien in der Musik", Stand.text(ctx()))
        Hochladen.sagen(ctx(), "Bei der Musik", "„Lied.mp3“ ist auf der Box.", "a1")
        val nm = ctx().getSystemService(NotificationManager::class.java)
        assertEquals(1, nm.activeNotifications.size)
        Stand.gesehen(ctx())
        assertEquals("the notification went", 0, nm.activeNotifications.size)
        assertEquals("the row stays while the screen is open", "3 Dateien in der Musik", Stand.text(ctx()))
        Stand.vergessen(ctx())
        assertEquals("gone at the next visit", null, Stand.text(ctx()))
        Stand.fertig(ctx(), musik = false, zeile = "Musik: Das ist keine Musik.")
        assertEquals("Musik: Das ist keine Musik.", Stand.text(ctx()))
    }

    @Test fun runningAndPausedStayUntilDone() {
        Stand.lauf(ctx(), "Album.zip", 12)
        Stand.gesehen(ctx())
        Stand.vergessen(ctx())
        assertEquals("Lädt hoch: Album.zip · 12 %", Stand.text(ctx()))
        Stand.pause(ctx(), "später")
        Stand.gesehen(ctx())
        Stand.vergessen(ctx())
        assertEquals("Hochladen pausiert – geht später weiter", Stand.text(ctx()))
        Stand.leer(ctx())
    }

    @Test fun theStartScreenShowsTheRowAndTheNextVisitNotTheSeenResult() {
        Stand.fertig(ctx(), musik = false, zeile = "Standortverlauf: 12.345 Orte übernommen.")
        Hochladen.sagen(ctx(), "Übernommen", "12.345 Orte übernommen.", "s1")
        val c = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val zeile = c.get().findViewById<TextView>(R.id.hochladen_zeile)
        assertEquals(View.VISIBLE, zeile.visibility)
        assertEquals("Standortverlauf: 12.345 Orte übernommen.", zeile.text.toString())
        assertEquals("seen in the app: its notification went", 0, ctx().getSystemService(NotificationManager::class.java).activeNotifications.size)
        c.pause().resume()
        assertEquals(View.GONE, zeile.visibility)
    }

    @Test fun theJobWritesTheRowsState() {
        koppeln(server.url("/").toString().trimEnd('/'))
        server.enqueue(MockResponse().setBody("""{"ok":true,"dateien":["Lied.mp3"]}"""))
        anstellen("Lied.mp3")
        lauf()
        assertEquals("1 Datei in der Musik", Stand.text(ctx()))
        server.shutdown()
        anstellen("Zwei.mp3")
        lauf()
        assertEquals("Hochladen pausiert – geht später weiter", Stand.text(ctx()))
    }

    /** The uploads' channel keeps its badge: the open app shows the same (owner, 2026-11-04). */
    @Test fun theUploadsChannelKeepsItsBadge() {
        Hochladen.sagen(ctx(), "Bei der Musik", "„Lied.mp3“ ist auf der Box.", "b1")
        val nm = ctx().getSystemService(NotificationManager::class.java)
        assertTrue(nm.getNotificationChannel("hochladen").canShowBadge())
    }

    /** Android 15's six data-sync hours used up: the family reads that it goes on later, not a failure. */
    @Test fun theDailyHoursUsedUpAreSaidInGerman() {
        val text = Hochladen.spaeter(ctx(), WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)
        assertTrue(text, text.startsWith("Android lässt die App gerade nicht länger hochladen. Es geht später von selbst weiter"))
        assertEquals(text, Hochladen.spaeter(ctx(), Hochladen.AUSGEBREMST))
        assertTrue(Hochladen.spaeter(ctx(), WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY).startsWith("Das Hochladen geht später"))
    }
}
