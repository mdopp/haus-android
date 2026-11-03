package cloud.dopp.homes.konto

/**
 * What one sync of the Homes account changes on the phone (#206): the phone
 * holds a copy of Homes' state, nothing else. [vorhanden] is what the phone
 * has, one row per entry: its row id, its sync id and the state it was
 * written from; [kommend] is Homes' state, by sync id. A row whose sync id
 * Homes no longer sends goes, so does a second row of one sync id; one whose
 * state differs is written again; what the phone lacks is added. Pure.
 */
object Abgleich {

    data class Zeile(val id: Long, val syncId: String?, val stand: String?)

    data class Plan<T>(val neu: List<T>, val geaendert: List<Pair<Long, T>>, val weg: List<Long>) {
        val nichts: Boolean get() = neu.isEmpty() && geaendert.isEmpty() && weg.isEmpty()
    }

    fun <T> plane(vorhanden: List<Zeile>, kommend: Map<String, T>, stand: (T) -> String): Plan<T> {
        val weg = ArrayList<Long>()
        val gesehen = HashMap<String, Zeile>()
        for (z in vorhanden) {
            val sid = z.syncId
            if (sid == null || sid !in kommend || sid in gesehen) {
                weg += z.id
            } else {
                gesehen[sid] = z
            }
        }
        val neu = ArrayList<T>()
        val geaendert = ArrayList<Pair<Long, T>>()
        for ((sid, t) in kommend) {
            val z = gesehen[sid]
            when {
                z == null -> neu += t
                z.stand != stand(t) -> geaendert += z.id to t
            }
        }
        return Plan(neu, geaendert, weg)
    }
}
