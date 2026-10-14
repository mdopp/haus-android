package cloud.dopp.haus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the app is and is not (#176; the owner's call on how the widgets stay
 * live). It is widgets and pairing, kept live by one foreground service of
 * type `specialUse` that holds the stream while the screen is on, restarts
 * after boot, and shows one quiet notification for it. It is not a poll: no
 * widget update period, no job, no worker, no alarm, no wakelock. Not
 * `dataSync` either — Android 15 caps that at 6 hours a day. No web shell, no
 * voice, no other notification.
 */
class ManifestTest {

    private val manifest by lazy { File(RepoFiles.main, "AndroidManifest.xml").readText() }
    private val gradle by lazy { File(RepoFiles.root, "app/build.gradle").readText() }
    private val sources by lazy { RepoFiles.mainKotlin().associate { RepoFiles.relative(it) to it.readText() } }

    /** The one file that may hold a foreground service and post its notification. */
    private val serviceFile = "app/src/main/java/cloud/dopp/haus/realtime/LiveService.kt"

    private fun noneSay(vararg needles: String, except: Set<String> = emptySet()): List<String> =
        sources.filterKeys { it !in except }
            .flatMap { (path, text) -> needles.filter { text.contains(it) }.map { "$path: $it" } }

    /** Exactly these permissions: the network, the camera capture, and what the live service needs. */
    @Test
    fun thePermissionsAreExactlyTheseAndNoOthers() {
        val asked = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest)
            .map { it.groupValues[1].removePrefix("android.permission.") }.toSet()
        assertEquals(
            setOf(
                "INTERNET", "CAMERA",
                "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", // the live service
                "RECEIVE_BOOT_COMPLETED", // its restart after boot
                "POST_NOTIFICATIONS", // its notification, shown on Android 13+
                // its restart after the process died: on the exemption list a restart may go foreground
                "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            ),
            asked,
        )
    }

