package cloud.dopp.homes.konto

import cloud.dopp.homes.data.Napi.FeedIcal
import cloud.dopp.homes.sicherung.Adresse
import cloud.dopp.homes.sicherung.Eintrag
import cloud.dopp.homes.sicherung.Kontakt

/**
 * The household's cards as Homes sends them (`/napi/gemeinsam/kontakte`,
 * #206): vCard 3.0 and 4.0 (RFC 2426, RFC 6350), as phones and Homes wrote
 * them into Radicale. Read into the app's own [Kontakt], the shape the
 * phone's address book takes. Lines unfolded, a group prefix (`item1.`)
 * kept apart (a URL takes the X-ABLabel of its group), text values unescaped, types lower case (`TYPE=CELL`,
 * `TYPE="home,voice"`, vCard 2.1's bare `;CELL`). A card without a UID is
 * left out: the phone could not tell it from the next. Pure.
 */
object VcardLeser {

    /** One card: what the phone keeps of it, its text, whose hash says whether it changed, and its version in Homes. */
    data class Karte(val kontakt: Kontakt, val text: String, val etag: String? = null)

    fun lies(text: String): List<Karte> {
        val out = ArrayList<Karte>()
        var zeilen: MutableList<String>? = null
        for (zeile in entfalte(text)) {
            val z = zeile.trim()
            when {
                z.equals("BEGIN:VCARD", ignoreCase = true) -> zeilen = ArrayList()
                z.equals("END:VCARD", ignoreCase = true) -> {
                    zeilen?.let { karte(it)?.let(out::add) }
                    zeilen = null
                }
                zeilen != null && z.isNotEmpty() -> zeilen.add(zeile)
            }
        }
        return out
    }

    /** The lines of [text] with their folds undone (a line starting with a space or tab goes on the one before). */
    fun entfalte(text: String): List<String> {
        val out = ArrayList<String>()
        for (roh in text.split("\r\n", "\n", "\r")) {
            if ((roh.startsWith(" ") || roh.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + roh.substring(1)
            } else {
                out.add(roh)
            }
        }
        return out
    }

    /** One content line: its name (upper case, no group), its parameters (names upper case), its raw value, its group (lower case). */
    data class Zeile(val name: String, val parameter: Map<String, List<String>>, val wert: String, val gruppe: String? = null)

    fun zeile(z: String): Zeile? {
        // the value starts at the first colon outside a quoted parameter value
        var quote = false
        var doppelpunkt = -1
        for (i in z.indices) {
            when (z[i]) {
                '"' -> quote = !quote
                ':' -> if (!quote) {
                    doppelpunkt = i
                    break
                }
            }
        }
        if (doppelpunkt <= 0) return null
        val kopf = teile(z.substring(0, doppelpunkt), ';', quoted = true)
        val name = kopf.first().substringAfterLast('.').trim().uppercase()
        val gruppe = kopf.first().substringBeforeLast('.', "").trim().lowercase().ifEmpty { null }
        val parameter = LinkedHashMap<String, MutableList<String>>()
        kopf.drop(1).forEach { p ->
            val gleich = p.indexOf('=')
            val (k, v) = if (gleich < 0) "TYPE" to p else p.substring(0, gleich).trim().uppercase() to p.substring(gleich + 1)
            val werte = teile(v, ',', quoted = true).map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            parameter.getOrPut(k) { ArrayList() }.addAll(werte)
        }
        return Zeile(name, parameter, z.substring(doppelpunkt + 1), gruppe)
    }

    private fun karte(zeilen: List<String>): Karte? {
        val gelesen = zeilen.mapNotNull(::zeile)
        val uid = gelesen.firstOrNull { it.name == "UID" }?.let { text(it.wert) }?.trim()?.ifEmpty { null } ?: return null
        var k = Kontakt(uid = uid)
        val labels = gelesen.filter { it.name == "X-ABLABEL" && it.gruppe != null }.associate { it.gruppe to text(it.wert).trim().ifEmpty { null } }
        for (z in gelesen) {
            val typen = (z.parameter["TYPE"] ?: emptyList()).flatMap { teile(it, ',', quoted = false) }
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "internet" }
            k = when (z.name) {
                "FN" -> k.copy(anzeigename = k.anzeigename ?: text(z.wert).ifBlank { null })
                "N" -> {
                    val n = komponenten(z.wert)
                    k.copy(
                        nachname = n.getOrNull(0), vorname = n.getOrNull(1), weitere = n.getOrNull(2),
                        praefix = n.getOrNull(3), suffix = n.getOrNull(4),
                    )
                }
                "TEL" -> text(z.wert).removePrefix("tel:").trim().ifEmpty { null }
                    ?.let { k.copy(telefone = k.telefone + Eintrag(it, typen)) } ?: k
                "EMAIL" -> text(z.wert).removePrefix("mailto:").trim().ifEmpty { null }
                    ?.let { k.copy(mails = k.mails + Eintrag(it, typen)) } ?: k
                "ADR" -> {
                    val a = komponenten(z.wert)
                    if (a.all { it == null }) {
                        k
                    } else {
                        k.copy(
                            adressen = k.adressen + Adresse(
                                typen = typen, postfach = a.getOrNull(0), zusatz = a.getOrNull(1), strasse = a.getOrNull(2),
                                ort = a.getOrNull(3), region = a.getOrNull(4), plz = a.getOrNull(5), land = a.getOrNull(6),
                            ),
                        )
                    }
                }
                "ORG" -> {
                    val o = komponenten(z.wert)
                    k.copy(firma = k.firma ?: o.getOrNull(0), abteilung = k.abteilung ?: o.getOrNull(1))
                }
                "TITLE" -> k.copy(titel = k.titel ?: text(z.wert).ifBlank { null })
                "BDAY" -> k.copy(geburtstag = k.geburtstag ?: geburtstag(z.wert))
                "NOTE" -> text(z.wert).ifBlank { null }?.let { k.copy(notiz = listOfNotNull(k.notiz, it).joinToString("\n")) } ?: k
                "URL" -> text(z.wert).trim().ifEmpty { null }?.let { k.copy(webseiten = k.webseiten + Eintrag(it, label = labels[z.gruppe], gruppe = z.gruppe)) } ?: k
                else -> k
            }
        }
        return Karte(k, zeilen.joinToString("\n"), gelesen.firstOrNull { it.name == FeedIcal.ETAG }?.let { text(it.wert).trim() })
    }

