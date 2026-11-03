package cloud.dopp.haus.data

import android.content.Context
import cloud.dopp.haus.wechsel.Wechsel
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Read-only client for the assistant's name (`/napi/assistent`) and the app
 * the server speaks to (`/napi/app`, laib#1021). The Box
 * widgets are gone (owner, 2026-11-03), and with them the Box keys.
 *
 * Blocking, off the main thread; null on any failure (no server, no token,
 * 401, network). Nothing here changes anything.
 */
class BoxApiClient(private val ctx: Context) {

    /** One token-authed GET of [endpoint], as JSON; never throws. */
    private fun get(endpoint: Napi.Endpoint): JSONObject? = reply(endpoint)?.body?.let(::json)

    private fun json(body: ByteArray): JSONObject? = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()

    /** What came back: the status, and the body of a success. */
    private class Reply(val code: Int, val body: ByteArray?)

    /** [endpoint]'s answer, its body at most [cap] bytes; null when there was none (no pairing, screen off, network). */
    private fun reply(endpoint: Napi.Endpoint, cap: Long = MAX_REPLY_BYTES): Reply? {
        if (!ScreenGate.open(ctx)) return null
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return null
        val token = TokenStore.get(ctx) ?: return null
        return try {
            http.newCall(endpoint.request(base, token)).execute().use { resp ->
                if (!resp.isSuccessful) return Reply(resp.code, null)
                val src = resp.body?.source() ?: return null
                // more than the cap → refuse it whole rather than cut it
                if (src.request(cap + 1)) return null
                Reply(resp.code, src.readByteArray())
            }
        } catch (e: Exception) {
            null
        }
    }

    /** `GET /napi/assistent` → the assistant's name, or null. */
    fun getAssistent(): String? = get(Napi.ASSISTENT)?.let { Assistent.parse(it) }

    /**
     * `GET /napi/app` → the app the server names ([Wechsel.Antwort.Ziel]),
     * [Wechsel.Antwort.Keine] when it names none (a 404 from a server before
     * laib#1021, or a reply without a package), or null when it could not be asked.
     */
    fun getApp(): Wechsel.Antwort? {
        val r = reply(Napi.APP) ?: return null
        return Wechsel.antwort(r.code, r.body?.toString(Charsets.UTF_8))
    }

    private companion object {
        /** No reply here is larger; a bigger one is refused, never half read. */
        const val MAX_REPLY_BYTES = 1L shl 20

        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