    /** Exactly one foreground service, `specialUse` only, with the reason Play asks for. */
    @Test
    fun oneSpecialUseForegroundServiceAndTheWidgetLists() {
        val services = Regex("""<service\b[^>]*>""").findAll(manifest).map { it.value }.toList()
        val foreground = services.filter { it.contains("foregroundServiceType") }
        assertEquals("foreground services: $foreground", 1, foreground.size)
        val fgs = foreground.single()
        assertTrue(fgs, fgs.contains("""android:name=".realtime.LiveService""""))
        assertTrue(fgs, fgs.contains("""android:foregroundServiceType="specialUse""""))
        assertTrue(fgs, fgs.contains("""android:exported="false""""))
        val block = manifest.substringAfter(fgs).substringBefore("</service>")
        assertTrue("no special-use subtype", block.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue("the subtype does not say what it is for", block.contains("live widget connection to the user's own Haus server"))
        (services - fgs).forEach { assertTrue("$it is not a widget list", it.contains("RemoteViewsService\"")) }
        assertFalse("dataSync is capped at 6 h a day", manifest.contains("dataSync"))
    }

    /** One receiver restarts the live service after a boot and after an app update, both allowed starts. */
    @Test
    fun theBootReceiverRestartsTheLiveServiceOnly() {
        assertEquals(1, Regex("""android.intent.action.BOOT_COMPLETED""").findAll(manifest).count())
        assertEquals(1, Regex("""android.intent.action.MY_PACKAGE_REPLACED""").findAll(manifest).count())
        val receiver = manifest.substringAfter("""android:name=".realtime.BootReceiver"""").substringBefore("</receiver>")
        assertTrue(receiver, receiver.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(receiver, receiver.contains("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    /**
     * The fed widgets without a configure screen of their own get the shared
     * no-UI one, required, so placing one from the launcher is a visible
     * activity — an allowed start for the live service on Android 12+.
     */
    @Test
    fun theFedWidgetsWithoutAScreenStartTheServiceWhenPlaced() {
        listOf(
            "energy_widget_info.xml", "energy_stats_widget_info.xml", "energy_totals_widget_info.xml",
            "active_devices_widget_info.xml", "box_overview_widget_info.xml", "box_services_widget_info.xml",
        ).forEach { name ->
            val info = File(RepoFiles.main, "res/xml/$name").readText()
            assertTrue(name, info.contains("""android:configure="cloud.dopp.haus.widget.LiveConfigActivity""""))
            assertFalse("$name: an optional configure step is skipped at placement", info.contains("configuration_optional"))
        }
        val activity = manifest.substringAfter("""android:name=".widget.LiveConfigActivity"""").substringBefore("</activity>")
        assertTrue(activity, activity.contains("android.appwidget.action.APPWIDGET_CONFIGURE"))
        assertTrue(activity, activity.contains("Translucent"))
    }

    /** No widget asks Android to wake it: the live service resyncs on screen-on. */
    @Test
    fun noWidgetPolls() {
        val infos = File(RepoFiles.main, "res/xml").listFiles()!!.filter { it.name.endsWith("_widget_info.xml") }
        assertTrue("no widget info files", infos.isNotEmpty())
        val polling = infos.mapNotNull { f ->
            val period = Regex("""android:updatePeriodMillis="(\d+)"""").find(f.readText())?.groupValues?.get(1)
            if (period != "0") "${f.name}: ${period ?: "not set"}" else null
        }
        assertTrue("widgets that poll:\n" + polling.joinToString("\n"), polling.isEmpty())
    }

    @Test
    fun noBackgroundWorkAndNoWebShellInTheBuild() {
        assertFalse("WorkManager (the rescue worker)", gradle.contains("androidx.work"))
        assertFalse("the Bubblewrap TWA block", gradle.contains("twaManifest"))
        assertFalse("android-browser-helper", gradle.contains("androidbrowserhelper"))
    }

    /** The live service's notification is the only one, and only it goes foreground. */
    @Test
    fun noOtherNotificationNoWebShellNoVoiceInTheSources() {
        val hits = noneSay(
            "NotificationCompat", "NotificationManager", "NotificationChannel", "startForeground",
            except = setOf(serviceFile),
        ) + noneSay(
            "TrustedWebActivity", "androidx.browser.trusted", "RecognizerIntent", "SpeechRecognizer",
            "DATA_SYNC", "dataSync",
        )
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
        assertTrue("the live service has no notification", sources[serviceFile]?.contains("startForeground") == true)
    }

    /**
     * Nothing that runs the app later or keeps the CPU up: no job (JobScheduler),
     * no worker (WorkManager), no alarm (AlarmManager), no wakelock (PowerManager).
     * Asking PowerManager whether the screen is on is fine; holding it awake is not.
     */
    @Test
    fun noJobNoWorkerNoAlarmNoWakelockInTheSources() {
        val hits = noneSay(
            "JobScheduler", "JobService", "JobInfo", "JOB_SCHEDULER_SERVICE",
            "androidx.work", "WorkManager", "WorkRequest", "CoroutineWorker",
            "AlarmManager", "ALARM_SERVICE", "setExactAndAllowWhileIdle", "setAndAllowWhileIdle", "setAlarmClock",
            "newWakeLock", "WakeLock", "PARTIAL_WAKE_LOCK", "acquire(",
        )
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun noJobServiceOrAlarmReceiverInTheManifest() {
        listOf("BIND_JOB_SERVICE", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "androidx.work", "WAKE_LOCK").forEach {
            assertFalse("manifest names $it", manifest.contains(it))
        }
    }

    @Test
    fun noCatchUpNoApprovalsNoDiagnostics() {
        val gone = listOf("Notice", "Approvals", "Diagnostics", "RealtimePoll", "RealtimeService", "Voice", "Pwa")
        val left = RepoFiles.mainKotlin().map { it.name }.filter { n -> gone.any { n.startsWith(it) } }
        assertTrue("left: $left", left.isEmpty())
        val layouts = File(RepoFiles.main, "res/layout").list()!!.toList()
        val oldLayouts = layouts.filter {
            it.contains("approval") || it.contains("notice") || it.contains("diagnostics") || it.contains("voice")
        }
        assertTrue("layouts left: $oldLayouts", oldLayouts.isEmpty())
    }
}
