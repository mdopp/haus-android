package cloud.dopp.homes.standort

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.StandortClient
import cloud.dopp.homes.data.TokenStore
import java.io.File
import kotlin.concurrent.thread

/** The permissions behind the switch: where, and in the background too. */
object StandortErlaubnis {
    val VORNE = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Android 10+: a separate permission, asked after [VORNE]; on 11+ Android's own page („Immer zulassen“). */
    const val HINTEN = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    private fun hat(ctx: Context, recht: String) =
        ContextCompat.checkSelfPermission(ctx, recht) == PackageManager.PERMISSION_GRANTED

    fun vorne(ctx: Context): Boolean = VORNE.all { hat(ctx, it) }
    fun hinten(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 || hat(ctx, HINTEN)

    /** All the recording needs: the location, in the background too. */
    fun genug(ctx: Context): Boolean = vorne(ctx) && hinten(ctx)
}

/**
 * The Android side of Standortverlauf, on the platform alone — no Google
 * Play services (the owner, 2026-11-02). Android's LocationManager delivers
 * the fixes by PendingIntent to [StandortEmpfaenger]: GPS, the network
 * provider or, on Android 12+, the platform's fused one, as [Anbieter]
 * picks for the plan's accuracy. While the phone rests, a second request on
 * the passive provider takes the fixes other apps cause, at no cost of its
 * own. The app holds no service, alarm, job or wakelock.
 *
 * Resting is [Ruhe]'s word from the fixes; it ends when a fix lies farther
 * away, or when the significant-motion sensor fires. That sensor is armed
 * while the phone rests, but only as long as the app's process lives: a
 * killed process loses it, and then the next fix ends the rest instead.
 */
object Aufzeichnung {
    private const val ORT = "cloud.dopp.homes.standort.ORT"

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

    /** The phone has a significant-motion sensor; without it the plan keeps the plain interval. */
    fun bewegungssensor(ctx: Context): Boolean =
        ctx.applicationContext.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null

    private fun absicht(ctx: Context, code: Int): PendingIntent {
        val i = Intent(ctx.applicationContext, StandortEmpfaenger::class.java).setAction(ORT)
        // mutable: LocationManager puts the fixes into the intent
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx.applicationContext, code, i, flags)
    }

    private fun lm(ctx: Context): LocationManager? = ctx.applicationContext.getSystemService(LocationManager::class.java)

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
        val sensor = bewegungssensor(app)
        val ruhe = store.ruhe()
        val lage = Plan.lage(if (sensor) ruhe.ruht else null, imWlan(app))
        if (lage != Lage.UNTERWEGS) Bewegung.scharf(app) else Bewegung.ab(app)
        val modus = store.modus()
        val a = Plan.anfrage(modus, lage)
        val lm = lm(app) ?: return
        val anbieter = Anbieter.waehle(a.genauigkeit, lm.getProviders(true).toSet(), Build.VERSION.SDK_INT)
        val key = modus.wire + "/" + lage.name + "/" + anbieter
        if (!neu && store.inKraft() == key) return
        val haupt = absicht(app, 1)
        val passiv = absicht(app, 3)
        try {
            lm.removeUpdates(haupt)
            lm.removeUpdates(passiv)
            if (anbieter == null) { // location off in Android's settings: nothing to ask
                store.setzeInKraft(null)
                return
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val q = if (a.genauigkeit == Genauigkeit.HOCH) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                val req = LocationRequest.Builder(a.intervallMs)
                    .setQuality(q)
                    .setMinUpdateIntervalMillis(a.schnellstensMs)
                    .setMinUpdateDistanceMeters(a.abstandM)
                    .setMaxUpdateDelayMillis(a.sammelnMs)
                    .build()
                lm.requestLocationUpdates(anbieter, req, haupt)
            } else {
                lm.requestLocationUpdates(anbieter, a.intervallMs, a.abstandM, haupt)
            }
            // resting: the fixes other apps cause come along, free, and can end the rest early
            if (lage != Lage.UNTERWEGS) {
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, Plan.MINUTE, 0f, passiv)
            }
            store.setzeInKraft(key)
        } catch (e: Exception) { // the permission went meanwhile, or the provider vanished
            store.setzeInKraft(null)
        }
    }

    /** Stops the requests and the sensor; the points collected stay until they are sent. */
    fun aus(ctx: Context) {
        val app = ctx.applicationContext
        lm(app)?.let { lm ->
            runCatching { lm.removeUpdates(absicht(app, 1)) }
            runCatching { lm.removeUpdates(absicht(app, 3)) }
        }
        Bewegung.ab(app)
        StandortStore(app).apply {
            setzeInKraft(null)
            setzeRuhe(Ruhe.Stand.NEU)
        }
    }

    /** The fixes in a LocationManager delivery: a batch on Android 12+, else one. */
    @Suppress("DEPRECATION")
    private fun fixe(intent: Intent): List<Location> {
        if (Build.VERSION.SDK_INT >= 31) {
            intent.getParcelableArrayExtra(LocationManager.KEY_LOCATIONS)?.mapNotNull { it as? Location }
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return listOfNotNull(intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED) as? Location)
    }

    /** A delivery from Android. Runs off the main thread. */
    fun empfangen(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val store = StandortStore(app)
        val punkte = fixe(intent).filter { it.hasAccuracy() }.map { l ->
            Punkt(
                zeit = l.time, breite = l.latitude, laenge = l.longitude, genauigkeit = l.accuracy,
                hoehe = if (l.hasAltitude()) l.altitude else null, tempo = if (l.hasSpeed()) l.speed else null,
            )
        }
        if (store.an() && punkte.isNotEmpty()) {
            store.mehrVerloren(puffer(app).dazu(punkte))
            store.setzeRuhe(Ruhe.weiter(store.ruhe(), punkte, store.modus()))
        }
        anpassen(app)
        if (online(app)) senden(app, gedraengt = false)
    }

    /** Sends what is due, with the paired Homes; [gedraengt]: the person asked. */
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

    /**
     * The significant-motion sensor: armed while the phone rests, it fires
     * once when the phone is carried off; the rest ends and the plan's own
     * rate comes back. One-shot: armed again at the next rest. It lives
     * with the process only.
     */
    private object Bewegung : TriggerEventListener() {
        @Volatile
        private var app: Context? = null

        @Volatile
        private var scharf = false

        fun scharf(ctx: Context) {
            if (scharf) return
            val sm = ctx.applicationContext.getSystemService(SensorManager::class.java) ?: return
            val s = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
            app = ctx.applicationContext
            scharf = runCatching { sm.requestTriggerSensor(this, s) }.getOrDefault(false)
        }

        fun ab(ctx: Context) {
            if (!scharf) return
            val sm = ctx.applicationContext.getSystemService(SensorManager::class.java) ?: return
            val s = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
            runCatching { sm.cancelTriggerSensor(this, s) }
            scharf = false
        }

        override fun onTrigger(event: TriggerEvent?) {
            scharf = false
            val a = app ?: return
            thread {
                runCatching {
                    StandortStore(a).setzeRuhe(Ruhe.Stand.NEU)
                    anpassen(a)
                }
            }
        }
    }
}

/** Receives Android's location deliveries ([Aufzeichnung]); not exported, reached by its PendingIntents only. */
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
