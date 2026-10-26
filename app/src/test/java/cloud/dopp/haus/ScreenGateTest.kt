package cloud.dopp.haus

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.BoxApiClient
import cloud.dopp.haus.data.ScreenGate
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * With the screen off nothing goes over the network: not only the stream, every
 * request to Haus. A widget update Android delivers after the screen went off
 * posted the watch set on API 31 (live e2e, step 2); [ScreenGate] refuses it
 * before a socket is opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenGateTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private fun screen(on: Boolean) =
        shadowOf(ctx().getSystemService(PowerManager::class.java)).setIsInteractive(on)

    @Before
    fun paired() {
        // a port nothing listens on: a request that does go out fails to connect
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        // Robolectric has no keystore: the token goes to plain prefs here.
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "haus_device_TESTTOKEN123")
    }

    @After
    fun clean() {
        screen(true)
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
    }

    @Test
    fun screenOffNoRequestGoesOut() {
        screen(false)
        assertFalse(ScreenGate.open(ctx()))
        val refused = runCatching { ApiClient(ctx()).getCard("light.flur") }.exceptionOrNull()
        assertTrue("refused at the gate, not at the socket: $refused", refused is ScreenGate.ScreenOffException)
        assertEquals(0, ApiClient(ctx()).postWatch(ApiClient.watchBody(listOf("light.flur"), false)))
        assertNull(ApiClient(ctx()).listActive())
        assertNull(BoxApiClient(ctx()).getPersona())
    }

    @Test
    fun screenOnTheRequestGoesOut() {
        screen(true)
        assertTrue(ScreenGate.open(ctx()))
        val failed = runCatching { ApiClient(ctx()).getCard("light.flur") }.exceptionOrNull()
        assertFalse("the gate refused with the screen on", failed is ScreenGate.ScreenOffException)
    }

    /** Every request of the two clients passes the gate; whoami is the pairing's, made with the person at the screen. */
    @Test
    fun everyRequestPassesTheGate() {
        val api = File(RepoFiles.main, "java/cloud/dopp/haus/data/ApiClient.kt").readLines()
        val bypass = api.filter { it.contains("newCall(") && !it.contains("authed(") && !it.contains("Napi.WHOAMI") }
        assertTrue("ApiClient calls outside authed(): $bypass", bypass.isEmpty())
        val authed = api.dropWhile { !it.contains("private fun authed(") }.take(3).joinToString("\n")
        assertTrue(authed, authed.contains("ScreenGate.check(ctx)"))

        val box = File(RepoFiles.main, "java/cloud/dopp/haus/data/BoxApiClient.kt").readText()
        assertEquals("BoxApiClient has one request", 1, Regex("""newCall\(""").findAll(box).count())
        assertTrue(box.substringAfter("private fun bytes(").substringBefore("newCall(").contains("ScreenGate.open(ctx)"))
    }
}
