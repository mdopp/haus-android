package cloud.dopp.haus

import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ApiClient.ActionOutcome
import cloud.dopp.haus.data.ToolDefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How the app reads `POST /napi/action-callback` (#90).
 *
 * The distinction that matters: **both** refusals are HTTP 403, so the status code
 * alone cannot tell "ask the user and retry" from "you may never do this". Only the
 * body's `reason` separates them — and on `/napi/` an admin action is *always*
 * `forbidden`, because `Remote-Groups` is client-supplied there.
 *
 * Robolectric only for `org.json`; both functions under test are Android-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionCallbackTest {

    @Test
    fun successIsOk() {
        assertEquals(ActionOutcome.OK, ApiClient.actionOutcome(200, """{"ok":true}"""))
        assertEquals(ActionOutcome.OK, ApiClient.actionOutcome(204, null))
    }

    /** Destructive + unconfirmed → confirm, then re-send with `confirmed=true`. */
    @Test
    fun confirmRequiredIsTheOnlyRetryableRefusal() {
        assertEquals(
            ActionOutcome.CONFIRM_REQUIRED,
            ApiClient.actionOutcome(403, """{"ok":false,"reason":"confirm_required"}"""),
        )
    }

    /** Admin-only on the native surface: no dialog, nothing to retry. */
    @Test
    fun forbiddenIsNotRetryable() {
        assertEquals(
            ActionOutcome.FORBIDDEN,
            ApiClient.actionOutcome(403, """{"ok":false,"reason":"forbidden"}"""),
        )
        // A 403 we can't read must not become a confirm dialog for an unnamed action.
        assertEquals(ActionOutcome.FORBIDDEN, ApiClient.actionOutcome(403, "not json"))
        assertEquals(ActionOutcome.FORBIDDEN, ApiClient.actionOutcome(403, null))
    }

    @Test
    fun unknownActionLeavesTheRowAlone() {
        assertEquals(
            ActionOutcome.UNKNOWN_ACTION,
            ApiClient.actionOutcome(404, """{"ok":false,"reason":"unknown_action"}"""),
        )
        assertEquals(ActionOutcome.UNKNOWN_ACTION, ApiClient.actionOutcome(404, null))
    }

    @Test
    fun everythingElseFailsQuietly() {
        assertEquals(ActionOutcome.FAILED, ApiClient.actionOutcome(400, """{"reason":"no_action_id"}"""))
        assertEquals(ActionOutcome.FAILED, ApiClient.actionOutcome(500, null))
    }

    /** The wire body the confirm retry re-sends, unchanged apart from the flag. */
    @Test
    fun theRetryAddsOnlyTheConfirmedFlag() {
        val params = JSONObject().put("entity_id", "t42").put("status", "done")
        val first = ToolDefs.actionBody("task.set_status", params)
        val retry = ToolDefs.actionBody("task.set_status", params, confirmed = true)
        assertEquals(first.getString("action_id"), retry.getString("action_id"))
        assertEquals(
            first.getJSONObject("params").toString(),
            retry.getJSONObject("params").toString(),
        )
        assertEquals(true, retry.getBoolean("confirmed"))
    }
}
