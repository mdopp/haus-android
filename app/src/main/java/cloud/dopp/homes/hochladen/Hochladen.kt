package cloud.dopp.homes.hochladen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.standort.Import
import cloud.dopp.homes.ui.HomeActivity
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * „Hochladen“, the app's second background job (owner, 2026-11-04: a large
 * upload keeps going with the screen off, with its own notification): the
 * files shared to the music and the Takeout ZIPs for the Standortverlauf go
 * up one after the other from the app's own copy ([Auftraege]).
 *
 * While it uploads it runs in the foreground, Android's `dataSync` type,
 * and its notification shows how far it got; it is the app's second
 * notification beside the live widgets'. Android 15 allows that type six
 * hours a day: when they are used up, or Android does not let it start,
 * or the line drops, the notification says in German that the upload goes
 * on later, and WorkManager runs it again — never a silent stop. A music
 * file starts again from the beginning (Homes takes it in one request); a
 * Standortverlauf goes on where Homes stands.
 */
object Hochladen {
    /** The notification channel, the unique work and the copies' folder share the job's name. */
    private const val NAME = "hochladen"
    private const val KANAL = NAME
    private const val ID_LAUF = 7301
    private const val ID_ENDE = 7302
    private const val ARBEIT = NAME
    private const val ORDNER = NAME

    /**
     * Copy [uri] into the app's files, streamed, queue it for [ziel] and start
     * the job; false when it could not be read. Blocking: call off the main thread.
     */
    fun uebernehmen(ctx: Context, resolver: ContentResolver, uri: Uri, name: String, ziel: Ziel): Boolean {
        val id = UUID.randomUUID().toString()
        val datei = File(File(ctx.filesDir, ORDNER).apply { mkdirs() }, id)
        val ok = runCatching {
            resolver.openInputStream(uri)?.use { ein -> datei.outputStream().use { ein.copyTo(it, PUFFER) } } != null
        }.getOrDefault(false)
        if (!ok || datei.length() <= 0L) {
            datei.delete()
            return false
        }
        Auftraege.hinzu(ctx, Auftrag(id, ziel, datei.path, name, datei.length()))
        anstossen(ctx)
        return true
    }

    /** Start the job when files wait: after a share, and when the app's screen opens (Android 15 resets its hours then). */
    fun anstossen(ctx: Context) {
        if (Auftraege.alle(ctx).isEmpty()) return
        val arbeit = OneTimeWorkRequest.Builder(HochladenWorker::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork(ARBEIT, ExistingWorkPolicy.APPEND_OR_REPLACE, arbeit) }
    }

