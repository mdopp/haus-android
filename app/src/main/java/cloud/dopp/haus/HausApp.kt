package cloud.dopp.haus

import android.app.Application
import cloud.dopp.haus.realtime.LiveStream

/**
 * The app process. Whenever Android starts it — a widget update, a tap, the
 * home screen — it starts listening for the screen, so the live stream runs
 * while the screen is on and nowhere else (laib ADR 0022 §2).
 */
class HausApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveStream.install(this)
    }
}
