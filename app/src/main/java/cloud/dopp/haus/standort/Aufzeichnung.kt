package cloud.dopp.haus.standort

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.StandortClient
import cloud.dopp.haus.data.TokenStore
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import kotlin.concurrent.thread

/** The permissions behind the switch: where (and in the background), and the optional „Körperliche Aktivität“. */
object StandortErlaubnis {
    val VORNE = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Android 10+: a separate permission, asked after [VORNE]; on 11+ Android's own page („Immer zulassen“). */
    const val HINTEN = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    /** Android 10+: activity recognition, to know when the phone rests; before that a normal permission. */
    const val AKTIVITAET = Manifest.permission.ACTIVITY_RECOGNITION

    private fun hat(ctx: Context, recht: String) =
        ContextCompat.checkSelfPermission(ctx, recht) == PackageManager.PERMISSION_GRANTED

    fun vorne(ctx: Context): Boolean = VORNE.all { hat(ctx, it) }
    fun hinten(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 || hat(ctx, HINTEN)
    fun aktivitaet(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 || hat(ctx, AKTIVITAET)

    /** All the recording needs: the location, in the background too. */
    fun genug(ctx: Context): Boolean = vorne(ctx) && hinten(ctx)
}

/**
 * The Android side of Standortverlauf: the fused provider's location updates
 * and activity recognition's still/moving transitions, both delivered by
 * PendingIntent to [StandortEmpfaenger] — Android wakes the app for them, the
 * app holds no service, alarm, job or wakelock of its own. Each delivery
 * stores the points, looks whether the request still fits ([Plan]) and, when
 * [Senden] says so, sends a batch while the phone is awake anyway.
 */
object Aufzeichnung {
    private const val ORT = "cloud.dopp.haus.standort.ORT"
    private const val BEWEGUNG = "cloud.dopp.haus.standort.BEWEGUNG"

    fun puffer(ctx: Context) = Puffer(File(ctx.applicationContext.filesDir, "standort/punkte.txt"))

    /** Connected to a Wi-Fi now (any; the owner, 2026-11-02). Needs no location permission. */
    fun imWlan(ctx: Context): Boolean {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun online(ctx: Context): Boolean {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun absicht(ctx: Context, aktion: String, code: Int): PendingIntent {
        val i = Intent(ctx.applicationContext, StandortEmpfaenger::class.java).setAction(aktion)
        // mutable: Play services puts the result into the intent
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx.applicationContext, code, i, flags)
    }

    /**
     * Brings Android's requests in line with the switch, the mode and the
     * phone's [Lage]: off or without permission, none; else the plan's.
     * [neu]: ask again even when the same request is in force (after a
     * boot, an update, a tap on the switch).
     */
    @SuppressLint("MissingPermission") // checked: StandortErlaubnis
    fun anpassen(ctx: Context, neu: Boolean = false) {
        val app = ctx.applicationContext
        val store = StandortStore(app)
        if (!store.an() || !StandortErlaubnis.genug(app)) {
            aus(app)
            return
        }
        val lage = Plan.lage(if (StandortErlaubnis.aktivitaet(app)) store.ruht() else null, imWlan(app))
        val modus = store.modus()
        val key = modus.wire + "/" + lage.name
        if (!neu && store.inKraft() == key) return
        val a = Plan.anfrage(modus, lage)
        val prio = if (a.genauigkeit == Genauigkeit.HOCH) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val req = LocationRequest.Builder(prio, a.intervallMs)
            .setMinUpdateIntervalMillis(a.schnellstensMs)
            .setMinUpdateDistanceMeters(a.abstandM)
            .setMaxUpdateDelayMillis(a.sammelnMs)
            .build()
        try {
            LocationServices.getFusedLocationProviderClient(app).requestLocationUpdates(req, absicht(app, ORT, 1))
            store.setzeInKraft(key)
        } catch (e: Exception) { // no Play services, or the permission went meanwhile
            store.setzeInKraft(null)
        }
        if (neu && StandortErlaubnis.aktivitaet(app)) {
            val uebergaenge = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(it).build()
            }
            try {
                ActivityRecognition.getClient(app).requestActivityTransitionUpdates(ActivityTransitionRequest(uebergaenge), absicht(app, BEWEGUNG, 2))
            } catch (e: Exception) {
                store.setzeRuht(null)
            }
        }
    }

    /** Stops both requests; the points collected stay until they are sent. */
    @SuppressLint("MissingPermission")
    fun aus(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { LocationServices.getFusedLocationProviderClient(app).removeLocationUpdates(absicht(app, ORT, 1)) }
        runCatching { ActivityRecognition.getClient(app).removeActivityTransitionUpdates(absicht(app, BEWEGUNG, 2)) }
        StandortStore(app).apply {
            setzeInKraft(null)
            setzeRuht(null)
        }
    }

    /** A delivery from Android: points or a transition. Runs off the main thread. */
    fun empfangen(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val store = StandortStore(app)
        if (LocationResult.hasResult(intent)) {
            val punkte = LocationResult.extractResult(intent)?.locations.orEmpty().filter { it.hasAccuracy() }.map { l ->
                Punkt(
                    zeit = l.time, breite = l.latitude, laenge = l.longitude, genauigkeit = l.accuracy,
                    hoehe = if (l.hasAltitude()) l.altitude else null, tempo = if (l.hasSpeed()) l.speed else null,
                )
            }
            if (store.an()) store.mehrVerloren(puffer(app).dazu(punkte))
        }
        if (ActivityTransitionResult.hasResult(intent)) {
            ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull { it.activityType == DetectedActivity.STILL }?.let {
                store.setzeRuht(it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
            }
        }
        anpassen(app)
        if (online(app)) senden(app, gedraengt = false)
    }

    /** Sends what is due, with the paired Haus; [gedraengt]: the person asked. */
    fun senden(ctx: Context, gedraengt: Boolean): Sender.Lauf? {
        val app = ctx.applicationContext
        val base = ServerStore.baseUrl(app) ?: return null
        val token = TokenStore.get(app)?.takeIf { it.isNotBlank() } ?: return null
        val store = StandortStore(app)
        val client = StandortClient(base, token)
        return Sender(puffer(app), store, Kanal { client.senden(it) }).senden(store.modus(), gedraengt)
    }

    /** After a boot or an app update Android has forgotten the requests: ask again. */
    fun nachStart(ctx: Context) {
        if (StandortStore(ctx).an()) anpassen(ctx, neu = true)
    }
}

/** Receives Android's location and activity deliveries ([Aufzeichnung]); not exported, reached by its PendingIntents only. */
class StandortEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val warte = goAsync()
        thread {
            try {
                Aufzeichnung.empfangen(app, intent)
            } catch (e: Exception) {
                // a failed delivery loses nothing that was stored; the next one tries again
            } finally {
                warte.finish()
            }
        }
    }
}
