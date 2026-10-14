package cloud.dopp.haus

import cloud.dopp.haus.data.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM coverage for [ApiClient.watchBody] (#48 Option B) — the
 * `POST /napi/portal/watch` request body. Verifies the shape is exactly
 * `{"entity_ids":[…]}` and preserves order, so the server's `ha_watch` gets the
 * device-widget entity set it expects. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchBodyTest {

    @Test
    fun buildsEntityIdsArray_inOrder() {
        val body = ApiClient.watchBody(listOf("light.buero", "cover.garage"))
        val arr = body.getJSONArray("entity_ids")
        assertEquals(2, arr.length())
        assertEquals("light.buero", arr.getString(0))
        assertEquals("cover.garage", arr.getString(1))
    }

    @Test
    fun emptyList_yieldsEmptyArray() {
        val body = ApiClient.watchBody(emptyList())
        assertEquals(0, body.getJSONArray("entity_ids").length())
        // The key is always present so the server can clear a device's watch-set.
        assertEquals(true, body.has("entity_ids"))
    }

    @Test
    fun serializesToExpectedJson() {
        val json = ApiClient.watchBody(listOf("switch.lamp")).toString()
        assertEquals("{\"entity_ids\":[\"switch.lamp\"]}", json)
    }
}
