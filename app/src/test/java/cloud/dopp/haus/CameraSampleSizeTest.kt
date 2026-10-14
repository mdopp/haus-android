package cloud.dopp.haus

import cloud.dopp.haus.data.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM coverage for [ApiClient.sampleSize] (#30) — the power-of-two
 * downscale factor that keeps a camera snapshot under the RemoteViews Binder
 * transaction limit. Verifies the longer edge lands at/under the target and the
 * factor is always a valid (≥ 1) power of two.
 */
class CameraSampleSizeTest {

    private val max = ApiClient.CAMERA_MAX_EDGE_PX // 640

    @Test
    fun smallerThanTarget_noDownscale() {
        assertEquals(1, ApiClient.sampleSize(320, 240, max))
        assertEquals(1, ApiClient.sampleSize(640, 480, max))
    }

    @Test
    fun justOverTarget_stillNoDownscale_untilDoubled() {
        // 641 wide: halving (321) would drop below 640, so keep full res.
        assertEquals(1, ApiClient.sampleSize(641, 480, max))
        // 1280 wide: halving to 640 is exactly the target → sample 2.
        assertEquals(2, ApiClient.sampleSize(1280, 720, max))
    }

    @Test
    fun fourKDownscalesToPowerOfTwo() {
        // 3840×2160: /2=1920, /4=960 (>=640, halve again), /8=480 (<640, stop) → 4.
        assertEquals(4, ApiClient.sampleSize(3840, 2160, max))
        // 6000 wide: /2=3000, /4=1500, /8=750 (>=640), /16=375 (<640) → 8.
        assertEquals(8, ApiClient.sampleSize(6000, 3000, max))
    }

    @Test
    fun usesLongerEdge_portraitOrientation() {
        // Tall image: the height governs the factor.
        assertEquals(2, ApiClient.sampleSize(720, 1280, max))
    }

    @Test
    fun degenerateInputs_neverBelowOne() {
        assertEquals(1, ApiClient.sampleSize(0, 0, max))
        assertEquals(1, ApiClient.sampleSize(1000, 1000, 0))
        assertEquals(1, ApiClient.sampleSize(100, 100, max))
    }
}
