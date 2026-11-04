package cloud.dopp.homes.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp the app has: every call shares its connection pool and
 * threads, and each caller names only its timeouts, in seconds. A client
 * from [client] is cheap; keep it in a `lazy` beside its calls.
 */
object HomesHttp {
    private val shared: OkHttpClient by lazy { OkHttpClient() }

    /** OkHttp's own default for a timeout not named. */
    private const val STANDARD = 10L

    fun client(connect: Long = STANDARD, read: Long = STANDARD, write: Long = STANDARD): OkHttpClient =
        shared.newBuilder()
            .connectTimeout(connect, TimeUnit.SECONDS)
            .readTimeout(read, TimeUnit.SECONDS)
            .writeTimeout(write, TimeUnit.SECONDS)
            .build()
}
