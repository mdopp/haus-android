package cloud.dopp.solaris.data

import android.content.Context
import cloud.dopp.solaris.SolarisConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Read-only client for the **Box** data (services, approvals, upgrades) the
 * paired server re-exposes token-authed under
 * `/napi/box/{home,approvals,services,upgrades}` — or, on servers that only
 * know the legacy name, `/napi/servicebay/…` ([BoxPaths], #174). Shapes mirror
 * ServiceBay's companion `/napi` (servicebay#2252), which Solaris aggregated as
 * a BFF (ADR 0010, solarisbay#811) and Haus now serves. The app never talks to
 * the Box directly — only to its one server.
 *
 * All calls are blocking and must run off the main thread; they return null /
 * empty on any failure (no server, no token, 401, 404-not-deployed, network) so
 * a widget render never throws.
 *
 * Mutations (approve/reject, service start/stop/restart, upgrade-trigger) are NOT
 * here: approve/reject rides the Authelia `/api/` surface (solarisbay#817), and
 * the operate endpoints are ServiceBay's "second slice" (not yet built).
 */
class SbApiClient(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** A Box key: `/napi/box<path>` first, `/napi/servicebay<path>` on a 404 ([BoxPaths]). */
    private fun get(path: String): JSONObject? {
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return null
        return BoxPaths.fetch(base, BoxPaths.memo(ctx)) { prefix -> fetchNapi(prefix + path) }
    }

    /** `GET /napi/persona` (Haus, laib#130) → the house persona, or null when the server has none. */
    fun getPersona(): Persona? = getNapi("/persona")?.let { Persona.parse(it) }

    private fun getNapi(path: String): JSONObject? = fetchNapi(path).value

    /** One token-authed GET under `/napi`; never throws. Status 0 = no answer. */
    private fun fetchNapi(path: String): BoxPaths.Answer<JSONObject> {
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return BoxPaths.Answer(0, null)
        val token = TokenStore.get(ctx) ?: return BoxPaths.Answer(0, null)
        return try {
            val req = Request.Builder()
                .url(base + SolarisConfig.NAPI + path)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return BoxPaths.Answer(resp.code, null)
                val body = runCatching { resp.body?.string()?.let { JSONObject(it) } }.getOrNull()
                BoxPaths.Answer(resp.code, body)
            }
        } catch (e: Exception) {
            BoxPaths.Answer(0, null)
        }
    }

    /** `GET /napi/box/home` → the summary counts, or null on any failure. */
    fun getHome(): SbHome? {
        val o = get("/home") ?: return null
        return SbHome(
            servicesUp = o.optInt("servicesUp", 0),
            servicesFailed = o.optInt("servicesFailed", 0),
            servicesDown = o.optInt("servicesDown", 0),
            pendingApprovals = o.optInt("pendingApprovals", 0),
            pendingUpdates = o.optInt("pendingUpdates", 0),
        )
    }

    /**
     * `GET /napi/box/services` → the service list. Returns null on a request
     * failure (so a widget keeps its last cache instead of blanking), an empty list
     * only when the response genuinely has none.
     */
    fun listServices(): List<SbService>? {
        val root = get("/services") ?: return null
        val arr = root.optJSONArray("services") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                SbService(
                    name = it.optString("name"),
                    activeState = it.optString("activeState"),
                    subState = it.optString("subState"),
                    health = it.optString("health"),
                )
            }
        }
    }

    /**
     * `GET /napi/box/approvals` → the pending-approval feed. Null on a
     * request failure (so the list screen can say "couldn't load" vs "none"),
     * empty only when there genuinely are none.
     */
    fun listApprovals(): List<SbApproval>? {
        val root = get("/approvals") ?: return null
        val arr = root.optJSONArray("approvals") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                SbApproval(
                    id = it.optString("id"),
                    service = it.optString("service"),
                    title = it.optString("title"),
                    description = it.optString("description"),
                    createdAt = it.optString("created_at"),
                    status = it.optString("status"),
                )
            }
        }
    }

    /** `GET /napi/box/upgrades` → pending template/image upgrades (empty on failure). */
    fun listUpgrades(): List<SbUpgrade> {
        val arr = get("/upgrades")?.optJSONArray("upgrades") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                SbUpgrade(
                    name = it.optString("name"),
                    kind = it.optString("kind"),
                    current = it.optString("current"),
                    available = it.optString("available"),
                )
            }
        }
    }
}

/** `/napi/box/home` summary counts (servicebay#2252). */
data class SbHome(
    val servicesUp: Int,
    val servicesFailed: Int,
    val servicesDown: Int,
    val pendingApprovals: Int,
    val pendingUpdates: Int,
)

/** One row of `/napi/box/services`. */
data class SbService(
    val name: String,
    val activeState: String,
    val subState: String,
    val health: String,
)

/** One row of `/napi/box/approvals`. */
data class SbApproval(
    val id: String,
    val service: String,
    val title: String,
    val description: String,
    val createdAt: String,
    val status: String,
)

/** One row of `/napi/box/upgrades`. */
data class SbUpgrade(
    val name: String,
    val kind: String,
    val current: String,
    val available: String,
)