    private fun kanal(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val k = NotificationChannel(KANAL, ctx.getString(R.string.hochladen_kanal), NotificationManager.IMPORTANCE_LOW)
            k.description = ctx.getString(R.string.hochladen_kanal_text)
            nm.createNotificationChannel(k)
        }
        return nm
    }

    private fun bau(ctx: Context, titel: String, text: String) = NotificationCompat.Builder(ctx, KANAL)
        .setSmallIcon(R.drawable.ic_stat_homes)
        .setContentTitle(titel)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(
            PendingIntent.getActivity(
                ctx, 0, Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    /** The running upload's notification: [name], [prozent] done (-1: not known yet). */
    fun lauf(ctx: Context, name: String, prozent: Int, rest: Int): Notification {
        kanal(ctx)
        val text = if (rest > 0) ctx.getString(R.string.hochladen_noch, rest) else ctx.getString(R.string.hochladen_laeuft)
        return bau(ctx, name, text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(PROZENT, prozent.coerceAtLeast(0), prozent < 0)
            .build()
    }

    fun info(n: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ID_LAUF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_LAUF, n)
        }

    /** The running upload's notification, updated in place (also when Android let it not run in front). */
    fun zeigeLauf(ctx: Context, n: Notification) {
        runCatching { kanal(ctx)?.notify(ID_LAUF, n) }
    }

    /**
     * What came of a file (one notification per file, by its [auftrag] id), or that
     * the upload goes on later: the same quiet channel, one line, stays until read.
     */
    fun sagen(ctx: Context, titel: String, text: String, auftrag: String? = null) {
        runCatching {
            val nm = kanal(ctx) ?: return
            nm.cancel(ID_LAUF)
            if (auftrag != null) nm.cancel(ID_ENDE) // the „goes on later“ line is over
            nm.notify(auftrag?.let { ID_ENDE + 1 + (it.hashCode() and 0xffff) } ?: ID_ENDE, bau(ctx, titel, text).setAutoCancel(true).build())
        }
    }

    /** The queue is done: no running notification is left behind. */
    fun leer(ctx: Context) {
        Stand.leer(ctx)
        runCatching { kanal(ctx)?.cancel(ID_LAUF) }
    }

    /** The job stops before the queue is done: the notification and the app's row say [text]. */
    fun pause(ctx: Context, text: String) {
        Stand.pause(ctx, text)
        sagen(ctx, ctx.getString(R.string.hochladen_spaeter), text)
    }

    /** The results were seen in the app: their notifications go (not the running or paused one). */
    fun ergebnisseWeg(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.activeNotifications.filter { it.id > ID_ENDE && it.id <= ID_ENDE + 1 + 0xffff }.forEach { nm.cancel(it.id) }
        }
    }

    /** Why the job stops before the queue is done, in the family's words. */
    fun spaeter(ctx: Context, grund: Int): String = ctx.getString(
        if (grund == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT || grund == AUSGEBREMST) {
            R.string.hochladen_heute_nicht
        } else {
            R.string.hochladen_spaeter_text
        },
    )

    /** Android refused to let the job run in front (its daily hours, or from the background). */
    const val AUSGEBREMST = -2

    private const val PROZENT = 100
    private const val PUFFER = 256 * 1024
}

