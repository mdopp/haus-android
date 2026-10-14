package cloud.dopp.haus.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Read-only client for Haus's Box keys (`/napi/box/home`, `/napi/box/services`)
 * that the admin widgets show, and for the house persona (`/napi/persona`).
 * Haus serves them from Laib; the app never talks to the Box directly.
 *
 * All calls are blocking and must run off the main thread; they return null on
 * any failure (no server, no token, 401, network) so a widget render never throws.
 * Nothing here changes anything: approving and restarting happen on Laib's page.
 */
class BoxApiClient(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** One token-authed GET of a whole `/napi/…` [path]; never throws. */
    private fun get(path: String): JSONObject? {
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return null
        val token = TokenStore.get(ctx) ?: return null
        return try {
            val req = Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The bytes of a whole `/napi/…` [path], or null; never throws. */
    private fun bytes(path: String): ByteArray? {
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return null
        val token = TokenStore.get(ctx) ?: return null
        return try {
            val req = Request.Builder().url(base + path).header("Authorization", "Bearer $token").get().build()
            http.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.bytes() else null }
        } catch (e: Exception) {
            null
        }
    }

    /** `GET /napi/persona` → the house persona, or null. */
    fun getPersona(): Persona? = get(Napi.PERSONA)?.let { Persona.parse(it) }

    /** `GET /napi/persona/icon.svg` → the persona's icon as SVG text, or null when it has none. */
    fun getPersonaIcon(): String? = bytes(Napi.PERSONA_ICON)?.toString(Charsets.UTF_8)?.takeIf { it.contains("<svg") }

    /** `GET /napi/box/home` → the summary counts, or null on any failure. */
    fun getHome(): BoxHome? {
        val o = get(Napi.BOX_HOME) ?: return null
        return BoxHome(
            servicesUp = o.optInt("servicesUp", 0),
            servicesFailed = o.optInt("servicesFailed", 0),
            servicesDown = o.optInt("servicesDown", 0),
            pendingApprovals = o.optInt("pendingApprovals", 0),
            pendingUpdates = o.optInt("pendingUpdates", 0),
        )
    }

    /**
     * `GET /napi/box/services` → the service list. Null on a request failure (so a
     * widget keeps its last cache instead of blanking), empty only when the
     * answer genuinely has none.
     */
    fun listServices(): List<BoxService>? {
        val root = get(Napi.BOX_SERVICES) ?: return null
        val arr = root.optJSONArray("services") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                BoxService(
                    name = it.optString("name"),
                    activeState = it.optString("activeState"),
                    subState = it.optString("subState"),
                    health = it.optString("health"),
                )
            }
        }
    }
}

/** `/napi/box/home` summary counts. */
data class BoxHome(
    val servicesUp: Int,
    val servicesFailed: Int,
    val servicesDown: Int,
    val pendingApprovals: Int,
    val pendingUpdates: Int,
)

/** One row of `/napi/box/services`. */
data class BoxService(
    val name: String,
    val activeState: String,
    val subState: String,
    val health: String,
)
