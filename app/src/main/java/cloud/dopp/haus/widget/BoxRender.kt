package cloud.dopp.haus.widget

import cloud.dopp.haus.data.BoxService

/**
 * Pure health bucketing for the Box service-status summary widget (#44).
 * The widget shows only three counts — healthy / warning / failure — so this maps
 * one service's raw state into a bucket. No Android types → JVM-unit-testable.
 */
object BoxRender {
    const val GREEN = 0xFF66BB6A.toInt()
    const val AMBER = 0xFFFFC107.toInt()
    const val RED = 0xFFEF5350.toInt()

    enum class Health { HEALTHY, WARNING, FAILURE }

    /** Bucket one service by its active/sub state + health. */
    fun bucket(s: BoxService): Health {
        val a = s.activeState.lowercase()
        val h = s.health.lowercase()
        val sub = s.subState.lowercase()
        return when {
            a == "failed" || h == "failed" || h == "unhealthy" || h == "critical" -> Health.FAILURE
            (a == "active" || sub == "running") && (h.isEmpty() || h == "healthy" || h == "ok") -> Health.HEALTHY
            else -> Health.WARNING
        }
    }

    /** (healthy, warning, failure) counts for a whole service list. */
    fun counts(list: List<BoxService>): Triple<Int, Int, Int> {
        var ok = 0
        var warn = 0
        var fail = 0
        list.forEach {
            when (bucket(it)) {
                Health.HEALTHY -> ok++
                Health.WARNING -> warn++
                Health.FAILURE -> fail++
            }
        }
        return Triple(ok, warn, fail)
    }
}
