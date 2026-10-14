package cloud.dopp.haus

import cloud.dopp.haus.data.Napi
import cloud.dopp.haus.data.asBody
import cloud.dopp.haus.data.json
import cloud.dopp.haus.data.request
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the one request builder makes of an endpoint: Haus's method, the token
 * where Haus reads it, the target with only the values Haus takes, the body
 * with only the endpoint's fields. NapiContractTest holds the data to Haus;
 * this holds the request to the data. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NapiRequestTest {

    private val base = "https://haus.example/"
    private val token = "haus_device_T1"

    private val all = listOf(
        Napi.PERSONA, Napi.PERSONA_ICON, Napi.WHOAMI, Napi.BOX_HOME, Napi.BOX_SERVICES, Napi.HA_CALL,
        Napi.STATE, Napi.ENERGY, Napi.HISTORY, Napi.ACTIVE, Napi.ADDABLE, Napi.WATCH, Napi.EVENTS,
        Napi.TOOL_DEFS, Napi.ACTION_CALLBACK, Napi.UPLOAD,
    )

    @Test
    fun everyRequestCarriesTheEndpointsMethodAndTheToken() {
        all.forEach { e ->
            val body = if (e.method == Napi.Method.POST) e.json(*arrayOfNulls<Any>(e.body.size)).asBody() else null
            val r = e.request(base, token, body = body)
            assertEquals(e.toString(), e.method.name, r.method)
            assertEquals(e.toString(), "Bearer $token", r.header("Authorization"))
            assertEquals(e.toString(), "https://haus.example" + e.path, r.url.toString())
        }
        assertEquals("text/event-stream", Napi.EVENTS.request(base, token).header("Accept"))
    }

    @Test
    fun aGetHasNoBodyAndAPostHasOne() {
        try {
            Napi.WATCH.request(base, token)
            fail("a POST without a body")
        } catch (e: IllegalArgumentException) {
        }
        try {
            Napi.STATE.request(base, token, body = Napi.WATCH.json(JSONArray()).asBody())
            fail("a GET with a body")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun theTargetCarriesOnlyTheDeclaredParametersAndValues() {
        assertEquals("/napi/portal/state?entity_id=light.kueche", Napi.STATE.target("light.kueche"))
        assertEquals(
            "/napi/portal/entity-history?entity_id=sensor.pv&range=7d",
            Napi.HISTORY.target("sensor.pv", Napi.HistoryRange.WEEK.wire),
        )
        try {
            Napi.HISTORY.target("sensor.pv", "30d")
            fail("a range Haus refuses")
        } catch (e: IllegalArgumentException) {
        }
        try {
            Napi.STATE.target("a", "b")
            fail("more values than parameters")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun theBodyCarriesOnlyTheDeclaredFields() {
        val call = Napi.HA_CALL.json("light.kueche", "light.toggle", null, null)
        assertEquals(setOf("entity_id", "service"), call.keys().asSequence().toSet())
        val confirmed = Napi.HA_CALL.json("lock.tuer", "lock.open", null, true)
        assertTrue(confirmed.getBoolean("confirmed"))
        try {
            Napi.WATCH.json("a", "b")
            fail("more values than fields")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun aToolRowsPathIsTakenOnlyInItsShape() {
        assertEquals("/napi/portal/tasks", Napi.TOOL_ROWS.at("/napi/portal/tasks")?.path)
        assertEquals("/napi/portal/tasks?done=1", Napi.TOOL_ROWS.at("/napi/portal/tasks?done=1")?.path)
        assertNull(Napi.TOOL_ROWS.at("/napi/box/home"))
        assertNull(Napi.TOOL_ROWS.at("/napi/portal/start/addable"))
        assertNull(Napi.TOOL_ROWS.at("/napi/x"))
    }
}
