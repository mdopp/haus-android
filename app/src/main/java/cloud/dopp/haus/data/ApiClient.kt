package cloud.dopp.haus.data

import android.content.Context
import cloud.dopp.haus.data.Napi.ActionJson
import cloud.dopp.haus.data.Napi.ActiveJson
import cloud.dopp.haus.data.Napi.AddableJson
import cloud.dopp.haus.data.Napi.CardJson
import cloud.dopp.haus.data.Napi.EnergyJson
import cloud.dopp.haus.data.Napi.HistoryJson
import cloud.dopp.haus.data.Napi.MerkenJson
import cloud.dopp.haus.data.Napi.StateJson
import cloud.dopp.haus.data.Napi.WhoamiJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The widgets' client for Haus's `/napi`, with the device token (`haus_device_…`)
 * from [TokenStore]. Every call is an endpoint of [Napi]; the request comes from
 * [request], the keys of the reply from Napi's `…Json` objects. All calls are
 * blocking and must run off the main thread (a widget provider's goAsync thread).
 */
class ApiClient(private val ctx: Context) {

    /** No server configured yet — the app must be connected first. */
    class NotConfiguredException : Exception("no server configured")

    /** No device token stored yet — the app must be paired first. */
    class NotPairedException : Exception("no device token")

    /** Haus refused a sensitive action (garage/door open) without confirm. */
    class SensitiveException : Exception("sensitive action needs confirmation")

    /**
     * How `POST /napi/action-callback` answered a tool-row action (#90). The three
     * refusals Haus distinguishes need three different reactions, so they
     * are separate values rather than one boolean:
     *
     * - [CONFIRM_REQUIRED] — destructive action, unconfirmed (403): ask, then
     *   re-send with `confirmed=true`.
     * - [FORBIDDEN] — admin-only action (403). On `/napi/` this is **always** the
     *   answer, because `Remote-Groups` is not trustworthy there — so there is
     *   nothing to retry and no dialog to show.
     * - [UNKNOWN_ACTION] — the id isn't registered (404): leave the row alone.
     */
    enum class ActionOutcome { OK, CONFIRM_REQUIRED, FORBIDDEN, UNKNOWN_ACTION, FAILED }

    /**
     * What Share → Haus came to (haus#120): [Kept] — Haus has the note, [said]
     * is its line, [note] its page on Haus or null, [already] when the link
     * was kept before; [Refused] — Haus's own sentence why not; [NotPaired];
     * [Unreachable] — no answer in time.
     */
    sealed class Merken {
        data class Kept(val said: String, val note: String?, val already: Boolean) : Merken()
        data class Refused(val sentence: String) : Merken()
        object NotPaired : Merken()
        object Unreachable : Merken()
    }

    private fun base(): String =
        ServerStore.baseUrl(ctx)?.trimEnd('/') ?: throw NotConfiguredException()

    private fun token(): String = TokenStore.get(ctx) ?: throw NotPairedException()

    /** [endpoint]'s request to the paired Haus with the device token. */
    private fun authed(endpoint: Napi.Endpoint, target: String = endpoint.path, body: okhttp3.RequestBody? = null): Request =
        endpoint.request(base(), token(), target, body)

    /** One entity's live card via `/napi/portal/state`; null on any refusal. */
    fun getCard(entityId: String): Card? {
        http.newCall(authed(Napi.STATE, Napi.STATE.target(entityId))).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val card = JSONObject(body).optJSONObject(StateJson.CARD) ?: return null
            return parseCardJson(card)
        }
    }

    /**
     * What a device widget can show, for the config picker and the app-icon menu
     * via `/napi/portal/start/addable`: only what Haus draws as a card — Haus
     * offers cameras there too, but a device widget could never render one.
     */
    fun listAddable(): List<Device> {
        http.newCall(authed(Napi.ADDABLE)).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            return DeviceSearch.pickable(parseAddable(JSONObject(body)))
        }
    }

    /**
     * Run a scoped HA service on one entity. `service` is dotted
     * (`light.toggle`, `cover.open_cover`). Throws [SensitiveException] on
     * Haus's 403 confirm gate; returns true on success.
     */
    fun call(
        entityId: String,
        service: String,
        confirmed: Boolean = false,
        data: JSONObject? = null,
    ): Boolean {
        val body = Napi.HA_CALL.json(entityId, service, data, if (confirmed) true else null).asBody()
        http.newCall(authed(Napi.HA_CALL, body = body)).execute().use { resp ->
            if (resp.code == 403) throw SensitiveException()
            return resp.isSuccessful
        }
    }

    /**
     * Register this device's watch set via `POST /napi/portal/watch` ([watchBody]):
     * Haus then streams `card_state` for exactly these entities over
     * `/napi/portal/events`, and `active_set` when the body says `active`.
     *
     * Returns Haus's status (503 while Home Assistant is down, which
     * [cloud.dopp.haus.realtime.RealtimeProtocol.watchRetryMs] retries), or 0
     * when no request went out (no server, no token, no network). Never throws.
     */
    fun postWatch(body: JSONObject): Int {
        return try {
            http.newCall(authed(Napi.WATCH, body = body.asBody())).execute().use { it.code }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Upload one captured file (image or PDF) to `POST /napi/upload` (camera #36)
     * as `multipart/form-data`: the chosen name, then the file.
     *
     * Returns the HTTP status code so the UI can tell "uploaded" (2xx) from
     * "Haus has no upload folder" (404) from other errors; a transport failure
     * (no server/token/network) returns 0. Blocking — call off the main thread.
     */
    fun uploadFile(bytes: ByteArray, filename: String, mime: String): Int {
        return try {
            val (nameField, fileField) = Napi.UPLOAD.body
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(nameField, filename)
                .addFormDataPart(fileField, filename, bytes.toRequestBody(mime.toMediaType()))
                .build()
            http.newCall(authed(Napi.UPLOAD, body = body)).execute().use { resp -> resp.code }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Share → Haus (haus#120): [text] as the phone shared it, to `POST /napi/merken`;
     * Haus finds the link in it and keeps the page as a note. Blocking, up to
     * 90 s (Haus reads the page, then its model sums it up): call off the main
     * thread. Never throws.
     */
    fun merken(text: String): Merken = try {
        val body = Napi.MERKEN.json(text).asBody()
        slow.newCall(authed(Napi.MERKEN, body = body)).execute().use { resp -> merkenOutcome(resp.code, resp.body?.string()) }
    } catch (e: NotPairedException) {
        Merken.NotPaired
    } catch (e: NotConfiguredException) {
        Merken.NotPaired
    } catch (e: Exception) {
        Merken.Unreachable
    }

    /** The house energy picture via `/napi/portal/energy`: the flow legs and the lifetime totals. */
    fun getEnergy(): Energy? {
        http.newCall(authed(Napi.ENERGY)).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return parseEnergy(JSONObject(body))
        }
    }

    /**
     * The household's currently **active** devices (lights on, covers open,
     * switches on, …) for the overview widget (#28) — one GET of
     * `/napi/portal/active`, which answers the already-active entities as cards.
     * Bounded by [cap]. Null when the read failed: that is no news about the
     * house, so the widget keeps the list it last read instead of "Alles aus".
     */
    fun listActive(cap: Int = 40): List<Card>? {
        try {
            http.newCall(authed(Napi.ACTIVE)).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                return parseActive(body).take(cap)
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Parse the `/napi/portal/active` reply, `{ok, active: [card]}`, into
     * [Card]s ordered by domain, then name, for a stable list. A row without an
     * `entity_id` is skipped. Anything else is not Haus's answer and yields nothing.
     */
    internal fun parseActive(body: String): List<Card> {
        val arr = runCatching { JSONObject(body.trim()).optJSONArray(ActiveJson.ACTIVE) }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Card>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString(CardJson.ENTITY_ID).isBlank()) continue
            out.add(parseCardJson(o))
        }
        return out.sortedWith(compareBy({ it.domain }, { it.name }))
    }

    /**
     * The tool catalog via `GET /napi/defs/tool` (#72): every tool Haus offers,
     * with its label, rows path, actions and cell schema. This is what lets a
     * new tool appear as an addable widget without an app rebuild.
     *
     * Returns the raw body so the caller can also persist it as the offline
     * fallback for the picker; parse it with [ToolDefs.parseCatalog]. Null on any
     * error (not paired, offline) — never throws.
     */
    fun toolCatalogBody(): String? = getBody(Napi.TOOL_DEFS, Napi.TOOL_DEFS.path)

    /**
     * The rows behind one tool, from the path its catalog entry names (a
     * [Napi.TOOL_ROWS] path). The generic widget renders these through the
     * tool's `tool-cell-schema` — no per-tool code here. A tool without such a
     * path yields nothing.
     */
    fun fetchToolRows(def: ToolDef): List<JSONObject> {
        val rows = def.apiPath?.let { Napi.TOOL_ROWS.at(it) } ?: return emptyList()
        return ToolDefs.rows(getBody(rows, rows.path))
    }

    /**
     * Run one declared tool action via `POST /napi/action-callback` (#90) with the
     * body [ToolDefs.actionBody] builds — the params already resolved from the
     * rendered row through the catalog's `tool-action-params`, so nothing here
     * knows what a task is.
     *
     * Never throws: a transport failure is [ActionOutcome.FAILED], because a
     * widget button that crashes the provider is worse than one that does nothing.
     */
    fun postActionCallback(
        actionId: String,
        params: JSONObject?,
        confirmed: Boolean = false,
    ): ActionOutcome {
        return try {
            val body = ToolDefs.actionBody(actionId, params, confirmed).asBody()
            http.newCall(authed(Napi.ACTION_CALLBACK, body = body)).execute().use { resp ->
                actionOutcome(resp.code, resp.body?.string())
            }
        } catch (e: Exception) {
            ActionOutcome.FAILED
        }
    }

    /** GET [endpoint] at [target], returning the body, or null on any failure. */
    private fun getBody(endpoint: Napi.Endpoint, target: String): String? {
        return try {
            http.newCall(authed(endpoint, target)).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Recent state history for one entity via `/napi/portal/entity-history`
     * (#755): the numeric series (non-numeric states dropped), for the energy
     * statistics chart. [range] is one Haus keeps.
     */
    fun getEntityHistory(entityId: String, range: Napi.HistoryRange): List<Float> {
        val target = Napi.HISTORY.target(entityId, range.wire)
        http.newCall(authed(Napi.HISTORY, target)).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val arr = JSONObject(body).optJSONArray(HistoryJson.HISTORY) ?: return emptyList()
            val out = ArrayList<Float>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                p.optString(HistoryJson.STATE).toFloatOrNull()?.let { out.add(it) }
            }
            return out
        }
    }

    /** addable: `{ ok, rooms: [ {room, cards:[ {entity_id, name, domain, device_class} ]} ] }`. */
    private fun parseAddable(root: JSONObject): List<Device> {
        val out = mutableListOf<Device>()
        val rooms = root.optJSONArray(AddableJson.ROOMS) ?: return out
        for (i in 0 until rooms.length()) {
            val roomObj = rooms.optJSONObject(i) ?: continue
            val room = roomObj.optString(AddableJson.ROOM).ifBlank { null }
            val cards = roomObj.optJSONArray(AddableJson.CARDS) ?: continue
            for (j in 0 until cards.length()) {
                val c = cards.optJSONObject(j) ?: continue
                val eid = c.optString(AddableJson.ENTITY_ID)
                if (eid.isBlank()) continue
                out.add(
                    Device(
                        entityId = eid,
                        name = c.optString(AddableJson.NAME).ifBlank { eid },
                        domain = c.optString(AddableJson.DOMAIN).ifBlank { eid.substringBefore(".") },
                        deviceClass = c.optString(AddableJson.DEVICE_CLASS).ifBlank { null },
                        room = room,
                    ),
                )
            }
        }
        return out.sortedWith(compareBy({ it.room ?: "￿" }, { it.name }))
    }

    companion object {
        /** One client per process: its connection pool and threads are shared by every call. */
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                // Uploads (camera #36) can be a few MB; give the write side room.
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Share → Haus waits for a page and the house model: a longer read. */
        private val slow: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Ask the Haus at [server] whose [token] this is: `GET /napi/whoami`.
         * The pairing stores a token only when this answers 200 with a `uid`;
         * returns the status (200 only with a uid), or 0 when nothing came back.
         */
        fun whoami(server: String, token: String): Int {
            return try {
                http.newCall(Napi.WHOAMI.request(server, token)).execute().use { resp ->
                    val uid = if (resp.isSuccessful) {
                        runCatching { JSONObject(resp.body?.string().orEmpty()).optString(WhoamiJson.UID) }.getOrNull()
                    } else {
                        null
                    }
                    if (resp.isSuccessful && uid.isNullOrBlank()) 0 else resp.code
                }
            } catch (e: Exception) {
                0
            }
        }

        /**
         * Build a [Card] from Haus's card JSON (the same shape on
         * `/napi/portal/state`, `/napi/portal/active` and the stream's
         * `card_state` frame, #48). Pure (only `org.json`), so the stream and JVM
         * tests can call it without an [ApiClient] instance or the network.
         */
        fun parseCardJson(o: JSONObject): Card = Card(
            entityId = o.optString(CardJson.ENTITY_ID),
            name = o.optString(CardJson.NAME).ifBlank { o.optString(CardJson.ENTITY_ID) },
            domain = o.optString(CardJson.DOMAIN).ifBlank { o.optString(CardJson.ENTITY_ID).substringBefore(".") },
            deviceClass = o.optString(CardJson.DEVICE_CLASS).ifBlank { null },
            state = o.optString(CardJson.STATE).ifBlank { null },
            unit = o.optString(CardJson.UNIT).ifBlank { null },
            brightness = o.optInt(CardJson.BRIGHTNESS, -1).takeIf { it >= 0 },
            position = o.optInt(CardJson.CURRENT_POSITION, -1).takeIf { it in 0..100 },
            temperature = o.optDouble(CardJson.CURRENT_TEMPERATURE, Double.NaN).takeIf { !it.isNaN() },
            // Light colour (#87). Absent keys stay null/empty, so a plain bulb is unchanged.
            rgbColor = packRgb(o.optJSONArray(CardJson.RGB_COLOR)),
            supportedColorModes = stringList(o.optJSONArray(CardJson.SUPPORTED_COLOR_MODES)),
            colorMode = o.optString(CardJson.COLOR_MODE).ifBlank { null },
            colorTemp = o.optInt(CardJson.COLOR_TEMP, -1).takeIf { it > 0 },
            // HA's `last_updated` in epoch-ms, as Haus forwards it — the ordering
            // stamp for the staleness guard. Absent → null → guard inert.
            updatedAtMs = o.optLong(CardJson.UPDATED_AT_MS, -1L).takeIf { it >= 0 },
            // HA's capability bitmask (#92). Absent ⇒ null ⇒ "capability unproven",
            // which the lock chooser reads as "no latch".
            supportedFeatures = o.optInt(CardJson.SUPPORTED_FEATURES, -1).takeIf { it >= 0 },
            page = o.optString(CardJson.PAGE).ifBlank { null },
        )

        /** `{ok, energy: {flow, totals}}` → [Energy], or null when Haus says it has none. */
        internal fun parseEnergy(root: JSONObject): Energy? {
            if (!root.optBoolean(EnergyJson.OK, false)) return null
            val e = root.optJSONObject(EnergyJson.ENERGY) ?: return null
            val legs = mutableListOf<EnergyFlow>()
            e.optJSONArray(EnergyJson.FLOW)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    legs.add(
                        EnergyFlow(
                            label = o.optString(EnergyJson.LABEL),
                            watts = o.optString(EnergyJson.STATE).toDoubleOrNull(),
                            unit = o.optString(EnergyJson.UNIT).ifBlank { "W" },
                            sense = o.optString(EnergyJson.SENSE),
                            entityId = o.optString(EnergyJson.ENTITY_ID).ifBlank { null },
                        ),
                    )
                }
            }
            val totals = mutableListOf<EnergyTotal>()
            e.optJSONArray(EnergyJson.TOTALS)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val entityId = o.optString(EnergyJson.ENTITY_ID)
                    totals.add(
                        EnergyTotal(
                            label = o.optString(EnergyJson.LABEL),
                            value = o.optString(EnergyJson.STATE).toDoubleOrNull(),
                            unit = o.optString(EnergyJson.UNIT).ifBlank { "kWh" },
                            // Haus's totals carry no key of their own: EnergyStyle
                            // classifies a slot from its entity id and label.
                            key = entityId,
                            entityId = entityId.ifBlank { null },
                        ),
                    )
                }
            }
            return Energy(legs, totals)
        }

        /**
         * HA's `rgb_color` (`[r, g, b]`) packed into 0xRRGGBB. Null for a missing,
         * short or out-of-range triple — a half-read colour must not become a
         * wrong one.
         */
        internal fun packRgb(a: JSONArray?): Int? {
            if (a == null || a.length() < 3) return null
            val r = a.optInt(0, -1)
            val g = a.optInt(1, -1)
            val b = a.optInt(2, -1)
            if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
            return (r shl 16) or (g shl 8) or b
        }

        /** A JSON string array (e.g. `supported_color_modes`) as a lower-case list. */
        internal fun stringList(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>(a.length())
            for (i in 0 until a.length()) {
                val s = a.optString(i).trim().lowercase()
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }

        /**
         * The `POST /napi/portal/watch` body, `{"entity_ids":[…]}` (#48), with
         * `"active":true` while this phone shows the active devices (#178).
         */
        fun watchBody(entityIds: List<String>, active: Boolean = false): JSONObject =
            Napi.WATCH.json(JSONArray(entityIds), if (active) true else null)

        /**
         * Classify an `/napi/action-callback` reply (#90). Both refusals Haus can
         * give are **403**, so the status alone is ambiguous — the `reason` in the
         * body is what separates "ask and retry" from "you may never do this". An
         * unreadable body degrades to [ActionOutcome.FAILED] rather than to a
         * confirm dialog for an action we can't name. Pure → JVM-testable.
         */
        fun merkenOutcome(code: Int, body: String?): Merken {
            val o = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            if (code in 200..299 && o != null) {
                val note = o.optString(MerkenJson.NOTE).takeIf { it.startsWith("https://") }
                return Merken.Kept(o.optString(MerkenJson.SAID), note, o.optBoolean(MerkenJson.ALREADY))
            }
            val sentence = o?.optString(MerkenJson.ERROR).orEmpty()
            return when {
                code == 401 -> Merken.NotPaired // Haus's {"error":"unauthorized"} is no sentence for a person
                sentence.isNotBlank() -> Merken.Refused(sentence)
                code == 404 -> Merken.Refused("Diese Box merkt sich noch keine geteilten Links.")
                else -> Merken.Refused("Haus konnte das gerade nicht merken ($code). Später noch einmal teilen.")
            }
        }

        fun actionOutcome(code: Int, body: String?): ActionOutcome {
            if (code in 200..299) return ActionOutcome.OK
            val reason = try {
                if (body.isNullOrBlank()) "" else JSONObject(body).optString(ActionJson.REASON)
            } catch (e: Exception) {
                ""
            }
            return when {
                code == 403 && reason == CONFIRM_REQUIRED -> ActionOutcome.CONFIRM_REQUIRED
                code == 403 -> ActionOutcome.FORBIDDEN
                code == 404 || reason == UNKNOWN_ACTION -> ActionOutcome.UNKNOWN_ACTION
                else -> ActionOutcome.FAILED
            }
        }

        /** The refusal reasons of `/napi/action-callback` the app tells apart. */
        private const val CONFIRM_REQUIRED = "confirm_required"
        private const val UNKNOWN_ACTION = "unknown_action"
    }
}
