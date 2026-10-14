package cloud.dopp.haus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The app calls Haus the way Haus answers (#176, laib ADR 0022: Haus's /napi
 * is the app's API).
 *
 * `data/Napi.kt` declares every call as data — method, path, the query
 * parameters and body fields it sends, the reply keys it reads — and the
 * client builds its requests from that data alone. This test holds the data
 * to [HAUS], a copy of what Haus's handlers read and send, each row naming the
 * Haus file and lines it was copied from (mdopp/haus at 8c1251e). When Haus
 * changes a handler, the row changes with it, in the same breath.
 *
 * The declarations are read by reflection: every static field of `Napi` is
 * looked at, so a path declared outside an endpoint is caught too. The
 * sources are read as text for what reflection cannot see: that no other file
 * spells a path, a key or the auth header by hand, and which pages the app
 * opens in the browser.
 */
class NapiContractTest {

    /** What one Haus handler reads and sends. [query] maps a parameter to its closed set of values, or null for any. */
    class Route(
        val method: String,
        val path: String,
        val at: String,
        val query: Map<String, Set<String>?> = emptyMap(),
        val body: Set<String> = emptySet(),
        val required: Set<String> = emptySet(),
        val sends: Set<String> = emptySet(),
        val accept: String? = null,
        val events: List<String> = emptyList(),
    ) {
        override fun toString() = "$method $path ($at)"
    }

    companion object {
        /** A card: internal/napi/home.go:164-193, its control attributes home.go:43-45, its page :177. */
        val CARD = setOf(
            "entity_id", "name", "domain", "state", "page", "device_class", "unit", "updated_at_ms",
            "supported_features", "brightness", "color_temp", "rgb_color", "color_mode", "supported_color_modes",
            "current_position", "temperature", "current_temperature", "hvac_action", "target_temp_step", "min_temp",
            "max_temp", "hvac_modes", "volume_level", "media_title", "media_artist",
        )

        /** One energy meter: internal/napi/energy.go:87-100, labelled at :145, :155, :183, :192. */
        val METER = setOf("entity_id", "name", "state", "device_class", "unit", "label", "sense")

        /** Every /napi route Haus serves, as its handler reads and answers it. */
        val HAUS = listOf(
            Route(
                "GET", "/napi/persona", "internal/napi/napi.go:63-74",
                query = mapOf("lang" to null),
                sends = setOf("name", "display", "greeting", "accent", "ground", "icon"),
            ),
            Route("GET", "/napi/persona/icon.png", "internal/napi/napi.go:75-82"),
            Route("GET", "/napi/whoami", "internal/napi/napi.go:181-190", sends = setOf("uid", "via")),
            Route(
                "GET", "/napi/box/home",
                "internal/napi/napi.go:85-108 (prefix: internal/app/app.go:17; updates apart: napi.go:105, 224-235)",
                sends = setOf(
                    "servicesUp", "servicesFailed", "servicesDown", "pendingApprovals", "pendingUpdates",
                    "state", "drift", "lastHeard", "operator",
                ),
            ),
            Route(
                "GET", "/napi/box/services", "internal/napi/napi.go:109-127",
                sends = setOf("services", "name", "activeState", "subState", "health", "detail"),
            ),
            Route(
                "GET", "/napi/box/approvals", "internal/napi/napi.go:128-144, the row napi.go:238-244",
                sends = setOf("approvals", "id", "service", "title", "description", "created_at", "status"),
            ),
            Route(
                "GET", "/napi/box/upgrades", "internal/napi/napi.go:145-156, the row napi.go:238-244",
                sends = setOf("upgrades", "id", "service", "title", "description", "created_at"),
            ),
            Route(
                "POST", "/napi/box/services/{name}/operate", "internal/napi/napi.go:157-172",
                body = setOf("action"), required = setOf("action"), sends = setOf("ok", "name", "action"),
            ),
            Route("POST", "/napi/box/approvals/{id}/approve", "internal/napi/napi.go:176-179"),
            Route("POST", "/napi/box/approvals/{id}/deny", "internal/napi/napi.go:176-180"),
            Route(
                "POST", "/napi/ha/call", "internal/napi/home.go:84-140",
                body = setOf("entity_id", "service", "data", "confirmed"),
                required = setOf("entity_id", "service"), sends = setOf("ok", "error"),
            ),
            Route(
                "GET", "/napi/portal/state", "internal/napi/home.go:195-217",
                query = mapOf("entity_id" to null), required = setOf("entity_id"),
                sends = setOf("ok", "error", "card") + CARD,
            ),
            Route(
                "GET", "/napi/portal/energy", "internal/napi/energy.go:107-130",
                sends = setOf("ok", "error", "energy", "flow", "totals", "circuits") + METER,
            ),
            Route(
                "GET", "/napi/portal/entity-history", "internal/napi/home.go:219-252, ranges home.go:47",
                query = mapOf("entity_id" to null, "range" to setOf("24h", "48h", "7d")),
                required = setOf("entity_id"), sends = setOf("ok", "error", "history", "t", "state"),
            ),
            Route(
                "GET", "/napi/portal/active", "internal/napi/home.go:254-291, the same set as active_set: live.go:324-350",
                sends = setOf("ok", "error", "active", "room") + CARD,
            ),
            Route("GET", "/napi/portal/camera/{entity_id}/snapshot", "internal/napi/camera.go:27-53"),
            Route(
                "GET", "/napi/portal/start/addable", "internal/napi/camera.go:54-104",
                sends = setOf(
                    "ok", "error", "rooms", "room", "cards", "entity_id", "name", "domain", "state", "device_class",
                    "automations",
                ),
            ),
            Route(
                "POST", "/napi/portal/watch", "internal/napi/live.go:106-145",
                body = setOf("entity_ids", "active"), required = setOf("entity_ids"), sends = setOf("ok", "error"),
            ),
            Route(
                "GET", "/napi/portal/events",
                "internal/napi/live.go:147-180, card_state live.go:432-434, active_set live.go:376-397",
                sends = setOf("entity_id", "card", "entity_ids") + CARD, accept = "text/event-stream",
                events = listOf("card_state", "active_set"),
            ),
            Route(
                "GET", "/napi/defs/tool", "internal/napi/tools.go:77-102",
                sends = setOf(
                    "ok", "kind", "defs", "tool-id", "tool-label", "tool-api-path", "tool-cell-schema", "tool-actions",
                    "tool-actions-titled", "tool-action-params", "id", "title",
                ),
            ),
            Route(
                "POST", "/napi/action-callback",
                "internal/napi/tools.go:276-336 (confirmed: named at :276, read by no action Haus has)",
                body = setOf("action_id", "params", "confirmed"), required = setOf("action_id"),
                sends = setOf("ok", "reason"),
            ),
            Route(
                "GET", "/napi/portal/tasks", "internal/napi/tools.go:55, 121-177, the page :165",
                query = mapOf("done" to setOf("1")),
                sends = setOf("ok", "error", "tasks", "id", "page", "title", "status", "due"),
            ),
            Route(
                "GET", "/napi/portal/contacts", "internal/napi/tools.go:58, 179-218, the page :210",
                sends = setOf("ok", "error", "contacts", "id", "page", "name", "phone", "email", "birthday"),
            ),
            Route(
                "GET", "/napi/portal/documents", "internal/napi/tools.go:61, 220-249, the page :244",
                sends = setOf("ok", "error", "documents", "id", "title", "created", "correspondent", "link", "page"),
            ),
            Route(
                "GET", "/napi/portal/notes", "internal/napi/tools.go:64, 251-265",
                sends = setOf("ok", "error", "notes", "id", "page", "title", "changed"),
            ),
            Route(
                "POST", "/napi/upload", "internal/napi/upload.go:144-145, the parts upload.go:214-232",
                body = setOf("filename", "file"), required = setOf("file"), sends = setOf("ok", "error", "id", "files"),
            ),
        )

        /** Where Haus reads the token: `bearerOf`, internal/napi/napi.go:208-211. */
        const val HAUS_AUTH_HEADER = "Authorization"
        const val HAUS_AUTH_SCHEME = "Bearer"

        /** The domains Haus draws as a card: `cards.Domains`, internal/cards/pages.go:21-25. */
        val HAUS_CARD_DOMAINS = setOf("sensor", "binary_sensor", "cover", "light", "switch", "climate", "media_player", "lock")

        /** The rows paths Haus's tool catalog names: internal/napi/tools.go:81-93. */
        val HAUS_TOOL_PATHS = setOf("/napi/portal/contacts", "/napi/portal/documents", "/napi/portal/notes", "/napi/portal/tasks")

        /** The cell roles Haus's tool catalog uses: internal/napi/tools.go:82-94. */
        val HAUS_CELL_ROLES = setOf("title", "meta", "actions")

        /**
         * Haus's pages (cmd/haus/main.go:61-75). Not "/" and not "/healthz": "/"
         * and every path Haus does not serve land on Start (main.go:77), so a
         * wrong page would never fail loudly — only this list catches it.
         */
        val PAGES = setOf("/start", "/raeume", "/kalender", "/notizen", "/box", "/box/geraete", "/pair-device")

        /** What the widgets and the pairing need: each must be declared. */
        val NEEDED = setOf(
            "GET /napi/persona", "GET /napi/persona/icon.png", "GET /napi/whoami",
            "GET /napi/box/home", "GET /napi/box/services",
            "POST /napi/ha/call", "GET /napi/portal/state", "GET /napi/portal/energy",
            "GET /napi/portal/entity-history", "GET /napi/portal/active", "GET /napi/portal/start/addable",
            "POST /napi/portal/watch", "GET /napi/portal/events",
            "GET /napi/defs/tool", "POST /napi/action-callback", "POST /napi/upload", "GET /napi/portal/{tool}",
        )

        const val NAPI = "cloud.dopp.haus.data.Napi"
        const val NAPI_FILE = "app/src/main/java/cloud/dopp/haus/data/Napi.kt"

        /** The one file that turns an endpoint into an HTTP request. */
        const val REQUEST_FILE = "app/src/main/java/cloud/dopp/haus/data/NapiRequest.kt"

        /** The files that read Haus's replies: their keys come from Napi, never spelled here. */
        val PARSERS = listOf(
            "app/src/main/java/cloud/dopp/haus/data/ApiClient.kt",
            "app/src/main/java/cloud/dopp/haus/data/BoxApiClient.kt",
            "app/src/main/java/cloud/dopp/haus/data/Persona.kt",
            "app/src/main/java/cloud/dopp/haus/data/ToolDefs.kt",
            "app/src/main/java/cloud/dopp/haus/realtime/RealtimeProtocol.kt",
        )

        private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")

        /** Two paths are the same route: equal segments, a `{…}` on either side matches one segment. */
        fun same(a: String, b: String): Boolean {
            val x = a.substringBefore('?').trimEnd('/').split('/')
            val y = b.substringBefore('?').trimEnd('/').split('/')
            return x.size == y.size && x.indices.all { i ->
                x[i] == y[i] || (x[i].startsWith("{") && y[i].isNotEmpty()) || (y[i].startsWith("{") && x[i].isNotEmpty())
            }
        }

        /** A line without its comment: KDoc and `//` lines quote JSON for the reader, not the wire. */
        fun code(line: String): String {
            val t = line.trim()
            if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) return ""
            val i = line.indexOf("// ")
            return if (i >= 0) line.substring(0, i) else line
        }

        /** Every string literal in the main sources' code, with where it stands. */
        fun literals(files: List<File> = RepoFiles.mainKotlin()): List<Pair<String, String>> = files.flatMap { f ->
            f.readLines().flatMapIndexed { i, line ->
                literal.findAll(code(line)).map { "${RepoFiles.relative(f)}:${i + 1}" to it.groupValues[1] }.toList()
            }
        }

        fun file(rel: String) = File(RepoFiles.root, rel)
    }

    // --- Napi's declarations, read by reflection --------------------------------

    /** One declared call, as plain values. */
    class Declared(
        val name: String,
        val method: String,
        val path: String,
        val query: List<String>,
        val allowed: Map<String, List<String>>,
        val body: List<String>,
        val reads: Set<String>,
        val accept: String?,
        val events: List<String>,
    ) {
        override fun toString() = "$name ($method $path)"
    }

    private val napi: Class<*> by lazy { Class.forName(NAPI) }

    /** Every static field of [c] but its INSTANCE, by name. */
    private fun statics(c: Class<*> = napi): Map<String, Any?> =
        c.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.name != "INSTANCE" }
            .associate { f -> f.isAccessible = true; f.name to f.get(null) }

    /** The keys a reply shape names: its constant strings. */
    private fun keysOf(shape: Any): Set<String> =
        statics(shape.javaClass).values.filterIsInstance<String>().toSet()

    @Suppress("UNCHECKED_CAST")
    private val declared: List<Declared> by lazy {
        statics().filter { (_, v) -> v != null && v.javaClass.simpleName == "Endpoint" }.map { (name, e) ->
            e!!
            Declared(
                name = name,
                method = Reach.get(e, "method").toString(),
                path = Reach.get(e, "path") as String,
                query = Reach.get(e, "query") as List<String>,
                allowed = Reach.get(e, "allowed") as Map<String, List<String>>,
                body = Reach.get(e, "body") as List<String>,
                reads = (Reach.get(e, "reply") as List<Any>).filter { it.javaClass.simpleName != "CellSchemaJson" }
                    .flatMap { keysOf(it) }.toSet(),
                accept = Reach.get(e, "accept") as String?,
                events = runCatching { Reach.get(e, "events") as List<String> }.getOrDefault(emptyList()),
            )
        }
    }

    /**
     * The Haus routes a declared call reaches: its own; for a path with a `{…}`
     * Haus does not have as such, the routes it is filled in to — for the tool
     * rows, the paths Haus's catalog names, not every route that shape matches.
     */
    private fun routesOf(d: Declared): List<Route> {
        val exact = HAUS.filter { it.method == d.method && it.path == d.path }
        if (exact.isNotEmpty()) return exact
        val reached = HAUS.filter { it.method == d.method && same(d.path, it.path) }
        return if (d.name == "TOOL_ROWS") reached.filter { it.path in HAUS_TOOL_PATHS } else reached
    }

    private fun failures(check: (Declared, Route) -> List<String>): List<String> =
        declared.flatMap { d -> routesOf(d).flatMap { r -> check(d, r).map { "$d against $r: $it" } } }

    @Test
    fun napiDeclaresItsCallsAsEndpoints() {
        assertTrue("Napi declares no endpoint (method, path, query, body, reply)", declared.isNotEmpty())
    }

    @Test
    fun everyCallIsAHausRouteWithItsMethod() {
        assertTrue("no endpoints", declared.isNotEmpty())
        val wrong = declared.filter { routesOf(it).isEmpty() }
        assertTrue("not served by Haus with that method:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun everyQueryParameterIsOneHausReads() {
        assertTrue("no endpoints", declared.isNotEmpty())
        val wrong = failures { d, r ->
            val out = mutableListOf<String>()
            d.query.filter { it !in r.query.keys }.forEach { out += "Haus reads no query parameter '$it'" }
            r.required.filter { it in r.query.keys && it !in d.query }.forEach { out += "Haus needs '$it'" }
            for ((k, values) in r.query) {
                if (values == null || k !in d.query) continue
                val sent = d.allowed[k]
                if (sent == null) {
                    out += "'$k' takes only $values in Haus; the app must declare which it sends"
                } else {
                    sent.filter { it !in values }.forEach { out += "Haus refuses $k=$it" }
                }
            }
            out
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun everyBodyFieldIsOneHausReads() {
        assertTrue("no endpoints", declared.isNotEmpty())
        val wrong = failures { d, r ->
            d.body.filter { it !in r.body }.map { "Haus reads no body field '$it'" } +
                r.required.filter { it in r.body && it !in d.body }.map { "Haus needs body field '$it'" }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun everyReplyKeyTheAppReadsIsOneHausSends() {
        assertTrue("no endpoints", declared.isNotEmpty())
        val wrong = failures { d, r -> d.reads.filter { it !in r.sends }.map { "Haus never sends '$it'" } }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun theStreamAsksForAndReadsWhatHausSends() {
        val events = declared.singleOrNull { it.method == "GET" && it.path == "/napi/portal/events" }
        assertTrue("no events endpoint", events != null)
        val route = HAUS.single { it.path == "/napi/portal/events" }
        assertEquals(route.accept, events!!.accept)
        assertEquals("the frames the app reads", route.events, events.events)
    }

    /**
     * What Haus added for the app (#178, haus#30) is read: the card's and the
     * tool row's page, the active set's ids, and the watch's active flag.
     */
    @Test
    fun theAppReadsHaussNewFields() {
        val byName = declared.associateBy { it.name }
        assertTrue("a card's page is not read", "page" in byName.getValue("STATE").reads)
        assertTrue("an active card's page is not read", "page" in byName.getValue("ACTIVE").reads)
        assertTrue("a tool row's page is not read", "page" in byName.getValue("TOOL_ROWS").reads)
        assertTrue("the active set's ids are not read", "entity_ids" in byName.getValue("EVENTS").reads)
        assertTrue("the watch says nothing of the active set", "active" in byName.getValue("WATCH").body)
    }

    @Test
    fun theAppDeclaresEveryCallItNeeds() {
        val have = declared.map { "${it.method} ${it.path}" }.toSet()
        val missing = NEEDED.filter { it !in have }
        assertTrue("not declared in Napi: $missing", missing.isEmpty())
    }

    @Test
    fun theToolRowsAreThePathsHaussCatalogNames() {
        val rows = declared.singleOrNull { it.name == "TOOL_ROWS" }
        assertTrue("Napi declares no TOOL_ROWS", rows != null)
        assertEquals("GET", rows!!.method)
        HAUS_TOOL_PATHS.forEach { p ->
            assertTrue("$p is not under ${rows.path}", same(p, rows.path))
            assertTrue("$p is not a GET route of Haus", HAUS.any { it.method == "GET" && it.path == p })
        }
    }

    @Test
    fun theCellRolesHausUsesAreOnesTheAppReads() {
        val shape = napi.declaredClasses.firstOrNull { it.simpleName == "CellSchemaJson" }
        assertTrue("Napi declares no CellSchemaJson", shape != null)
        val roles = keysOf(shape!!.getField("INSTANCE").get(null))
        assertTrue("roles the app does not read: ${HAUS_CELL_ROLES - roles}", roles.containsAll(HAUS_CELL_ROLES))
    }

    @Test
    fun theDeviceWidgetOffersOnlyWhatHausDrawsAsACard() {
        assertEquals(HAUS_CARD_DOMAINS, statics()["CARD_DOMAINS"])
    }

    /** No path hides in Napi outside an endpoint — a glued `ROOT + "…"` included. */
    @Test
    fun napiHoldsNoPathOutsideAnEndpoint() {
        val stray = statics().filter { (name, v) -> v is String && v.startsWith("/napi") && name != "ROOT" }
        assertTrue("paths that are not endpoints: $stray", stray.isEmpty())
        assertEquals("/napi/", statics()["ROOT"])
    }

    @Test
    fun theTokenGoesWhereHausReadsIt() {
        val s = statics()
        assertEquals(HAUS_AUTH_HEADER, s["AUTH_HEADER"])
        assertEquals(HAUS_AUTH_SCHEME, s["AUTH_SCHEME"])
    }

    // --- the sources, read as text ----------------------------------------------

    /** One file builds every request from an endpoint: its method, its auth header, its target. */
    @Test
    fun onlyNapiRequestBuildsARequest() {
        val req = file(REQUEST_FILE)
        assertTrue("$REQUEST_FILE does not exist", req.isFile)
        val text = req.readText()
        assertTrue("the method is not the endpoint's", text.contains(".method(method.name"))
        assertTrue("the token is not sent the way Haus reads it", text.contains(".header(Napi.AUTH_HEADER, Napi.bearer("))
        // A request is built from a builder (new or copied) or not at all, and only
        // NapiRequest holds one; the auth header's name and scheme live in Napi.
        val elsewhere = RepoFiles.mainKotlin().filter { RepoFiles.relative(it) != REQUEST_FILE }.flatMap { f ->
            val rel = RepoFiles.relative(f)
            val banned = listOf("Request.Builder", ".newBuilder()", ".header(", ".addHeader(", ".method(") +
                (if (rel == NAPI_FILE) emptyList() else listOf("\"Bearer", "\"Authorization\""))
            f.readLines().mapIndexedNotNull { i, line ->
                val c = code(line)
                banned.firstOrNull { c.contains(it) }?.let { "$rel:${i + 1}: $it" }
            }
        }
        assertTrue("requests built by hand:\n" + elsewhere.joinToString("\n"), elsewhere.isEmpty())
    }

    @Test
    fun noFileButNapiSpellsAPath() {
        val wrong = literals().filter { (at, s) -> s.contains("/napi") && !at.startsWith("$NAPI_FILE:") }
            .map { (at, s) -> "$at: \"$s\"" }
        assertTrue("paths spelled outside Napi:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /**
     * No tail waits to be glued to a prefix: a directory of Haus's (`portal/`,
     * with or without its slash) or a leaf with its slash (`/upload`). A bare
     * word such as `upload` may be a file name; a path glued from it could still
     * not be called, since only an endpoint becomes a request.
     */
    @Test
    fun noPathIsGluedFromAPrefixAndATail() {
        val dirs = listOf("portal/", "box/", "defs/", "ha/", "persona/")
        val leaves = listOf("/upload", "/action-callback", "/persona", "/whoami")
        val glued = literals().filter { (_, s) -> dirs.any { s.startsWith(it) || s.startsWith("/$it") } || leaves.any { s.startsWith(it) } }
            .map { (at, s) -> "$at: \"$s\"" }
        assertTrue("relative /napi tails:\n" + glued.joinToString("\n"), glued.isEmpty())
    }

    /** The parsers read and write keys through Napi's names; a key spelled by hand is unchecked. */
    @Test
    fun theClientNeverSpellsAKeyItself() {
        val keys = HAUS.flatMap { it.sends + it.body + it.query.keys }.toSet()
        val access = Regex("""\.(opt\w*|put|has|get(String|Int|Long|Double|Boolean|JSONObject|JSONArray))\(\s*"""")
        val wrong = PARSERS.map(::file).flatMap { f ->
            f.readLines().flatMapIndexed { i, line ->
                val c = code(line)
                val at = "${RepoFiles.relative(f)}:${i + 1}"
                val byAccess = if (access.containsMatchIn(c)) listOf("$at: ${c.trim()}") else emptyList()
                val byName = literal.findAll(c).map { it.groupValues[1] }.filter { it in keys }
                    .map { "$at: \"$it\"" }.toList()
                byAccess + byName
            }
        }.distinct()
        assertTrue("keys spelled by hand:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** No query string is written by hand either: it comes from the endpoint's parameters. */
    @Test
    fun noQueryIsWrittenByHand() {
        val wrong = literals().filter { (_, s) -> Regex("""[?&][a-z_]+=""").containsMatchIn(s) }
            .map { (at, s) -> "$at: \"$s\"" }
        assertTrue("query strings by hand:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /**
     * Every page the app opens is one Haus serves. A page path may stand only
     * in HausPages and HausConfig, and each one there must be Haus's.
     */
    @Test
    fun thePagesTheAppOpensAreHauss() {
        val page = Regex("""^/[a-z][a-z0-9_-]*(/[a-z0-9_-]+)*$""")
        val homes = setOf(
            "app/src/main/java/cloud/dopp/haus/widget/HausPages.kt",
            "app/src/main/java/cloud/dopp/haus/HausConfig.kt",
        )
        val pages = literals().filter { (_, s) -> page.matches(s) && !s.startsWith("/napi") }
        assertTrue("no page found at all", pages.isNotEmpty())
        val misplaced = pages.filter { (at, _) -> homes.none { at.startsWith("$it:") } }
        assertTrue("pages outside HausPages/HausConfig: $misplaced", misplaced.isEmpty())
        val foreign = pages.filter { (_, s) -> s !in PAGES }
        assertTrue("pages Haus does not serve: $foreign", foreign.isEmpty())
        assertTrue("the pairing page is not opened", pages.any { it.second == "/pair-device" })
    }

    @Test
    fun theTableItselfReadsAsHausWritesIt() {
        HAUS.forEach { r ->
            assertTrue(r.toString(), r.method == "GET" || r.method == "POST")
            assertTrue(r.toString(), r.path.startsWith("/napi/"))
            assertTrue(r.toString(), r.at.startsWith("internal/napi/"))
        }
        assertEquals(HAUS.size, HAUS.map { "${it.method} ${it.path}" }.toSet().size)
        assertTrue(same("/napi/portal/camera/{x}/snapshot", "/napi/portal/camera/{entity_id}/snapshot"))
        assertTrue(!same("/napi/portal/camera/snapshot", "/napi/portal/camera/{entity_id}/snapshot"))
        assertTrue(same("/napi/portal/{tool}", "/napi/portal/tasks"))
        assertTrue(!same("/napi/portal/{tool}", "/napi/portal/start/addable"))
    }
}
