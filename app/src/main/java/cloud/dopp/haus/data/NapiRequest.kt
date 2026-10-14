package cloud.dopp.haus.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The one place an [Napi.Endpoint] becomes an HTTP request: the endpoint's
 * method, the device token in the header Haus reads it from, and the target
 * the endpoint built. Nothing else in the app builds a request
 * (`NapiContractTest.onlyNapiRequestBuildsARequest`).
 *
 * [target] is the endpoint's path with its query ([Napi.Endpoint.target]); a
 * GET carries no [body], a POST always one.
 */
fun Napi.Endpoint.request(
    base: String,
    token: String,
    target: String = path,
    body: RequestBody? = null,
): Request {
    require((method == Napi.Method.GET) == (body == null)) { "$this: a GET has no body, a POST has one" }
    val b = Request.Builder()
        .url(base.trimEnd('/') + target)
        .header(Napi.AUTH_HEADER, Napi.bearer(token))
        .method(method.name, body)
    accept?.let { b.header("Accept", it) }
    return b.build()
}

/** This endpoint's JSON body: its [Napi.Endpoint.body] names with [values], in order, nulls left out. */
fun Napi.Endpoint.json(vararg values: Any?): JSONObject = JSONObject(fields(*values))

/** [this] as a request body. */
fun JSONObject.asBody(): RequestBody = toString().toRequestBody(JSON_TYPE)

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
