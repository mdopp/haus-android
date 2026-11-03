package cloud.dopp.haus.konto

/**
 * When a `gemeinsam` frame of the live stream (#209, haus#223) asks the Haus
 * account to sync a part: at once when the part has not been asked for in
 * the last [fenster] ms, else once at the end of that window, however many
 * frames come meanwhile — a burst of edits in Haus costs two syncs at most.
 * Pure; the live service holds one and posts the later sync on its handler.
 */
class Anstoss(private val fenster: Long = FENSTER_MS) {

    private val zuletzt = HashMap<HausKonto.Teil, Long>()
    private val wartet = HashSet<HausKonto.Teil>()

    /** A frame for [teil] at [jetzt]: 0 to sync now, a delay to sync then, null when one is due already. */
    fun wann(teil: HausKonto.Teil, jetzt: Long): Long? {
        if (teil in wartet) return null
        val vorher = zuletzt[teil]
        if (vorher == null || jetzt - vorher >= fenster) {
            zuletzt[teil] = jetzt
            return 0L
        }
        wartet += teil
        return vorher + fenster - jetzt
    }

    /** The later sync of [teil] ran at [jetzt]. */
    fun erledigt(teil: HausKonto.Teil, jetzt: Long) {
        wartet -= teil
        zuletzt[teil] = jetzt
    }

    /** The stream ended: a later sync it planned is dropped (the hourly one stays). */
    fun vergiss() {
        wartet.clear()
    }

    companion object {
        const val FENSTER_MS = 30_000L
    }
}