    /**
     * A birthday as Android's contacts keep one: `yyyy-MM-dd`, or `--MM-dd`
     * without a year; vCard's basic forms (`19500101`, `--0101`) become
     * those. Anything else is left out.
     */
    fun geburtstag(wert: String): String? {
        val v = wert.trim().substringBefore('T')
        Regex("""(\d{4})-?(\d{2})-?(\d{2})""").matchEntire(v)?.let { m ->
            val (j, mo, t) = m.destructured
            return "$j-$mo-$t"
        }
        Regex("""--(\d{2})-?(\d{2})""").matchEntire(v)?.let { m ->
            val (mo, t) = m.destructured
            return "--$mo-$t"
        }
        return null
    }

    /** A structured value's components (N, ADR, ORG), each unescaped; an empty one is null. */
    private fun komponenten(wert: String): List<String?> =
        teile(wert, ';', quoted = false, escaped = true).map { text(it).trim().ifEmpty { null } }

    /** A text value unescaped: `\n` a line break, `\,` `\;` `\\` the character. */
    fun text(wert: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < wert.length) {
            val c = wert[i]
            if (c == '\\' && i + 1 < wert.length) {
                val n = wert[i + 1]
                out.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** [s] split at [trenner], not inside quotes ([quoted]) nor after a backslash ([escaped]); the backslashes stay. */
    private fun teile(s: String, trenner: Char, quoted: Boolean, escaped: Boolean = false): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                escaped && c == '\\' && i + 1 < s.length -> {
                    cur.append(c).append(s[i + 1])
                    i += 2
                    continue
                }
                quoted && c == '"' -> {
                    quote = !quote
                    cur.append(c)
                }
                c == trenner && !quote -> {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
