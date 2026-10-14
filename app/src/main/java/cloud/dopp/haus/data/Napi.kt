package cloud.dopp.haus.data

import java.net.URLEncoder

/**
 * Every call the app makes to Haus, as data (laib ADR 0022: Haus's `/napi` is
 * this app's API). An [Endpoint] names its method, its path, the query
 * parameters and body fields it sends — in the order the client passes their
 * values — and the reply shapes whose keys the client reads. The client never
 * spells any of these itself: `NapiRequest.kt` turns an endpoint into a
 * request, and the parsers read keys through the `…Json` objects below.
 *
 * `NapiContractTest` holds all of it to Haus's handlers, file and line.
 */
object Napi {

    /** Haus reads the device token from this header, after this scheme (`bearerOf`). */
    const val AUTH_HEADER = "Authorization"
    const val AUTH_SCHEME = "Bearer"

    /** The header value that carries [token] (`haus_device_…`). */
    fun bearer(token: String): String = "$AUTH_SCHEME $token"

    /** The root every path shares. A tool's rows path must sit under it, see [TOOL_ROWS]. */
    const val ROOT = "/napi/"

    enum class Method { GET, POST }

    /**
     * One call. [query] and [body] are the names the client sends, in the order
     * it passes the values; [allowed] closes a parameter to the values Haus
     * takes; [reply] lists the shapes whose keys the client reads.
     */
    class Endpoint(
        val method: Method,
        val path: String,
        val query: List<String> = emptyList(),
        val allowed: Map<String, List<String>> = emptyMap(),
        val body: List<String> = emptyList(),
        val reply: List<Any> = emptyList(),
        val accept: String? = null,
        val event: String? = null,
    ) {
        /**
         * [path] with [values] for [query], in order; a null value leaves its
         * parameter out. A value outside [allowed] is refused here, before Haus
         * would refuse it.
         */
        fun target(vararg values: String?): String {
            require(values.size <= query.size) { "$path takes ${query.size} query values" }
            val q = query.zip(values.toList()).mapNotNull { (name, value) ->
                if (value == null) return@mapNotNull null
                allowed[name]?.let { require(value in it) { "$name=$value: Haus takes only $it" } }
                name + "=" + URLEncoder.encode(value, "UTF-8")
            }
            return if (q.isEmpty()) path else path + "?" + q.joinToString("&")
        }

        /** The body: [body]'s names with [values], in order; a null value leaves its field out. */
        fun fields(vararg values: Any?): Map<String, Any> {
            require(values.size == body.size) { "$path takes ${body.size} body fields" }
            val out = LinkedHashMap<String, Any>()
            body.zip(values.toList()).forEach { (name, value) -> if (value != null) out[name] = value }
            return out
        }

        /** This endpoint at [concrete], one of its paths with every `{…}` filled; null when it is not one. */
        fun at(concrete: String): Endpoint? {
            val want = path.split('/')
            val have = concrete.substringBefore('?').split('/')
            val fits = want.size == have.size && want.indices.all { i ->
                want[i] == have[i] || (want[i].startsWith("{") && have[i].isNotEmpty())
            }
            return if (fits) Endpoint(method, concrete, query, allowed, body, reply, accept, event) else null
        }

        override fun toString() = "$method $path"
    }

    /** The history windows the app shows, as Haus names them (`historyRanges`). */
    enum class HistoryRange(val wire: String) { DAY("24h"), WEEK("7d") }

    /** The domains Haus draws as a card: only these can be a device widget. */
    val CARD_DOMAINS: Set<String> = setOf(
        "sensor", "binary_sensor", "cover", "light", "switch", "climate", "media_player", "lock",
    )

    // --- the replies, by the keys the app reads ---------------------------------

    /** `GET /napi/persona`. */
    object PersonaJson {
        const val DISPLAY = "display"
        const val GREETING = "greeting"
        const val ACCENT = "accent"
        const val GROUND = "ground"
        const val ICON = "icon"
    }

    /** `GET /napi/whoami`: who owns the device token. */
    object WhoamiJson {
        const val UID = "uid"
    }

    /** `GET /napi/box/home`. */
    object BoxHomeJson {
        const val SERVICES_UP = "servicesUp"
        const val SERVICES_FAILED = "servicesFailed"
        const val SERVICES_DOWN = "servicesDown"
        const val PENDING_APPROVALS = "pendingApprovals"
        const val PENDING_UPDATES = "pendingUpdates"
    }

    /** `GET /napi/box/services`: `{services: [row]}`. */
    object BoxServicesJson {
        const val SERVICES = "services"
        const val NAME = "name"
        const val ACTIVE_STATE = "activeState"
        const val SUB_STATE = "subState"
        const val HEALTH = "health"
    }

    /** One entity as a card: the state path, the active list and the stream's frames carry it. */
    object CardJson {
        const val ENTITY_ID = "entity_id"
        const val NAME = "name"
        const val DOMAIN = "domain"
        const val DEVICE_CLASS = "device_class"
        const val STATE = "state"
        const val UNIT = "unit"
        const val BRIGHTNESS = "brightness"
        const val CURRENT_POSITION = "current_position"
        const val CURRENT_TEMPERATURE = "current_temperature"
        const val RGB_COLOR = "rgb_color"
        const val SUPPORTED_COLOR_MODES = "supported_color_modes"
        const val COLOR_MODE = "color_mode"
        const val COLOR_TEMP = "color_temp"
        const val UPDATED_AT_MS = "updated_at_ms"
        const val SUPPORTED_FEATURES = "supported_features"
    }

    /** `GET /napi/portal/state`: `{ok, card}`. */
    object StateJson {
        const val CARD = "card"
    }

