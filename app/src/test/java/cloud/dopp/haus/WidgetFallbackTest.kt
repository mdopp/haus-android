package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import cloud.dopp.haus.widget.WidgetFallback
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the safe-render fallback (#32). `WidgetFallback.show()` is the widgets'
 * last line of defence — it must never throw, even when handed a bogus widget id
 * or a null tap intent. Robolectric supplies a real-enough Context so the
 * RemoteViews inflation runs, and we assert the call completes without escaping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetFallbackTest {

    @Test
    fun show_withInvalidId_doesNotThrow() {
        val ctx = RuntimeEnvironment.getApplication()
        WidgetFallback.show(ctx, AppWidgetManager.INVALID_APPWIDGET_ID)
    }

    @Test
    fun show_withNullTap_doesNotThrow() {
        val ctx = RuntimeEnvironment.getApplication()
        WidgetFallback.show(ctx, 42, onTap = null)
    }

    @Test
    fun show_withArbitraryId_doesNotThrow() {
        val ctx = RuntimeEnvironment.getApplication()
        WidgetFallback.show(ctx, 1)
        WidgetFallback.show(ctx, Int.MAX_VALUE)
    }
}
