package cloud.dopp.haus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the new app is not (#176, laib ADR 0022 §1–2): no foreground service,
 * no notifications, no boot start, no background poll, no web shell, no voice.
 * Widgets stay current through one live stream, only while the screen is on —
 * so nothing in the manifest may keep the app alive or wake it, and nothing in
 * the sources may post a notification, schedule work, set an alarm, hold a
 * wakelock or open a Trusted Web Activity.
 */
class ManifestTest {

    private val manifest by lazy { File(RepoFiles.main, "AndroidManifest.xml").readText() }
    private val gradle by lazy { File(RepoFiles.root, "app/build.gradle").readText() }
    private val sources by lazy { RepoFiles.mainKotlin().associate { RepoFiles.relative(it) to it.readText() } }

    private fun noneSay(vararg needles: String): List<String> =
        sources.flatMap { (path, text) -> needles.filter { text.contains(it) }.map { "$path: $it" } }

    @Test
    fun noPermissionThatKeepsTheAppAliveOrTalksToThePerson() {
        listOf(
            "FOREGROUND_SERVICE", "POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK",
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "RECORD_AUDIO",
        ).forEach { assertFalse("manifest asks for $it", manifest.contains("android.permission.$it")) }
    }

    @Test
    fun theOnlyServicesAreTheWidgetLists() {
        val services = Regex("""<service[^>]*android:name="([^"]+)"""").findAll(manifest)
            .map { it.groupValues[1] }.toList()
        services.forEach { assertTrue("$it is not a widget list", it.endsWith("RemoteViewsService")) }
        assertFalse("a foreground service type", manifest.contains("foregroundServiceType"))
        assertFalse("a boot receiver", manifest.contains("BOOT_COMPLETED"))
    }

    @Test
    fun noBackgroundWorkAndNoWebShellInTheBuild() {
        assertFalse("WorkManager (the rescue worker)", gradle.contains("androidx.work"))
        assertFalse("the Bubblewrap TWA block", gradle.contains("twaManifest"))
        assertFalse("android-browser-helper", gradle.contains("androidbrowserhelper"))
    }

    @Test
    fun noNotificationNoAlarmNoWebShellNoVoiceInTheSources() {
        val hits = noneSay(
            "NotificationCompat", "NotificationManager", "NotificationChannel", "startForeground",
            "AlarmManager", "WorkManager", "TrustedWebActivity", "androidx.browser.trusted",
            "RecognizerIntent", "SpeechRecognizer",
        )
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
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
            "AlarmManager", "ALARM_SERVICE", "setExactAndAllowWhileIdle", "setAlarmClock",
            "newWakeLock", "WakeLock", "PARTIAL_WAKE_LOCK", "acquire(",
        )
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun noJobServiceOrAlarmReceiverInTheManifest() {
        listOf("BIND_JOB_SERVICE", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "androidx.work").forEach {
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
