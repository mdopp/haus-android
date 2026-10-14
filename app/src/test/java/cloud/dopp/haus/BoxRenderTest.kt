package cloud.dopp.haus

import cloud.dopp.haus.data.BoxService
import cloud.dopp.haus.widget.BoxRender
import cloud.dopp.haus.widget.BoxRender.Health
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic check for the Box service-status bucketing (#44): each service
 * maps to healthy / warning / failure, and a list rolls up to the three counts the
 * summary widget shows.
 */
class BoxRenderTest {

    private fun svc(active: String, sub: String = "", health: String = "") =
        BoxService(name = "s", activeState = active, subState = sub, health = health)

    @Test fun activeRunningIsHealthy() {
        assertEquals(Health.HEALTHY, BoxRender.bucket(svc("active", "running")))
        assertEquals(Health.HEALTHY, BoxRender.bucket(svc("active", "running", "healthy")))
    }

    @Test fun failedIsFailure() {
        assertEquals(Health.FAILURE, BoxRender.bucket(svc("failed")))
        assertEquals(Health.FAILURE, BoxRender.bucket(svc("active", "running", "unhealthy")))
    }

    @Test fun inactiveOrDegradedIsWarning() {
        assertEquals(Health.WARNING, BoxRender.bucket(svc("inactive")))
        assertEquals(Health.WARNING, BoxRender.bucket(svc("activating")))
        assertEquals(Health.WARNING, BoxRender.bucket(svc("active", "running", "degraded")))
    }

    @Test fun countsRollUp() {
        val list = listOf(
            svc("active", "running"),
            svc("active", "running", "healthy"),
            svc("failed"),
            svc("inactive"),
        )
        assertEquals(Triple(2, 1, 1), BoxRender.counts(list))
    }
}
