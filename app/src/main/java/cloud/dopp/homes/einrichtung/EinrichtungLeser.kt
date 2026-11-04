package cloud.dopp.homes.einrichtung

import android.content.Context
import android.content.Intent
import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.data.asBody
import cloud.dopp.homes.data.json
import cloud.dopp.homes.data.request
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.widget.AppShortcuts
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * `GET /napi/einrichtung` and `POST /napi/einrichtung/apps` (owner
 * 2026-11-04). Blocking; call it off the main thread.
 */
class EinrichtungClient(private val base: String, private val token: String, private val http: OkHttpClient) {

    /** What a call came to: a new answer, 404 from an older Homes, or nothing to go by. */
    sealed class Antwort {
        data class Neu(val body: String) : Antwort()
        object Fehlt : Antwort()
        object Nichts : Antwort()
    }

    fun lesen(): Antwort = rufe(Napi.EINRICHTUNG.request(base, token))

    /** The phone has [pakete]: Homes ticks the open tasks whose app is among them and answers as [lesen]. */
    fun melden(pakete: List<String>): Antwort =
        rufe(Napi.EINRICHTUNG_APPS.request(base, token, body = Napi.EINRICHTUNG_APPS.json(JSONArray(pakete)).asBody()))

    // No answer is an answer here: the kept state stays.
    @Suppress("SwallowedException")
    private fun rufe(req: okhttp3.Request): Antwort = try {
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> Antwort.Fehlt
                resp.isSuccessful -> Antwort.Neu(resp.body?.string().orEmpty())
                else -> Antwort.Nichts
            }
        }
    } catch (e: IOException) {
        Antwort.Nichts
    }
}

/**
 * When the app asks for the person's modules and tasks, and tells Homes which
 * of the modules' apps the phone has: at start and resume, at most every five
 * minutes ([EinrichtungStore.ABSTAND_MS]), never while the screen is off. A
 * changed answer is said with [ACTION_GEAENDERT], and the launcher's
 * shortcuts are built again from it.
 */
object EinrichtungLeser {

    const val ACTION_GEAENDERT = "cloud.dopp.homes.einrichtung.GEAENDERT"

    private val laeuft = AtomicBoolean(false)

    private val HTTP: OkHttpClient by lazy { HomesHttp.client(connect = 10, read = 15) }

    /** Ask, off the main thread, when due; [immer] skips the wait. */
    fun lesen(ctx: Context, immer: Boolean = false) {
        val app = ctx.applicationContext
        if (!ServerStore.isConfigured(app) || !TokenStore.isPaired(app) || !ScreenGate.open(app)) return
        if (!EinrichtungStore.faellig(app, System.currentTimeMillis(), immer)) return
        if (!laeuft.compareAndSet(false, true)) return
        thread {
            try {
                if (einmal(app)) geaendert(app)
            } finally {
                laeuft.set(false)
            }
        }
    }

    /** One read and, when an open task's app is on the phone, one report; true when the kept answer changed. */
    fun einmal(ctx: Context, client: EinrichtungClient? = null, hat: (String) -> Boolean = { installiert(ctx, it) }): Boolean {
        val c = client ?: EinrichtungClient(
            ServerStore.baseUrl(ctx) ?: return false, TokenStore.get(ctx) ?: return false, Verbindung.gemeldet(ctx, HTTP),
        )
        var changed = when (val a = c.lesen()) {
            is EinrichtungClient.Antwort.Neu -> EinrichtungStore.neu(ctx, a.body)
            EinrichtungClient.Antwort.Fehlt -> return EinrichtungStore.fehlt(ctx)
            EinrichtungClient.Antwort.Nichts -> return false
        }
        val pakete = EinrichtungStore.einrichtung(ctx)?.installiert(hat).orEmpty()
        if (pakete.isNotEmpty()) {
            val a = c.melden(pakete)
            if (a is EinrichtungClient.Antwort.Neu) changed = EinrichtungStore.neu(ctx, a.body) || changed
        }
        return changed
    }

    /** Is [paket] on the phone? Android 11+ answers only for the packages the manifest's `<queries>` names. */
    fun installiert(ctx: Context, paket: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(paket, 0) }.isSuccess

    /** Say it: the start screen draws again, and the launcher's shortcuts follow the answer. */
    fun geaendert(ctx: Context) {
        runCatching { ctx.sendBroadcast(Intent(ACTION_GEAENDERT).setPackage(ctx.packageName)) }
        AppShortcuts.refreshAsync(ctx)
    }
}
