package cloud.dopp.haus

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.widget.EnergyRange
import cloud.dopp.haus.widget.WidgetStore
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Verlauf widget's 24h/7d choice must survive across refreshes, per instance.
 * Drives [WidgetStore] on the JVM (Robolectric SharedPreferences).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetStoreRangeTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun rangeDefaultsTo24hWhenUnset() {
        assertSame(EnergyRange.DAY, WidgetStore.range(ctx, 42))
    }

    @Test fun rangePersistsPerInstanceAndIsIndependent() {
        WidgetStore.setRange(ctx, 1, EnergyRange.WEEK)
        WidgetStore.setRange(ctx, 2, EnergyRange.DAY)
        assertSame(EnergyRange.WEEK, WidgetStore.range(ctx, 1))
        assertSame(EnergyRange.DAY, WidgetStore.range(ctx, 2))
    }

    @Test fun unbindClearsThePersistedRange() {
        WidgetStore.setRange(ctx, 7, EnergyRange.WEEK)
        WidgetStore.unbind(ctx, 7)
        assertSame(EnergyRange.DAY, WidgetStore.range(ctx, 7))
    }
}