/** The Hochladen job's one run: the queue, in order, until it is empty or Android stops it. */
class HochladenWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    /** Android said no to running in front: the run goes on in the back while it may, and says so if it ends early. */
    private var ausgebremst = false

    override fun doWork(): Result {
        val ctx = applicationContext
        val base = ServerStore.baseUrl(ctx)
        val token = TokenStore.get(ctx)
        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            Hochladen.pause(ctx, ctx.getString(R.string.share_not_paired))
            return Result.success() // the files wait; the next pairing's screen starts the job again
        }
        while (!isStopped) {
            val alle = Auftraege.alle(ctx)
            val a = alle.firstOrNull() ?: return Result.success().also { Hochladen.leer(ctx) }
            val datei = File(a.datei)
            if (!datei.isFile) {
                Auftraege.weg(ctx, a.id)
                continue
            }
            zeige(a.name, -1, alle.size - 1, erst = true)
            val ende = when (a.ziel) {
                Ziel.MUSIK -> musik(base, token, a, datei, alle.size - 1)
                Ziel.STANDORT -> standort(base, token, a, datei, alle.size - 1)
            } ?: break
            Auftraege.weg(ctx, a.id)
            datei.delete()
            val inDerMusik = a.ziel == Ziel.MUSIK && ende.first == ctx.getString(R.string.musik_fertig)
            val woher = ctx.getString(if (a.ziel == Ziel.MUSIK) R.string.musik_titel else R.string.standort_import_title)
            Stand.fertig(ctx, inDerMusik, "$woher: ${ende.second}")
            Hochladen.sagen(ctx, ende.first, ende.second, a.id)
        }
        Hochladen.pause(ctx, Hochladen.spaeter(ctx, grund()))
        return Result.retry()
    }

    /** Why the run ends early: Android refused the front, or its stop reason (known from Android 12). */
    private fun grund(): Int = when {
        ausgebremst -> Hochladen.AUSGEBREMST
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> stopReason
        else -> WorkInfo.STOP_REASON_UNKNOWN
    }

    private var zuletzt = -1

    /** The notification for [name] at [prozent]; the first one asks to run in front. */
    @Suppress("SwallowedException") // Android's no is the answer: the run goes on in the back, and says so if it ends early
    private fun zeige(name: String, prozent: Int, rest: Int, erst: Boolean = false) {
        if (!erst && prozent == zuletzt) return
        zuletzt = prozent
        Stand.lauf(applicationContext, name, prozent)
        val n = Hochladen.lauf(applicationContext, name, prozent, rest)
        if (erst && !ausgebremst) {
            ausgebremst = try {
                setForegroundAsync(Hochladen.info(n)).get()
                false
            } catch (e: ExecutionException) {
                true // Android 15's hours used up, or a start from the background it does not allow
            } catch (e: IllegalStateException) {
                true
            }
        }
        Hochladen.zeigeLauf(applicationContext, n)
    }

    /** Title and line of how a music file ended; null when it goes again later. */
    private fun musik(base: String, token: String, a: Auftrag, datei: File, rest: Int): Pair<String, String>? {
        val ctx = applicationContext
        val r = MusikClient(base, token).senden(datei, a.name, { zeige(a.name, prozent(it, a.groesse), rest) }, { isStopped })
        return when (r) {
            is MusikClient.Ausgang.Fertig -> ctx.getString(R.string.musik_fertig) to ctx.getString(R.string.musik_fertig_text, a.name)
            MusikClient.Ausgang.Fehlt -> ctx.getString(R.string.musik_nicht) to ctx.getString(R.string.musik_noch_nicht)
            is MusikClient.Ausgang.Abgelehnt -> if (r.code == 401) {
                ctx.getString(R.string.musik_nicht) to ctx.getString(R.string.share_not_paired)
            } else {
                ctx.getString(R.string.musik_nicht) to (r.satz ?: ctx.getString(R.string.musik_abgelehnt))
            }
            MusikClient.Ausgang.Offline -> null
        }
    }

    /** Title and line of how a Standortverlauf ended; null when it goes on later, where Homes stands. */
    private fun standort(base: String, token: String, a: Auftrag, datei: File, rest: Int): Pair<String, String>? {
        val ctx = applicationContext
        val r = Import(cloud.dopp.homes.data.StandortImportClient(base, token)).uebernehmen(
            name = a.name,
            groesse = a.groesse,
            oeffnen = { ab -> ab(datei, ab) },
            fortschritt = { f ->
                when (f) {
                    is Import.Fortschritt.Hochladen -> zeige(a.name, prozent(f.gesendet, f.groesse), rest)
                    is Import.Fortschritt.Uebernehmen -> zeige(ctx.getString(R.string.standort_import_title), prozent(f.gelesen, f.groesse), rest)
                }
            },
            halt = { isStopped },
        )
        val nicht = ctx.getString(R.string.standort_import_nicht)
        return when (r) {
            is Import.Ausgang.Fertig -> ctx.getString(R.string.standort_import_fertig) to r.text
            is Import.Ausgang.Fehler -> nicht to (r.satz ?: ctx.getString(R.string.standort_import_abgelehnt))
            Import.Ausgang.NichtGekoppelt -> nicht to ctx.getString(R.string.share_not_paired)
            Import.Ausgang.Still -> ctx.getString(R.string.standort_import_title) to ctx.getString(R.string.standort_import_still)
            Import.Ausgang.Unerreichbar, Import.Ausgang.Angehalten -> null
        }
    }

    private fun ab(datei: File, n: Long): InputStream {
        val s = FileInputStream(datei)
        if (n > 0 && !Import.ueberspringe(s, n)) {
            s.close()
            throw IOException("the copy got shorter")
        }
        return s
    }

    private fun prozent(teil: Long, ganz: Long): Int = if (ganz <= 0) -1 else ((teil * 100) / ganz).toInt().coerceIn(0, 100)
}
