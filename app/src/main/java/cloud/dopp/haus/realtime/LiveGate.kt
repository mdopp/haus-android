package cloud.dopp.haus.realtime

/**
 * When the live stream runs (laib ADR 0022 §2): only while the screen is on and
 * there is something to keep current. Screen on → open; once connected →
 * resync (watch set, the widgets' cards), because Haus sends a card only when
 * it changes and the screen-off gap was not listened to. Screen off → close,
 * and nothing runs, polls or catches up until the screen is on again.
 *
 * Pure and synchronous — the Android side ([LiveStream]) feeds it the screen
 * broadcasts and the connection's callbacks — so the rule itself is JVM-tested.
 */
class LiveGate(private val stream: Stream) {

    /** What the gate drives: the connection and the catch-up after it opened. */
    interface Stream {
        fun open()
        fun close()
        fun resync()
    }

    /** A stream is open or opening. */
    @Volatile
    var running = false
        private set

    @Volatile
    private var screenOn = false

    /**
     * The screen turned on or off, or what is worth listening for changed
     * ([eligible]: paired and at least one widget placed). Opens at most one
     * stream: SCREEN_ON and USER_PRESENT both arrive on unlock.
     */
    @Synchronized
    fun screen(on: Boolean, eligible: Boolean) {
        screenOn = on
        if (on && eligible) {
            if (!running) {
                running = true
                stream.open()
            }
        } else if (running) {
            running = false
            stream.close()
        }
    }

    /** The stream is up: resync, unless the screen went off in the meantime. */
    @Synchronized
    fun connected() {
        if (running && screenOn) stream.resync()
    }

    /** The stream went away by itself. True iff it is worth opening again: the screen is still on. */
    @Synchronized
    fun dropped(): Boolean {
        val was = running
        running = false
        return was && screenOn
    }

    /** A frame is read only while a stream should be running. */
    fun accepts(): Boolean = running && screenOn
}
