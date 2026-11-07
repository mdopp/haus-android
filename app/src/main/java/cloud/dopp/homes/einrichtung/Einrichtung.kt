package cloud.dopp.homes.einrichtung

import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi.EinrichtungJson
import cloud.dopp.homes.data.Napi.EinrichtungModulJson
import cloud.dopp.homes.data.Napi.KannJson
import org.json.JSONObject

/** A module's own phone app: its name, its Android package, where to get it. */
data class App(val name: String, val paket: String, val store: String?)

/** The person's setup task for a module with an app; [einrichten] is Homes' page with its steps. */
data class Aufgabe(val titel: String, val erledigt: Boolean, val einrichten: String?)

/** One module that is on, as `/napi/einrichtung` names it. */
data class Modul(
    val name: String,
    val titel: String,
    /** The app's key for its picture ([Bilder]); an unknown one gets the default. */
    val icon: String,
    /** Its page in Homes or its service's address, https only; null when it has none. */
    val web: String?,
    /** Its own phone app; null for a module that is only on the web. */
    val app: App?,
    val aufgabe: Aufgabe?,
)

/**
 * The person's modules and setup tasks (`GET /napi/einrichtung`, owner
 * 2026-11-04): what the start screen offers to open, the tasks still open,
 * and where the launcher's shortcuts lead. Nothing is taken on a guess: an
 * unknown field is ignored, an address that is not https is none.
 */
class Einrichtung(val offen: Int, val module: List<Modul>, val ziele: Map<String, String>) {

    /** „Öffnen“: of [OEFFNEN], in that order, each one the box sends with an address. */
    val oeffnen: List<Modul> get() = OEFFNEN.mapNotNull { n -> module.firstOrNull { it.name == n && it.web != null } }

    /** The open setup tasks: a done one leaves the list, and then the phone's own app icon is the way in. */
    val aufgaben: List<Modul> get() = module.filter { it.aufgabe?.erledigt == false }

    /** The packages of the modules' apps, the ones the phone may tell Homes it has. */
    val pakete: Set<String> get() = module.mapNotNull { it.app?.paket }.toSet()

    /**
     * What to tell Homes the phone has, by [hat]: every module app installed,
     * sorted; empty while none of them is an open task's (nothing to tick, no request).
     */
    fun installiert(hat: (String) -> Boolean): List<String> {
        val da = pakete.filter(hat).sorted()
        return if (aufgaben.any { it.app?.paket in da }) da else emptyList()
    }

    companion object {
        /**
         * What „Öffnen“ shows (owner, 2026-11-04): the whole list was too long — on a box
         * some fifteen modules, most of them in the Homes app itself, among the phone's
         * apps, or in their own apps. These only: Dokumente, the Zeitleiste
         * (standort) and Musik verwalten (Gesundheit was the fourth until the
         * owner removed it, 2026-11-07).
         */
        val OEFFNEN = listOf("dokumente", "standort", "musik")

        /** The launcher's targets the app knows: Einkaufsliste, Zeitleiste, Dokument scannen. */
        private val ZIELE = listOf(EinrichtungJson.EINKAUF, EinrichtungJson.ZEITLEISTE, EinrichtungJson.SCANNEN)

        /** An answer; null when it is none (not JSON, no version 1). */
        fun lesen(body: String?): Einrichtung? {
            val o = HomesHttp.json(body) ?: return null
            if (o.optInt(KannJson.VERSION, 0) < 1) return null
            val module = o.optJSONArray(KannJson.MODULE)
            val ziele = o.optJSONObject(EinrichtungJson.ZIELE)
            return Einrichtung(
                offen = o.optInt(EinrichtungJson.OFFEN, 0).coerceAtLeast(0),
                module = (0 until (module?.length() ?: 0)).mapNotNull { modul(module?.optJSONObject(it)) },
                ziele = ZIELE.mapNotNull { k -> https(HomesHttp.text(ziele, k))?.let { k to it } }.toMap(),
            )
        }

        private fun modul(o: JSONObject?): Modul? {
            val name = HomesHttp.text(o, EinrichtungModulJson.NAME) ?: return null
            val app = o?.optJSONObject(KannJson.APP)
            val paket = HomesHttp.text(app, EinrichtungModulJson.PAKET)
            val a = o?.optJSONObject(EinrichtungModulJson.AUFGABE)
            return Modul(
                name = name,
                titel = HomesHttp.text(o, EinrichtungModulJson.TITEL) ?: name,
                icon = HomesHttp.text(o, EinrichtungModulJson.ICON) ?: name,
                web = https(HomesHttp.text(o, EinrichtungModulJson.WEB)),
                app = if (o?.optBoolean(EinrichtungModulJson.NUR_WEB, false) == true || paket == null) {
                    null
                } else {
                    App(HomesHttp.text(app, EinrichtungModulJson.NAME) ?: paket, paket, https(HomesHttp.text(app, EinrichtungModulJson.STORE)))
                },
                aufgabe = a?.let {
                    Aufgabe(
                        titel = HomesHttp.text(it, EinrichtungModulJson.TITEL) ?: name,
                        erledigt = it.optBoolean(EinrichtungModulJson.ERLEDIGT, true),
                        einrichten = https(HomesHttp.text(it, EinrichtungModulJson.EINRICHTEN)),
                    )
                },
            )
        }

        /** [raw] when it is an https address with a host; anything else (http, a scheme of its own) is none. */
        fun https(raw: String?): String? = raw?.trim()?.takeIf {
            it.startsWith("https://", ignoreCase = true) && it.length > "https://".length && it.none(Char::isWhitespace)
        }
    }
}
