package cloud.dopp.haus

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app calls only what Haus serves (#176, laib ADR 0022: Haus's /napi is
 * the new app's API).
 *
 * [HAUS] is Haus's own table of every /napi route (mdopp/haus
 * internal/napi/napi_test.go, TestEveryNapiRouteWantsADevice — a route Haus
 * registers without a row there fails Haus's build), and [PAGES] the pages
 * the app opens in the browser (cmd/haus/main.go). When Haus changes either,
 * this copy changes with it, in the same breath.
 *
 * The test reads the app's sources: every string that names a /napi path must
 * be one of Haus's, spelled whole. A path glued together from a bare "/napi"
 * and a relative tail could not be checked, so neither half may exist.
 */
class NapiContractTest {

    companion object {
        val HAUS = setOf(
            "GET /napi/persona", "GET /napi/persona/icon.svg", "GET /napi/whoami",
            "GET /napi/box/home", "GET /napi/box/services", "GET /napi/box/approvals", "GET /napi/box/upgrades",
            "POST /napi/box/services/{name}/operate", "POST /napi/box/approvals/{id}/approve",
            "POST /napi/box/approvals/{id}/deny",
            "POST /napi/ha/call", "GET /napi/portal/state", "GET /napi/portal/energy",
            "GET /napi/portal/entity-history", "GET /napi/portal/active",
            "GET /napi/portal/camera/{entity_id}/snapshot", "GET /napi/portal/start/addable",
            "POST /napi/portal/watch", "GET /napi/portal/events",
            "GET /napi/defs/tool", "POST /napi/action-callback", "GET /napi/portal/tasks",
            "GET /napi/portal/contacts", "GET /napi/portal/documents", "GET /napi/portal/notes",
            "POST /napi/upload",
        )

        val PAGES = setOf("/pair-device", "/start", "/raeume", "/kalender", "/notizen", "/box", "/box/geraete")

        /** What the widgets and the pairing need: each must be spelled in the app. */
        val NEEDED = setOf(
            "/napi/persona", "/napi/persona/icon.svg",
            "/napi/box/home", "/napi/box/services",
            "/napi/ha/call", "/napi/portal/state", "/napi/portal/energy", "/napi/portal/entity-history",
            "/napi/portal/active", "/napi/portal/camera/{entity_id}/snapshot", "/napi/portal/start/addable",
            "/napi/portal/watch", "/napi/portal/events",
            "/napi/defs/tool", "/napi/action-callback", "/napi/upload",
            "/pair-device",
        )

        private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        private val template = Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*""")

        /** A path the way Haus writes it: no query, `{x}` for each `$value`. */
        fun shape(raw: String): String = template.replace(raw.substringBefore('?'), "{x}")

        fun matches(path: String, route: String): Boolean {
            val a = path.trimEnd('/').split('/')
            val b = route.trimEnd('/').split('/')
            return a.size == b.size && a.indices.all { i ->
                a[i] == b[i] || (b[i].startsWith("{") && a[i].isNotEmpty())
            }
        }

        private val hausPaths = HAUS.map { it.substringAfter(' ') }

        /** Every string literal in the main sources, with where it stands. */
        fun literals(): List<Pair<String, String>> = RepoFiles.mainKotlin().flatMap { f ->
            f.readLines().flatMapIndexed { i, line ->
                literal.findAll(line).map { "${RepoFiles.relative(f)}:${i + 1}" to it.groupValues[1] }.toList()
            }
        }
    }

    @Test
    fun everyNapiPathTheAppNamesIsServedByHaus() {
        val wrong = literals().filter { (_, s) -> s.startsWith("/napi") }
            .filter { (_, s) -> hausPaths.none { matches(shape(s), it) } }
            .map { (at, s) -> "$at: \"$s\"" }
        assertTrue("paths Haus does not serve (or not spelled whole):\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun noPathIsGluedFromAPrefixAndATail() {
        val tails = listOf("/portal/", "/box/", "/defs/", "/ha/", "/upload", "/action-callback", "/persona", "/whoami")
        val glued = literals().filter { (_, s) -> tails.any { s.startsWith(it) } }
            .map { (at, s) -> "$at: \"$s\"" }
        assertTrue("relative /napi tails:\n" + glued.joinToString("\n"), glued.isEmpty())
    }

    @Test
    fun theAppSpellsEveryPathItNeeds() {
        val spelled = literals().map { shape(it.second) }.toSet()
        val missing = NEEDED.filter { need -> spelled.none { matches(it, need) } }
        assertTrue("not spelled anywhere in the app: $missing", missing.isEmpty())
    }

    @Test
    fun thePagesTheAppOpensAreHauss() {
        val opened = literals().map { it.second }
            .filter { s -> PAGES.any { s == it } || s == "/download" || s.startsWith("/#") }
        val foreign = opened.filter { it !in PAGES }
        assertTrue("pages Haus does not serve: $foreign", foreign.isEmpty())
    }

    @Test
    fun theTableItselfReadsAsHausWritesIt() {
        // a guard on the copy: methods are GET or POST, paths start at /napi/
        HAUS.forEach { r ->
            val (m, p) = r.split(' ')
            assertTrue(r, m == "GET" || m == "POST")
            assertTrue(r, p.startsWith("/napi/"))
        }
        assertTrue(matches("/napi/portal/camera/{x}/snapshot", "/napi/portal/camera/{entity_id}/snapshot"))
        assertTrue(!matches("/napi/portal/camera/snapshot", "/napi/portal/camera/{entity_id}/snapshot"))
        assertTrue(shape("/napi/portal/state?entity_id=\$id") == "/napi/portal/state")
    }
}
