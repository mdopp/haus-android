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

    /** Android 15's six data-sync hours used up: the family reads that it goes on later, not a failure. */
    @Test fun theDailyHoursUsedUpAreSaidInGerman() {
        val text = Hochladen.spaeter(ctx(), WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)
        assertTrue(text, text.startsWith("Android lässt die App gerade nicht länger hochladen. Es geht später von selbst weiter"))
        assertEquals(text, Hochladen.spaeter(ctx(), Hochladen.AUSGEBREMST))
        assertTrue(Hochladen.spaeter(ctx(), WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY).startsWith("Das Hochladen geht später"))
    }
}
