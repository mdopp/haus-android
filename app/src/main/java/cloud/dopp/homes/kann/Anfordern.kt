package cloud.dopp.homes.kann

import android.content.Context
import android.os.Handler
import android.os.Looper
import cloud.dopp.homes.data.KannClient
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Where a trace's „Hier anfordern“ stands (homes ADR 0001 §10). */
sealed class Anfrage {
    /** Not pressed, or done: the trace shows the module's state. */
    object Bereit : Anfrage()

    /** Pressed: „Wird angefordert …“ until Homes answers. */
    object Laeuft : Anfrage()

    /** Homes took the wish; the kept answer has the module's new state. */
    object Fertig : Anfrage()

    /** Homes refused, in its sentence [satz] when it sent one; [code] 401: the phone is not paired any more. */
    data class Abgelehnt(val code: Int, val satz: String?) : Anfrage()

    /** No answer in time: Homes may have filed it all the same. */
    object Zeit : Anfrage()

    /** No connection to Homes. */
    object Offline : Anfrage()
}

/**
 * „Hier anfordern“ (homes ADR 0001 §10): `POST /napi/kann/anfordern` for one
 * off module. It answers at once ([Anfrage.Laeuft]) and ends visibly: the
 * module's new state in the kept answer, or why not. One request per module
 * at a time; what a trace shows is [stand].
 */
object Anfordern {

    private val staende = ConcurrentHashMap<String, Anfrage>()

    /** Where the request for [modul] stands; [Anfrage.Bereit] when none was made. */
    fun stand(modul: String): Anfrage = staende[modul] ?: Anfrage.Bereit

    /** Press „Hier anfordern“ for [modul]; [danach] runs on the main thread once it ended. */
    fun los(ctx: Context, modul: String, danach: () -> Unit) {
        val app = ctx.applicationContext
        if (staende.put(modul, Anfrage.Laeuft) == Anfrage.Laeuft) return
        thread {
            val client = ServerStore.baseUrl(app)?.let { base -> TokenStore.get(app)?.let { KannClient(base, it) } }
            staende[modul] = if (client == null) Anfrage.Abgelehnt(UNAUTHORIZED, null) else einmal(app, client, modul)
            Handler(Looper.getMainLooper()).post(danach)
        }
    }

    /** One request, blocking: how it ended; a taken wish is kept in [KannStore] first. */
    fun einmal(ctx: Context, client: KannClient, modul: String): Anfrage {
        val w = client.anfordern(modul)
        if (w is KannClient.Wunsch.Angenommen) KannStore.ersetzen(ctx, w.eintrag)
        return ende(w)
    }

    /** What a wish's outcome means for the trace. */
    fun ende(w: KannClient.Wunsch): Anfrage = when (w) {
        is KannClient.Wunsch.Angenommen -> Anfrage.Fertig
        is KannClient.Wunsch.Abgelehnt -> Anfrage.Abgelehnt(w.code, w.satz)
        KannClient.Wunsch.Zeit -> Anfrage.Zeit
        KannClient.Wunsch.Offline -> Anfrage.Offline
    }

    /** Forget every request's end (unpaired). */
    fun clear() = staende.clear()

    const val UNAUTHORIZED = 401
}
