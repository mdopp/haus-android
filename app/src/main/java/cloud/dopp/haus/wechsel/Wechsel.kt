package cloud.dopp.haus.wechsel

import android.content.Context
import cloud.dopp.haus.data.BoxApiClient
import cloud.dopp.haus.data.Napi.AppJson
import org.json.JSONObject
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Haus heißt jetzt Homes (the owner's call, 2026-11-03, laib#1021): the server
 * is renamed and so is the app, under a new package — a new app beside this
 * one. This build is the last of `cloud.dopp.haus`; it asks the server which
 * app it speaks to (`GET /napi/app`) and, when that is another package, tells
 * the person in German to install it, pair it and place the widgets again,
 * and once it is installed, that Haus can go.
 *
 * The decision ([hinweis]) is plain; the server's last answer is kept in plain
 * prefs (nothing secret) so the start screen shows it at once. A server from
 * before answers 404: nothing is shown. A check that fails keeps what was
 * known and shows the person nothing about the failure.
 *
 * Asked on the app's start ([HomeActivity][cloud.dopp.haus.ui.HomeActivity])
 * and, for the people who only use the widgets, from the live service once its
 * stream is open, at most once a day ([taeglich]), which then posts the one
 * notice ([WechselNachricht]). No poll of its own.
 */
object Wechsel {

    /** What the server said about its app. */
    sealed class Antwort {
        /** It names none: a 404 from a server before laib#1021, or no package in the reply. */
        object Keine : Antwort()

        /** It names [paket], called [name], which phones get from [quelle] (an https address). */
        data class Ziel(val name: String, val paket: String, val quelle: String) : Antwort() {
            /** [quelle] as a person types it into Obtainium: no scheme, no trailing slash. */
            val adresse: String get() = quelle.removePrefix("https://").trimEnd('/')
        }
    }

    /** What the start screen and the notice say. */
    enum class Hinweis {
        /** Nothing: this is the server's app, or it names none. */
        KEINER,

        /** Install the new app, pair it, place the widgets; then remove this one. */
        INSTALLIEREN,

        /** The new app is installed: this one can go. */
        LOESCHEN,
    }

    /** The new app's package, declared in the manifest's `<queries>` so this app may see it. */
    const val HOMES = "cloud.dopp.homes"

    /** The name until the server says one. */
    private const val NAME = "Homes"

    private const val PREFS = "wechsel"
    private const val KEY_NAME = "ziel_name"
    private const val KEY_PAKET = "ziel_paket"
    private const val KEY_QUELLE = "ziel_quelle"
    private const val KEY_GEPRUEFT = "geprueft_tag"
    private const val KEY_GEMELDET = "gemeldet_tag"
    private const val TAG_MS = 24L * 60L * 60L * 1000L

    private val laeuft = AtomicBoolean(false)

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The answer to `GET /napi/app` with status [code] and [body]: [Antwort.Keine]
     * on a 404, null when it says nothing (another error, no JSON).
     */
    fun antwort(code: Int, body: String?): Antwort? = when {
        code == 404 -> Antwort.Keine
        code !in 200..299 || body == null -> null
        else -> runCatching { JSONObject(body) }.getOrNull()?.let(::parse)
    }

    /** The reply's app; [Antwort.Keine] without a package or without an https address to get it from. */
    fun parse(o: JSONObject): Antwort {
        val paket = o.optString(AppJson.PAKET).trim()
        val quelle = o.optString(AppJson.QUELLE).trim()
        if (paket.isEmpty() || !quelle.startsWith("https://")) return Antwort.Keine
        return Antwort.Ziel(o.optString(AppJson.NAME).trim().ifEmpty { NAME }, paket, quelle)
    }

    /** What to say, given the server's [antwort], this app's own package and whether a package is installed. */
    fun hinweis(antwort: Antwort?, eigenesPaket: String, installiert: (String) -> Boolean): Hinweis {
        val ziel = antwort as? Antwort.Ziel ?: return Hinweis.KEINER
        if (ziel.paket == eigenesPaket) return Hinweis.KEINER
        return if (installiert(ziel.paket)) Hinweis.LOESCHEN else Hinweis.INSTALLIEREN
    }

    /** The server's last answer that named an app, or null. */
    fun ziel(ctx: Context): Antwort.Ziel? {
        val p = p(ctx)
        val paket = p.getString(KEY_PAKET, null) ?: return null
        val quelle = p.getString(KEY_QUELLE, null) ?: return null
        return Antwort.Ziel(p.getString(KEY_NAME, null) ?: NAME, paket, quelle)
    }

    /** What to say now: the kept answer, against this phone's apps. */
    fun jetzt(ctx: Context): Pair<Hinweis, Antwort.Ziel?> {
        val ziel = ziel(ctx)
        return hinweis(ziel, ctx.packageName) { installiert(ctx, it) } to ziel
    }

    /** Keep [antwort]: an app it names, or none. */
    fun merke(ctx: Context, antwort: Antwort) {
        val e = p(ctx).edit()
        when (antwort) {
            is Antwort.Ziel -> e.putString(KEY_NAME, antwort.name).putString(KEY_PAKET, antwort.paket)
                .putString(KEY_QUELLE, antwort.quelle)
            Antwort.Keine -> e.remove(KEY_NAME).remove(KEY_PAKET).remove(KEY_QUELLE)
        }
        e.apply()
    }

    /** Forget everything (unpairing): the answer was this server's. */
    fun vergiss(ctx: Context) {
        p(ctx).edit().clear().apply()
    }

    fun installiert(ctx: Context, paket: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(paket, 0) }.isSuccess

    /** Ask the server and keep its answer; blocking, off the main thread. False when it could not be asked. */
    fun pruefen(ctx: Context): Boolean {
        val antwort = BoxApiClient(ctx).getApp() ?: return false
        merke(ctx, antwort)
        return true
    }

    /**
     * From the live service, once its stream is open: ask at most once a day
     * (a failed check is asked again at the next screen-on), then post or take
     * back the notice.
     */
    fun taeglich(ctx: Context, jetztMs: Long = System.currentTimeMillis()) {
        val app = ctx.applicationContext
        if (p(app).getLong(KEY_GEPRUEFT, -1L) == tag(jetztMs)) return
        if (!laeuft.compareAndSet(false, true)) return
        thread {
            try {
                if (pruefen(app)) {
                    p(app).edit().putLong(KEY_GEPRUEFT, tag(jetztMs)).apply()
                    WechselNachricht.melden(app, jetztMs)
                }
            } finally {
                laeuft.set(false)
            }
        }
    }

    /** The local calendar day of [ms]. */
    fun tag(ms: Long, zone: TimeZone = TimeZone.getDefault()): Long = (ms + zone.getOffset(ms)) / TAG_MS

    /** Whether the notice may be posted on the day of [jetztMs]: once a day at most. Marks the day when it may. */
    fun meldenHeute(ctx: Context, jetztMs: Long): Boolean {
        val heute = tag(jetztMs)
        if (p(ctx).getLong(KEY_GEMELDET, -1L) == heute) return false
        p(ctx).edit().putLong(KEY_GEMELDET, heute).apply()
        return true
    }
}
