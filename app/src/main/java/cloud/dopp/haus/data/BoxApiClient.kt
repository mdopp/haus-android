package cloud.dopp.haus.data

import android.content.Context
import cloud.dopp.haus.data.Napi.BoxHomeJson
import cloud.dopp.haus.data.Napi.BoxServicesJson
import okhttp3.OkHttpClient
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

    /** One token-authed GET of [endpoint], as JSON; never throws. */
    private fun get(endpoint: Napi.Endpoint): JSONObject? =
        bytes(endpoint)?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }

    /** The bytes [endpoint] answers, at most [cap] of them, or null; never throws. */
    private fun bytes(endpoint: Napi.Endpoint, cap: Long = MAX_REPLY_BYTES): ByteArray? {
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return null
        val token = TokenStore.get(ctx) ?: return null
        return try {
            http.newCall(endpoint.request(base, token)).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val src = resp.body?.source() ?: return null
                // more than the cap → refuse it whole rather than cut it
                if (src.request(cap + 1)) return null
                src.readByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** `GET /napi/persona` → the house persona, or null. */
    fun getPersona(): Persona? = get(Napi.PERSONA)?.let { Persona.parse(it) }

    /**
     * `GET /napi/persona/icon.svg` → the persona's icon as SVG text, or null
     * when it has none, it is not SVG, or it is larger than [PersonaIcon.MAX_BYTES].
     */
    fun getPersonaIcon(): String? =
        bytes(Napi.PERSONA_ICON, PersonaIcon.MAX_BYTES.toLong())?.toString(Charsets.UTF_8)?.let(PersonaIcon::accept)

    /** `GET /napi/box/home` → the summary counts, or null on any failure. */
    fun getHome(): BoxHome? {
        val o = get(Napi.BOX_HOME) ?: return null
        return BoxHome(
            servicesUp = o.optInt(BoxHomeJson.SERVICES_UP, 0),
            servicesFailed = o.optInt(BoxHomeJson.SERVICES_FAILED, 0),
            servicesDown = o.optInt(BoxHomeJson.SERVICES_DOWN, 0),
            pendingApprovals = o.optInt(BoxHomeJson.PENDING_APPROVALS, 0),
            pendingUpdates = o.optInt(BoxHomeJson.PENDING_UPDATES, 0),
        )
    }

    /**
     * `GET /napi/box/services` → the service list. Null on a request failure (so a
     * widget keeps its last cache instead of blanking), empty only when the
     * answer genuinely has none.
     */
    fun listServices(): List<BoxService>? {
        val root = get(Napi.BOX_SERVICES) ?: return null
        val arr = root.optJSONArray(BoxServicesJson.SERVICES) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                BoxService(
                    name = it.optString(BoxServicesJson.NAME),
                    activeState = it.optString(BoxServicesJson.ACTIVE_STATE),
                    subState = it.optString(BoxServicesJson.SUB_STATE),
                    health = it.optString(BoxServicesJson.HEALTH),
                )
            }
        }
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
