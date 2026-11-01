package cloud.dopp.haus

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.sicherung.SicherungWorker
import cloud.dopp.haus.ui.HomeActivity
import cloud.dopp.haus.ui.SicherungActivity
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
import java.util.concurrent.TimeUnit

/**
 * Mehr → Nachrichten sichern (#194): the home screen leads there; the screen
 * shows both sources, the guide and „Jetzt sichern“, asks for no key; the
 * daily run waits for WLAN, the charger and enough battery, „Jetzt sichern“
 * only for WLAN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SicherungScreenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun auf() {
        if (runCatching { WorkManager.getInstance(ctx()) }.isFailure) {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                ctx(),
                Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
            )
        }
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "haus_device_TESTTOKEN123")
    }

    @After
    fun zu() {
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
    }

    @Test
    fun homeLeadsToIt() {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val mehr = a.findViewById<ViewGroup>(R.id.mehr_group)
        assertEquals(1, mehr.childCount)
        val row = mehr.getChildAt(0)
        assertEquals("Nachrichten sichern", row.findViewById<TextView>(R.id.type_label).text.toString())
        row.performClick()
        val next = shadowOf(a).nextStartedActivity
        assertEquals(SicherungActivity::class.java.name, next.component?.className)
    }

    @Test
    fun theScreenShowsBothSourcesTheGuideAndNoKeyField() {
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        val whatsapp = a.findViewById<View>(R.id.sicherung_whatsapp)
        val signal = a.findViewById<View>(R.id.sicherung_signal)
        assertEquals("WhatsApp", whatsapp.findViewById<TextView>(R.id.quelle_name).text.toString())
        assertEquals("Signal", signal.findViewById<TextView>(R.id.quelle_name).text.toString())
        listOf(whatsapp, signal).forEach {
            assertEquals("Noch kein Ordner gewählt", it.findViewById<TextView>(R.id.quelle_ordner).text.toString())
            assertEquals("Letzte Übertragung: noch keine", it.findViewById<TextView>(R.id.quelle_zuletzt).text.toString())
            assertEquals("Ordner wählen", it.findViewById<TextView>(R.id.quelle_waehlen).text.toString())
        }
        val anleitung = texte(a.findViewById(R.id.sicherung_anleitung))
        listOf(
            "Ende-zu-Ende-verschlüsseltes Backup", "64-stelligen Schlüssel verwenden", "Vaultwarden",
            "Chat-Backups", "Auf dem Gerät", "30-stellige Passphrase", "com.whatsapp → WhatsApp",
            "je nach App-Version", "die sich selbst löschen", "einmaligen Ansicht",
        ).forEach { assertTrue("the guide lacks „$it“", anleitung.contains(it)) }
        assertFalse("no key field: keys go into the vault on the box", hat(a.window.decorView, EditText::class.java))
    }

    @Test
    fun jetztWithoutAFolderSaysWhatToDoFirst() {
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        a.findViewById<View>(R.id.sicherung_jetzt).performClick()
        assertEquals(
            "Erst einen Ordner freigeben, dann sichern.",
            a.findViewById<TextView>(R.id.sicherung_zustand).text.toString(),
        )
        val offen = WorkManager.getInstance(ctx()).getWorkInfosForUniqueWork(SicherungWorker.JETZT).get().filter { !it.state.isFinished }
        assertTrue("nothing enqueued: $offen", offen.isEmpty())
    }

    @Test
    fun theDailyRunWaitsForWlanChargerAndBattery() {
        val r = SicherungWorker.taeglich()
        val c = r.workSpec.constraints
        assertEquals(NetworkType.UNMETERED, c.requiredNetworkType)
        assertTrue(c.requiresCharging())
        assertTrue(c.requiresBatteryNotLow())
        assertEquals(TimeUnit.DAYS.toMillis(1), r.workSpec.intervalDuration)
        assertFalse("the job never goes foreground", r.workSpec.expedited)
    }

    @Test
    fun jetztWaitsOnlyForWlan() {
        val c = SicherungWorker.jetztAnfrage().workSpec.constraints
        assertEquals(NetworkType.UNMETERED, c.requiredNetworkType)
        assertFalse(c.requiresCharging())
        SicherungWorker.jetzt(ctx())
        SicherungWorker.jetzt(ctx())
        val infos = WorkManager.getInstance(ctx()).getWorkInfosForUniqueWork(SicherungWorker.JETZT).get()
        assertEquals("a second tap adds no second run", 1, infos.size)
        assertTrue(infos.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED))
        WorkManager.getInstance(ctx()).cancelAllWork()
    }

    private fun texte(v: View): String = when (v) {
        is TextView -> v.text.toString()
        is ViewGroup -> (0 until v.childCount).joinToString("\n") { texte(v.getChildAt(it)) }
        else -> ""
    }

    private fun hat(v: View, c: Class<*>): Boolean =
        c.isInstance(v) || (v is ViewGroup && (0 until v.childCount).any { hat(v.getChildAt(it), c) })
}
