package cloud.dopp.homes.kann

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.EnergyWidgetProvider

/**
 * The device and energy widgets in the system's widget picker follow the box
 * (homes ADR 0001 §3): a provider is disabled while the box lacks its key
 * *and* none of its widgets is placed — disabling it would take a placed one
 * off the home screen, so a placed one keeps it, showing the module's reason
 * ([cloud.dopp.homes.widget.WidgetFallback.weg]). With what the box can do
 * unknown, both are enabled, as before.
 */
object Anbieter {

    private val PROVIDER = linkedMapOf(
        KannKey.GERAET to DeviceWidgetProvider::class.java,
        KannKey.ENERGIE to EnergyWidgetProvider::class.java,
    )

    /** Switch both providers to what [kann] offers; true when one changed. */
    fun schalten(ctx: Context, kann: Kann?): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        val pm = ctx.packageManager
        var changed = false
        PROVIDER.forEach { (key, cls) ->
            val c = ComponentName(ctx, cls)
            // not readable: as if placed, never disabled on a guess
            val placed = runCatching { mgr.getAppWidgetIds(c).isNotEmpty() }.getOrDefault(true)
            val want = if (kann.hat(Liste.WIDGETS, key) || placed) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (pm.getComponentEnabledSetting(c) != want) {
                pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
                changed = true
            }
        }
        return changed
    }
}
