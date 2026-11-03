package cloud.dopp.homes

import android.Manifest
import android.app.Application
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.HomesKonto.Teil
import cloud.dopp.homes.konto.KontoStore
import cloud.dopp.homes.ui.HomesKontoActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * „Jetzt abgleichen“ crashed the app (owner, 2026-11-03, Haus v3.0.2-dev.269):
 * the line under the button asks Android whether the sync runs
 * (isSyncActive, isSyncPending), and Android answers that only to an app
 * holding READ_SYNC_STATS — without it a SecurityException, uncaught on the
 * main thread. Robolectric's own shadow checks nothing; [PruefendeSyncs] does
 * what ContentService does, granting what the manifest asks for, as Android
 * does with a normal permission at install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [PruefendeSyncs::class])
class HomesKontoJetztTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // as after a fresh install: no account, no run remembered
        HomesKonto.entfernen(app)
        Teil.values().forEach { KontoStore(app).vergiss(it) }
        TokenStore.backend = app.getSharedPreferences("test_tokens", android.content.Context.MODE_PRIVATE)
        ServerStore.setBaseUrl(app, "https://homes.example.org")
        TokenStore.save(app, "homes_device_T")
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
        )
    }

    @After
    fun tearDown() {
        HomesKonto.entfernen(app)
        TokenStore.clear(app)
        ServerStore.clear(app)
    }

    @Test
    fun jetztAbgleichenWithBothPartsOnFollowsTheRunInsteadOfCrashing() {
        HomesKonto.einschalten(app, Teil.KONTAKTE)
        HomesKonto.einschalten(app, Teil.KALENDER)
        // the screen opened later, nothing of its own under way, as on the owner's phone
        val a = Robolectric.buildActivity(HomesKontoActivity::class.java).setup().get()
        val knopf = a.findViewById<Button>(R.id.konto_jetzt)
        assertTrue(knopf.isEnabled)

        knopf.performClick()

        assertEquals(app.getString(R.string.konto_gleicht_ab), a.findViewById<TextView>(R.id.konto_zustand).text.toString())
        assertFalse("one run at a time", knopf.isEnabled)
    }

    @Test
    fun theSwitchFollowsItsFirstRunInsteadOfCrashing() {
        val a = Robolectric.buildActivity(HomesKontoActivity::class.java).setup().get()
        a.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.konto_kalender_an).performClick()
        assertEquals(app.getString(R.string.konto_gleicht_ab), a.findViewById<TextView>(R.id.konto_zustand).text.toString())
    }
}
