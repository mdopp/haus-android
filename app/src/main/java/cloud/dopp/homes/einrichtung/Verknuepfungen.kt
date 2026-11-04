package cloud.dopp.homes.einrichtung

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cloud.dopp.homes.R
import cloud.dopp.homes.data.Napi.EinrichtungJson
import cloud.dopp.homes.widget.DeviceIcons
import cloud.dopp.homes.widget.DeviceShortcuts
import cloud.dopp.homes.widget.ShortcutDevice

/** The launcher's shortcuts, in their order (owner, 2026-11-04), each with its words and badge. */
enum class Kurz(val id: String, @StringRes val kurz: Int, @StringRes val lang: Int, @DrawableRes val bild: Int) {
    GARAGE("garage", R.string.kurz_garage, R.string.kurz_garage_lang, R.drawable.ic_shortcut_garage),
    EINKAUF(EinrichtungJson.EINKAUF, R.string.kurz_einkauf, R.string.kurz_einkauf, R.drawable.ic_shortcut_einkauf),
    ZEITLEISTE(EinrichtungJson.ZEITLEISTE, R.string.kurz_zeitleiste, R.string.kurz_zeitleiste, R.drawable.ic_shortcut_zeitleiste),
    SCANNEN(EinrichtungJson.SCANNEN, R.string.kurz_scannen, R.string.kurz_scannen, R.drawable.ic_shortcut_scannen),
}

/** One shortcut: the garage's entity id and name, or the address a target leads to. */
data class Verknuepfung(val art: Kurz, val ziel: String, val name: String? = null)

/**
 * The app icon's long-press menu (owner, 2026-11-04): exactly Garagentor,
 * Einkaufsliste, Zeitleiste and Dokument scannen, in this order, each only
 * where the box has it — the garage among the person's favourites or the
 * household's devices, the others where `/napi/einrichtung`'s `ziele` names
 * them. Pure: the shortcuts are decided from this alone.
 */
object Verknuepfungen {

    /** The shortcut ids' prefix, so a published entry is recognisable and stable. */
    const val PREFIX = "kurz:"

    fun liste(ziele: Map<String, String>, garage: ShortcutDevice?): List<Verknuepfung> =
        Kurz.values().mapNotNull { k ->
            if (k == Kurz.GARAGE) {
                garage?.let { Verknuepfung(k, it.entityId, it.name.ifBlank { it.entityId }) }
            } else {
                ziele[k.id]?.let { Verknuepfung(k, it) }
            }
        }

    /** The garage: the first cover drawn as a garage (class garage or gate), its domain read from its id. */
    fun garage(geraete: List<ShortcutDevice>): ShortcutDevice? =
        geraete.firstOrNull { DeviceShortcuts.domainOf(it.entityId) == "cover" && DeviceIcons.isGarage(it.deviceClass) }
}
