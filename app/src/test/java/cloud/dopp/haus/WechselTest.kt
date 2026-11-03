package cloud.dopp.haus

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInfo
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import cloud.dopp.haus.wechsel.Wechsel
import cloud.dopp.haus.wechsel.Wechsel.Antwort
import cloud.dopp.haus.wechsel.Wechsel.Hinweis
import cloud.dopp.haus.wechsel.WechselNachricht
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Haus heißt jetzt Homes (laib#1021): the server names its app at
 * `GET /napi/app`; when that is another package, the start screen says to
 * install it, and once it is installed, that Haus can go. A server from
 * before (404) and a failed check show nothing. The one notice comes at most
 * once a day, only while it applies, only when notifications are allowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WechselTest {

    private val homes = """{"name":"Homes","paket":"cloud.dopp.homes","quelle":"https://github.com/mdopp/homes-android"}"""
    private val eigen = "cloud.dopp.haus"
    private val nichts: (String) -> Boolean = { false }

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private fun nm() = ctx().getSystemService(NotificationManager::class.java)!!

    @Before
    fun clean() {
        Wechsel.vergiss(ctx())
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
        nm().cancelAll()
    }

    // --- the decision ------------------------------------------------------------

    @Test
    fun aServerFromBeforeAnswers404AndNothingIsShown() {
        val a = Wechsel.antwort(404, "404 page not found")
        assertEquals(Antwort.Keine, a)
        assertEquals(Hinweis.KEINER, Wechsel.hinweis(a, eigen, nichts))
    }

    @Test
    fun anotherPackageSaysInstallIt() {
        val a = Wechsel.antwort(200, homes)
        assertEquals(Antwort.Ziel("Homes", "cloud.dopp.homes", "https://github.com/mdopp/homes-android"), a)
        assertEquals("github.com/mdopp/homes-android", (a as Antwort.Ziel).adresse)
        assertEquals(Hinweis.INSTALLIEREN, Wechsel.hinweis(a, eigen, nichts))
    }

    @Test
    fun theSamePackageSaysNothing() {
        val a = Wechsel.antwort(200, """{"name":"Haus","paket":"cloud.dopp.haus","quelle":"https://github.com/mdopp/haus-android"}""")
        assertEquals(Hinweis.KEINER, Wechsel.hinweis(a, eigen) { true })
    }

    @Test
    fun homesInstalledSaysHausCanGo() {
        val a = Wechsel.antwort(200, homes)
        assertEquals(Hinweis.LOESCHEN, Wechsel.hinweis(a, eigen) { it == "cloud.dopp.homes" })
    }

    @Test
    fun aFailedCheckIsNoAnswerAndAnOddOneNamesNoApp() {
        assertNull(Wechsel.antwort(500, null))
        assertNull(Wechsel.antwort(401, """{"error":"unauthorized"}"""))
        assertNull(Wechsel.antwort(200, "kaputt"))
        assertEquals(Hinweis.KEINER, Wechsel.hinweis(null, eigen, nichts))
        assertEquals("no package", Antwort.Keine, Wechsel.antwort(200, """{"name":"Homes"}"""))
        assertEquals("no https source", Antwort.Keine, Wechsel.antwort(200, """{"paket":"cloud.dopp.homes","quelle":"http://x"}"""))
        assertEquals("the name until the server says one", "Homes", (Wechsel.antwort(200, """{"paket":"x.y","quelle":"https://a/b/"}""") as Antwort.Ziel).name)
    }

    @Test
    fun theDayIsTheLocalOne() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val berlin = java.util.TimeZone.getTimeZone("Europe/Berlin")
        val spaet = 1_793_000_000_000L - (1_793_000_000_000L % 86_400_000L) + 23L * 3_600_000L // 23:00 UTC
        assertEquals(Wechsel.tag(spaet, utc) + 1, Wechsel.tag(spaet, berlin))
    }

    // --- kept, on this phone -----------------------------------------------------

    private fun homesInstallieren() {
        shadowOf(ctx().packageManager).installPackage(PackageInfo().apply { packageName = Wechsel.HOMES })
    }

    @Test
    fun theKeptAnswerAgainstThisPhonesApps() {
        assertEquals(Hinweis.KEINER, Wechsel.jetzt(ctx()).first)
        Wechsel.merke(ctx(), Wechsel.antwort(200, homes)!!)
        assertEquals(Hinweis.INSTALLIEREN, Wechsel.jetzt(ctx()).first)
        homesInstallieren()
        assertEquals(Hinweis.LOESCHEN, Wechsel.jetzt(ctx()).first)
        Wechsel.merke(ctx(), Antwort.Keine)
        assertEquals(Hinweis.KEINER, Wechsel.jetzt(ctx()).first)
    }

    // --- the one notice ----------------------------------------------------------

    private val morgens = 1_793_000_000_000L - (1_793_000_000_000L % 86_400_000L) + 8L * 3_600_000L

    @Test
    fun theNoticeComesOnceADayWhileItApplies() {
        Wechsel.merke(ctx(), Wechsel.antwort(200, homes)!!)
        WechselNachricht.melden(ctx(), morgens)
        assertEquals(1, shadowOf(nm()).allNotifications.size)
        val n = shadowOf(nm()).allNotifications.single()
        assertEquals("Haus heißt jetzt Homes", shadowOf(n).contentTitle.toString())
        nm().cancelAll()
        WechselNachricht.melden(ctx(), morgens + 3_600_000L)
        assertEquals("not twice a day", 0, shadowOf(nm()).allNotifications.size)
        WechselNachricht.melden(ctx(), morgens + 86_400_000L)
        assertEquals("again the next day", 1, shadowOf(nm()).allNotifications.size)
        // the server names no app any more: the notice is taken back
        Wechsel.merke(ctx(), Antwort.Keine)
        WechselNachricht.melden(ctx(), morgens + 2 * 86_400_000L)
        assertEquals(0, shadowOf(nm()).allNotifications.size)
    }

    @Test
    fun noNoticeWhenNotificationsAreOffOrNothingApplies() {
        WechselNachricht.melden(ctx(), morgens)
        assertEquals("nothing applies", 0, shadowOf(nm()).allNotifications.size)
        Wechsel.merke(ctx(), Wechsel.antwort(200, homes)!!)
        shadowOf(nm()).setNotificationsEnabled(false)
        WechselNachricht.melden(ctx(), morgens)
        assertEquals("notifications off", 0, shadowOf(nm()).allNotifications.size)
        shadowOf(nm()).setNotificationsEnabled(true)
        homesInstallieren()
        WechselNachricht.melden(ctx(), morgens)
        assertEquals("Homes ist installiert", shadowOf(shadowOf(nm()).allNotifications.single()).contentTitle.toString())
    }

    // --- the start screen --------------------------------------------------------

    private fun paired() {
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "haus_device_TESTTOKEN123")
    }

    private fun open(): HomeActivity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()

    @Test
    fun theStartScreenShowsNothingWhileTheServerNamesNoApp() {
        paired()
        assertEquals(View.GONE, open().findViewById<View>(R.id.wechsel_card).visibility)
    }

    @Test
    fun theStartScreenSaysInstallHomesThenRemoveHaus() {
        paired()
        Wechsel.merke(ctx(), Wechsel.antwort(200, homes)!!)
        var a = open()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.wechsel_card).visibility)
        val text = a.findViewById<TextView>(R.id.wechsel_text).text.toString()
        assertEquals(
            "Haus heißt jetzt Homes. Installiere die App Homes (in Obtainium: App hinzufügen, Adresse " +
                "github.com/mdopp/homes-android), melde dich dort an und richte deine Widgets neu ein. " +
                "Danach kannst du Haus löschen.",
            text,
        )
        assertEquals("Seite von Homes öffnen", a.findViewById<Button>(R.id.wechsel_btn).text.toString())

        homesInstallieren()
        a = open()
        assertEquals("Homes ist installiert. Du kannst Haus jetzt löschen.", a.findViewById<TextView>(R.id.wechsel_text).text.toString())
        val btn = a.findViewById<Button>(R.id.wechsel_btn)
        assertEquals("Haus löschen", btn.text.toString())
        btn.performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_DELETE, started.action)
        assertEquals("package:" + ctx().packageName, started.dataString)
    }

    @Test
    fun unpairingForgetsIt() {
        paired()
        Wechsel.merke(ctx(), Wechsel.antwort(200, homes)!!)
        val a = open()
        a.findViewById<View>(R.id.logout_btn).performClick()
        assertTrue(Wechsel.ziel(ctx()) == null)
    }
}