    /** `GET /napi/portal/active`: `{ok, active: [card]}`. */
    object ActiveJson {
        const val ACTIVE = "active"
    }

    /** `GET /napi/portal/energy`: `{ok, energy: {flow: [meter], totals: [meter]}}`. */
    object EnergyJson {
        const val OK = "ok"
        const val ENERGY = "energy"
        const val FLOW = "flow"
        const val TOTALS = "totals"
        const val LABEL = "label"
        const val STATE = "state"
        const val UNIT = "unit"
        const val SENSE = "sense"
        const val ENTITY_ID = "entity_id"
    }

    /** `GET /napi/portal/entity-history`: `{ok, history: [{t, state}]}`. */
    object HistoryJson {
        const val HISTORY = "history"
        const val STATE = "state"
    }

    /** `GET /napi/portal/start/addable`: `{ok, rooms: [{room, cards: [device]}]}`. */
    object AddableJson {
        const val ROOMS = "rooms"
        const val ROOM = "room"
        const val CARDS = "cards"
        const val ENTITY_ID = "entity_id"
        const val NAME = "name"
        const val DOMAIN = "domain"
        const val DEVICE_CLASS = "device_class"
    }

    /** A `card_state` frame of the stream: `{entity_id, card}`. */
    object EventJson {
        const val ENTITY_ID = "entity_id"
        const val CARD = "card"
    }

    /** `GET /napi/defs/tool`: `{ok, defs: [def]}`. */
    object ToolDefJson {
        const val DEFS = "defs"
        const val TOOL_ID = "tool-id"
        const val TOOL_LABEL = "tool-label"
        const val TOOL_API_PATH = "tool-api-path"
        const val TOOL_CELL_SCHEMA = "tool-cell-schema"
        const val TOOL_ACTIONS = "tool-actions"
        const val TOOL_ACTIONS_TITLED = "tool-actions-titled"
        const val TOOL_ACTION_PARAMS = "tool-action-params"

        /** An entry of `tool-actions-titled`. */
        const val ID = "id"
        const val TITLE = "title"
    }

    /**
     * The roles a `tool-cell-schema` may name (ADR 0011): a closed vocabulary the
     * app reads, wider than the roles Haus's catalog uses today.
     */
    object CellSchemaJson {
        const val TITLE = "title"
        const val SUBTITLE = "subtitle"
        const val META = "meta"
        const val BADGE = "badge"
        const val STATE = "state"
        const val ICON = "icon"
        const val ACTIONS = "actions"
    }

    /** A refused `POST /napi/action-callback`: `{ok: false, reason}`. */
    object ActionJson {
        const val REASON = "reason"
    }

    // --- the calls -------------------------------------------------------------

    /** The house persona: name, greeting, colours, whether it has an icon. */
    val PERSONA = Endpoint(Method.GET, "/napi/persona", reply = listOf(PersonaJson))

    /** The persona's icon, as SVG text. */
    val PERSONA_ICON = Endpoint(Method.GET, "/napi/persona/icon.svg")

    /** Who owns a device token: the pairing checks a new token here before storing it. */
    val WHOAMI = Endpoint(Method.GET, "/napi/whoami", reply = listOf(WhoamiJson))

    /** The Box keys, for the admin widgets. */
    val BOX_HOME = Endpoint(Method.GET, "/napi/box/home", reply = listOf(BoxHomeJson))
    val BOX_SERVICES = Endpoint(Method.GET, "/napi/box/services", reply = listOf(BoxServicesJson))

    /** Switch one entity; a 403 asks for `confirmed`. */
    val HA_CALL = Endpoint(Method.POST, "/napi/ha/call", body = listOf("entity_id", "service", "data", "confirmed"))

    /** One entity's card. */
    val STATE = Endpoint(Method.GET, "/napi/portal/state", query = listOf("entity_id"), reply = listOf(StateJson, CardJson))

    val ENERGY = Endpoint(Method.GET, "/napi/portal/energy", reply = listOf(EnergyJson))

    val HISTORY = Endpoint(
        Method.GET, "/napi/portal/entity-history",
        query = listOf("entity_id", "range"),
        allowed = mapOf("range" to HistoryRange.entries.map { it.wire }),
        reply = listOf(HistoryJson),
    )

    val ACTIVE = Endpoint(Method.GET, "/napi/portal/active", reply = listOf(ActiveJson, CardJson))

    /** What the device picker and the app-icon menu offer. */
    val ADDABLE = Endpoint(Method.GET, "/napi/portal/start/addable", reply = listOf(AddableJson))

    /** Which entities this device's widgets show; 503 while Home Assistant is down. */
    val WATCH = Endpoint(Method.POST, "/napi/portal/watch", body = listOf("entity_ids"))

    /** The live stream, only `card_state` frames. */
    val EVENTS = Endpoint(
        Method.GET, "/napi/portal/events",
        reply = listOf(EventJson, CardJson), accept = "text/event-stream", event = "card_state",
    )

    /** The tool catalog. */
    val TOOL_DEFS = Endpoint(Method.GET, "/napi/defs/tool", reply = listOf(ToolDefJson, CellSchemaJson))

    /** A tool's rows, at the path its catalog entry names ([Endpoint.at]). */
    val TOOL_ROWS = Endpoint(Method.GET, "/napi/portal/{tool}")

    /** A tool row's action. */
    val ACTION_CALLBACK = Endpoint(
        Method.POST, "/napi/action-callback",
        body = listOf("action_id", "params", "confirmed"), reply = listOf(ActionJson),
    )

    /** The camera capture into the share's consume folder, multipart: the name, then the file. */
    val UPLOAD = Endpoint(Method.POST, "/napi/upload", body = listOf("filename", "file"))
}
