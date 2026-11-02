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
 * widget update period, no alarm, no wakelock of its own. Not `dataSync`
 * either — Android 15 caps that at 6 hours a day. No web shell, no voice, no
 * other notification.
 *
 * One job, and only one (the owner's call in #194, laib ADR 0029): the daily
 * pick-up of the phone's WhatsApp and Signal backups, a WorkManager worker
 * that never goes foreground and posts nothing. The folders come through the
 * system picker: no storage permission. Beside it, optional and each behind
 * its own switch and permission (laib ADR 0029): the contacts (READ_CONTACTS),
 * the SMS and the call log (READ_SMS, READ_CALL_LOG). Read only: never a
 * permission to write or send them.
 *
 * Standortverlauf (owner 2026-11-02), optional behind its own switch: the
 * location in the background, delivered by Android's own LocationManager by
 * PendingIntent to one receiver that is not exported. It adds no service,
 * no job, no alarm and no wakelock: the points go to Haus while a delivery
 * has the phone awake anyway.
 *
 * No Google Play services, anywhere (the owner's decision, 2026-11-02): no
 * `com.google.android.gms` library, class or permission.
 */
class ManifestTest {

    private val manifest by lazy { File(RepoFiles.main, "AndroidManifest.xml").readText() }
    private val gradle by lazy { File(RepoFiles.root, "app/build.gradle").readText() }
    private val sources by lazy { RepoFiles.mainKotlin().associate { RepoFiles.relative(it) to it.readText() } }

    /** The one file that may hold a foreground service and post its notification. */
    private val serviceFile = "app/src/main/java/cloud/dopp/haus/realtime/LiveService.kt"

    /** The one file that may name WorkManager: the backup job (#194). */
    private val workerFile = "app/src/main/java/cloud/dopp/haus/sicherung/SicherungWorker.kt"

    /** The source manifest's entries that take a library's entry out of the merged one. */
    private val removed = Regex("""<(service|receiver|provider|uses-permission)\b[^>]*tools:node="remove"[^>]*/>""")

    /** The manifest as Gradle merged it with the libraries', as the unit tests' Robolectric reads it. */
    private val merged by lazy {
        val props = java.util.Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use { props.load(it) }
        val path = props.getProperty("android_merged_manifest")!!
        val f = File(path).let { if (it.isAbsolute) it else File(RepoFiles.root, "app/$path") }
        f.readText()
    }

    private fun permissions(xml: String): Set<String> =
        Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(xml.replace(removed, ""))
            .map { it.groupValues[1].removePrefix("android.permission.") }.toSet()

    private fun noneSay(vararg needles: String, except: Set<String> = emptySet()): List<String> =
        sources.filterKeys { it !in except }
            .flatMap { (path, text) -> needles.filter { text.contains(it) }.map { "$path: $it" } }

    private val ours = setOf(
        "INTERNET",
        "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", // the live service
        "RECEIVE_BOOT_COMPLETED", // its restart after boot
        "POST_NOTIFICATIONS", // its notification, shown on Android 13+
        // its restart after the process died: on the exemption list a restart may go foreground
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        // Kontakte, SMS und Anrufe (laib ADR 0029): read only, each behind its own switch
        "READ_CONTACTS", "READ_SMS", "READ_CALL_LOG",
        // Standortverlauf (owner 2026-11-02): behind its own switch
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
        "ACCESS_NETWORK_STATE", // whether the phone is on a Wi-Fi
    )

    /**
     * Exactly these permissions: the network, what the live service needs,
     * and reading contacts, SMS and calls. No camera of the app's own: its
     * capture screen is gone (owner, 2026-11-02); the QR scanner brings its own. No storage, and
     * nothing that writes or sends contacts or messages.
     */
    @Test
    fun thePermissionsAreExactlyTheseAndNoOthers() {
        assertEquals(ours, permissions(manifest))
    }

    /**
     * What the libraries add, and nothing more: WorkManager (the backup job,
     * #194) brings its job service and, for it, the wake lock and the network
     * state; androidx.core its receiver guard; the QR scanner of the pairing
     * (zxing-android-embedded) the camera it scans with. No storage permission
     * in any form.
     */
    @Test
    fun theMergedManifestAddsOnlyWorkManagersOwn() {
        assertEquals(
            ours + setOf("WAKE_LOCK", "cloud.dopp.haus.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "CAMERA"),
            permissions(merged),
        )
        listOf("STORAGE", "READ_MEDIA", "ACCESS_MEDIA_LOCATION", "WRITE_CONTACTS", "SEND_SMS", "WRITE_CALL_LOG", "RECEIVE_SMS").forEach { word ->
            assertFalse("a storage permission: $word", permissions(merged).any { it.contains(word) })
        }
        val foreground = Regex("""android:foregroundServiceType="([^"]+)"""").findAll(merged).map { it.groupValues[1] }.toList()
        assertEquals("one foreground service, the live one", listOf("specialUse"), foreground)
        assertFalse("WorkManager's foreground service is taken out", merged.contains("androidx.work.impl.foreground.SystemForegroundService"))
        assertTrue("the job runs through JobScheduler", merged.contains("androidx.work.impl.background.systemjob.SystemJobService"))
    }

    /** Exactly one foreground service, `specialUse` only, with the reason Play asks for. */
    @Test
    fun oneSpecialUseForegroundServiceAndTheWidgetLists() {
        val services = Regex("""<service\b[^>]*>""").findAll(manifest.replace(removed, "")).map { it.value }.toList()
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
        listOf("energy_widget_info.xml", "box_overview_widget_info.xml", "box_services_widget_info.xml").forEach { name ->
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
    fun oneWorkerLibraryAndNoWebShellInTheBuild() {
        val work = Regex("""implementation\s+'(androidx\.work:[^:']+)""").findAll(gradle).map { it.groupValues[1] }.toList()
        assertEquals("WorkManager for the backup job only (#194)", listOf("androidx.work:work-runtime"), work)
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
     * Nothing that runs the app later or keeps the CPU up — no job (JobScheduler),
     * no alarm (AlarmManager), no wakelock (PowerManager) — but the one worker of
     * the backup (#194), in its one file, which never goes foreground. Asking
     * PowerManager whether the screen is on is fine; holding it awake is not.
     */
    @Test
    fun noJobNoAlarmNoWakelockAndOneWorkerInTheSources() {
        val hits = noneSay(
            "JobScheduler", "JobService", "JobInfo", "JOB_SCHEDULER_SERVICE",
            "AlarmManager", "ALARM_SERVICE", "setExactAndAllowWhileIdle", "setAndAllowWhileIdle", "setAlarmClock",
            "newWakeLock", "WakeLock", "PARTIAL_WAKE_LOCK", "acquire(",
            "setForeground", "ForegroundInfo", "CoroutineWorker",
        ) + noneSay("androidx.work", "WorkManager", "WorkRequest", except = setOf(workerFile))
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
        val worker = sources.getValue(workerFile)
        assertEquals("one worker", 1, Regex(""": Worker\(""").findAll(sources.values.joinToString("\n")).count())
        assertTrue(worker.contains("class SicherungWorker(") && worker.contains(": Worker("))
    }

    /**
     * Standortverlauf's receiver is reached by the app's own PendingIntents
     * only, and only Aufzeichnung asks Android for locations — by
     * PendingIntent, never a one-off poll (no single update, no current
     * location, no listener that needs the process).
     */
    @Test
    fun theLocationComesByPendingIntentToOneHiddenReceiver() {
        val receiver = manifest.substringAfter("""android:name=".standort.StandortEmpfaenger"""").substringBefore("/>")
        assertTrue(receiver, receiver.contains("""android:exported="false""""))
        assertFalse(receiver, receiver.contains("intent-filter"))
        val aufzeichnung = "app/src/main/java/cloud/dopp/haus/standort/Aufzeichnung.kt"
        val hits = noneSay("requestLocationUpdates", "LocationManager", "requestTriggerSensor", except = setOf(aufzeichnung)) +
            noneSay("requestSingleUpdate", "getCurrentLocation", "LocationListener")
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
        val calls = Regex("""requestLocationUpdates\(([^)]*)\)""").findAll(sources.getValue(aufzeichnung)).map { it.groupValues[1] }.toList()
        assertTrue("no request at all", calls.isNotEmpty())
        calls.forEach { assertTrue("a request not by PendingIntent: $it", it.trim().endsWith("haupt") || it.trim().endsWith("passiv")) }
    }

    /**
     * No Google Play services (the owner, 2026-11-02): not in the build, not
     * in the merged manifest, not in the sources. The app never needed them;
     * a phone without them works the same.
     */
    @Test
    fun noGooglePlayServices() {
        val gradles = RepoFiles.walk().filter { it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") || it.name.endsWith(".toml") }
        assertTrue("no build files found", gradles.any { it.name == "build.gradle" })
        val inBuild = gradles.filter { f -> f.readText().let { it.contains("com.google.android.gms") || it.contains("play-services") || it.contains("com.google.firebase") } }
            .map { RepoFiles.relative(it) }
        assertTrue("Google Play services in the build: $inBuild", inBuild.isEmpty())
        assertFalse("Google Play services in the merged manifest", merged.contains("com.google.android.gms"))
        val inSources = noneSay("com.google.android.gms", "com.google.firebase")
        assertTrue("left in the sources:\n" + inSources.joinToString("\n"), inSources.isEmpty())
    }

    @Test
    fun noJobServiceOrAlarmReceiverInTheManifest() {
        val own = manifest.replace(removed, "")
        listOf("BIND_JOB_SERVICE", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "androidx.work", "WAKE_LOCK").forEach {
            assertFalse("manifest names $it", own.contains(it))
        }
        val taken = removed.findAll(manifest).map { it.value }.toList()
        assertEquals(1, taken.size)
        assertTrue(taken.single(), taken.single().contains("androidx.work.impl.foreground.SystemForegroundService"))
    }

    /**
     * The backup folders come through the system picker and a persisted read
     * grant (#194): no broad storage access, and nothing in the app writes,
     * moves or deletes a document in WhatsApp's or Signal's folder.
     */
    @Test
    fun theFoldersAreReadThroughThePickerOnly() {
        val hits = noneSay(
            "MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "getExternalStorageDirectory",
            "deleteDocument", "renameDocument", "moveDocument", "copyDocument", "createDocument", "removeDocument",
            "openOutputStream", "FLAG_GRANT_WRITE_URI_PERMISSION",
        )
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
        val saf = sources.getValue("app/src/main/java/cloud/dopp/haus/sicherung/SafOrdner.kt")
        assertTrue(saf.contains("takePersistableUriPermission(baum, Intent.FLAG_GRANT_READ_URI_PERMISSION)"))
        val modes = Regex("""openFileDescriptor\([^)]*"(\w+)"\)""").findAll(saf).map { it.groupValues[1] }.toSet()
        assertEquals("read only", setOf("r"), modes)
        val screen = sources.getValue("app/src/main/java/cloud/dopp/haus/ui/SicherungActivity.kt")
        assertTrue(screen.contains("ActivityResultContracts.OpenDocumentTree()"))
    }

    /**
     * The widgets the owner took out on 2026-11-02 are gone from the manifest,
     * so the launcher's picker no longer offers them: the device widgets, the
     * active devices, the camera and the two energy widgets beside the one that
     * stays — with their configure screen, list service, capture screen and
     * file provider. Their sources and widget infos are gone too.
     */
    @Test
    fun theRemovedWidgetsAreNotInTheManifest() {
        val gone = listOf(
            "DeviceWidgetProvider", "DevicesWidgetProvider", "ActiveDevicesWidgetProvider", "CameraWidgetProvider",
            "EnergyStatsWidgetProvider", "EnergyTotalsWidgetProvider", "WidgetConfigActivity",
            "ActiveDevicesRemoteViewsService", "CameraCaptureActivity",
        )
        gone.forEach { name ->
            assertFalse("$name is still in the manifest", Regex("""android:name="\.(widget|ui)\.$name"""").containsMatchIn(manifest))
            assertFalse("$name is still in the merged manifest", merged.contains(".$name\""))
            assertTrue("$name.kt is still there", RepoFiles.mainKotlin().none { it.name == "$name.kt" })
        }
        assertFalse("the capture's file provider", manifest.contains("androidx.core.content.FileProvider"))
        assertFalse("the camera", manifest.contains("android.hardware.camera"))
        val providers = Regex("""<receiver\s+android:name="\.widget\.(\w+WidgetProvider)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("EnergyWidgetProvider", "BoxOverviewWidgetProvider", "BoxServicesWidgetProvider", "ToolWidgetProvider"),
            providers,
        )
        listOf(
            "device_widget_info", "devices_widget_info", "active_devices_widget_info", "camera_widget_info",
            "energy_stats_widget_info", "energy_totals_widget_info",
        ).forEach { assertFalse("res/xml/$it.xml is still there", File(RepoFiles.main, "res/xml/$it.xml").exists()) }
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
