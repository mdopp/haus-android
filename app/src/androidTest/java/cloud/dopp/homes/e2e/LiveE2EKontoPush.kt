package cloud.dopp.homes.e2e

import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.HomesKonto.Teil
import cloud.dopp.homes.konto.KontoStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Push for the Homes account (#209; e2e/live_e2e.sh step 9): [ein] turns the
 * calendar part on and waits for its first sync; the script then has the
 * fake Homes add an entry and send a `gemeinsam` frame on the open stream;
 * [danach] finds the new entry in the phone's calendar and takes the account
 * away again. Two runs, because the frame comes from the script in between.
 */
@RunWith(AndroidJUnit4::class)
class LiveE2EKontoPush {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun ein() {
        HomesKonto.entfernen(ctx)
        HomesKonto.einschalten(ctx, Teil.KALENDER)
        val store = KontoStore(ctx)
        var warte = 180
        while (store.stand(Teil.KALENDER) == null && warte-- > 0) Thread.sleep(500)
        assertTrue("no first sync; last word: ${store.meldung(Teil.KALENDER)}", store.stand(Teil.KALENDER) != null)
    }

    @Test
    fun danach() {
        try {
            var warte = 60
            while (!da() && warte-- > 0) Thread.sleep(500)
            assertTrue("„Elternabend“ is not in the phone's calendar", da())
        } finally {
            HomesKonto.entfernen(ctx)
        }
    }

    private fun da(): Boolean = ctx.contentResolver.query(
        CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
        "${CalendarContract.Events.ACCOUNT_TYPE} = ? AND ${CalendarContract.Events.TITLE} = ?",
        arrayOf(HomesKonto.TYP, "Elternabend"), null,
    )?.use { it.count > 0 } ?: false
}
