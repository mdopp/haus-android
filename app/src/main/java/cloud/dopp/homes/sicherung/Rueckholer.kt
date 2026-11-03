package cloud.dopp.homes.sicherung

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.SicherungClient
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * The one restore under way (homes-android#197): a thread of the app's
 * process that outlives the screen turning or being left for a moment, and
 * ends when the process does — no job, no service, no wakelock (CLAUDE.md:
 * one job only). While it runs, the screen keeps itself on; when it was
 * stopped, the next „Weiter“ goes on where the file stopped.
 */
object Rueckholer {

    sealed class Zustand {
        /** Nothing under way; [ausgang] of the last run of [quelle], if there was one in this process. */
        data class Ruht(val quelle: Quelle? = null, val ausgang: Rueckholung.Ausgang? = null) : Zustand()

        /** [quelle] is being restored; [fortschritt] once the first byte came. */
        data class Laeuft(val quelle: Quelle, val fortschritt: Fortschritt?) : Zustand()
    }

    @Volatile
    var zustand: Zustand = Zustand.Ruht()
        private set

    @Volatile
    private var halt = false

    private val beobachter = CopyOnWriteArraySet<(Zustand) -> Unit>()
    private val haupt = Handler(Looper.getMainLooper())

    /** [zeige] gets the state now and on each change, on the main thread, until [abmelden]. */
    fun anmelden(zeige: (Zustand) -> Unit) {
        beobachter += zeige
        zeige(zustand)
    }

    fun abmelden(zeige: (Zustand) -> Unit) {
        beobachter -= zeige
    }

    private fun setze(z: Zustand) {
        zustand = z
        haupt.post { beobachter.forEach { it(z) } }
    }

    fun laeuft(): Boolean = zustand is Zustand.Laeuft

    /**
     * Restore [plan] of [quelle] into the folder [baum] ([unter] its prefix),
     * the conflicts in [ersetzen] over what is there. False when one runs already.
     */
    @Synchronized
    fun starten(ctx: Context, client: SicherungClient, quelle: Quelle, plan: Rueckholung.Plan, baum: Uri, ersetzen: Set<String>): Boolean {
        if (laeuft()) return false
        halt = false
        laufender = client
        val app = ctx.applicationContext
        setze(Zustand.Laeuft(quelle, null))
        thread(name = "rueckholen") {
            var zuletzt = 0L
            val r = Rueckholung(client, RueckStore(app), halt = { halt }, melde = { f ->
                // the screen needs no more than a few frames a second
                val jetzt = System.currentTimeMillis()
                if (jetzt - zuletzt > 200 || f.fertig == f.von) {
                    zuletzt = jetzt
                    setze(Zustand.Laeuft(quelle, f))
                }
            })
            val ausgang = try {
                r.laufen(plan, SafZiel(app, baum), ersetzen)
            } catch (e: Exception) {
                // the folder's provider failed outside a write (SecurityException, a gone tree)
                Rueckholung.Ausgang(Rueckholung.Art.ORDNER, 0, Rueckholung.ORDNER_FEHLER)
            }
            laufender = null
            setze(Zustand.Ruht(quelle, ausgang))
        }
        return true
    }

    /** Stop after the piece being written; the next start goes on from there. */
    fun anhalten() {
        halt = true
        laufender?.abbrechen()
    }

    @Volatile
    private var laufender: SicherungClient? = null
}
