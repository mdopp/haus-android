package cloud.dopp.haus.wechsel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cloud.dopp.haus.R
import cloud.dopp.haus.ui.HomeActivity

/**
 * The one notice beside the live service's notification (the owner's call,
 * laib#1021): many people only use the widgets and never open the app, so
 * they learn here that Haus is now Homes. Posted at most once a day, only
 * while the notice applies ([Wechsel.Hinweis]), only when notifications are
 * allowed; a tap opens the start screen, which says what to do. When the
 * notice no longer applies, a posted one is taken back.
 */
object WechselNachricht {
    private const val CHANNEL = "wechsel"
    private const val ID = 4802

    /** Post the notice for what [Wechsel.jetzt] says, once on the day of [jetztMs]; take it back when nothing applies. */
    fun melden(ctx: Context, jetztMs: Long = System.currentTimeMillis()) {
        val (hinweis, ziel) = Wechsel.jetzt(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (hinweis == Wechsel.Hinweis.KEINER || ziel == null) {
            runCatching { nm.cancel(ID) }
            return
        }
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        if (!Wechsel.meldenHeute(ctx, jetztMs)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.wechsel_kanal), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = ctx.getString(R.string.wechsel_kanal_info) },
            )
        }
        val tap = PendingIntent.getActivity(
            ctx, ID, Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (titel, text) = when (hinweis) {
            Wechsel.Hinweis.LOESCHEN -> R.string.wechsel_nachricht_loeschen_titel to R.string.wechsel_nachricht_loeschen
            else -> R.string.wechsel_nachricht_titel to R.string.wechsel_nachricht
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_haus)
            .setContentTitle(ctx.getString(titel, ziel.name))
            .setContentText(ctx.getString(text, ziel.name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(text, ziel.name)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    /** Take a posted notice back. */
    fun weg(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
