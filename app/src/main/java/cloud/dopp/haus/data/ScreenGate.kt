package cloud.dopp.haus.data

import android.content.Context
import android.os.PowerManager
import java.io.IOException

/**
 * With the screen off nothing goes over the network (docs/CONTRACT.md (c)).
 * The live stream holds that by itself; this is the gate every other request
 * to Haus passes, in [ApiClient] and [BoxApiClient]. A widget update Android
 * delivers late, after the screen went off, reads nothing and keeps its last
 * card; the stream's resync redraws every fed widget when the screen is on
 * again. Seen on API 31 in the live e2e, step 2: a WATCH 4 s after screen off.
 */
object ScreenGate {

    /** Instead of a request while the screen is off: an IOException, so every caller treats it as offline. */
    class ScreenOffException : IOException("screen off: no request to Haus")

    /** Whether a request may go out now. Unknown counts as on: no PowerManager, no gate. */
    fun open(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    /** Throws [ScreenOffException] while the screen is off. */
    fun check(ctx: Context) {
        if (!open(ctx)) throw ScreenOffException()
    }
}
