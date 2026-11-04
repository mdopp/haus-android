package cloud.dopp.homes.hochladen

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What a file goes to: Homes' music (`POST /napi/musik`) or the Standortverlauf (`/napi/standort/import`). */
enum class Ziel { MUSIK, STANDORT }

/** One file waiting in the Hochladen job: the app's own copy at [datei], under the phone's [name] for it. */
data class Auftrag(val id: String, val ziel: Ziel, val datei: String, val name: String, val groesse: Long)

/**
 * The Hochladen job's queue (owner, 2026-11-04): the files shared to it, in
 * order, each the app's own copy until Homes has it. Kept in the app's
 * preferences, so a killed process or a reboot keeps the queue.
 */
object Auftraege {
    private const val PREFS = "homes_hochladen"
    private const val KEY = "auftraege"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun alle(ctx: Context): List<Auftrag> = lesen(p(ctx).getString(KEY, null))

    @Synchronized
    fun hinzu(ctx: Context, a: Auftrag) = p(ctx).edit { putString(KEY, schreiben(alle(ctx) + a)) }

    @Synchronized
    fun weg(ctx: Context, id: String) = p(ctx).edit { putString(KEY, schreiben(alle(ctx).filter { it.id != id })) }

    fun schreiben(l: List<Auftrag>): String = JSONArray(
        l.map { JSONObject().put("id", it.id).put("ziel", it.ziel.name).put("datei", it.datei).put("name", it.name).put("groesse", it.groesse) },
    ).toString()

    /** The kept queue; what does not read is none. */
    fun lesen(raw: String?): List<Auftrag> = runCatching {
        val a = JSONArray(raw.orEmpty())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ziel = Ziel.values().firstOrNull { it.name == o.optString("ziel") } ?: return@mapNotNull null
            Auftrag(o.optString("id"), ziel, o.optString("datei"), o.optString("name"), o.optLong("groesse", -1L))
                .takeIf { it.id.isNotBlank() && it.datei.isNotBlank() && it.name.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}

/**
 * „Zur Musik hinzufügen“ (owner, 2026-11-04): what Homes' music takes —
 * songs and ZIPs, by the file's name (homes `internal/musik`); a file
 * manager that says `octet-stream` is taken by its name too.
 */
object MusikDatei {
    val ENDUNGEN = listOf(".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".zip")
    private val ZIP = setOf("application/zip", "application/x-zip-compressed", "application/x-zip")

    /** Does a file of [name] and [mime] go to the music? The name decides when there is one. */
    fun nimmt(name: String?, mime: String?): Boolean {
        val n = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (n.isNotEmpty()) return ENDUNGEN.any { n.endsWith(it) }
        val m = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return m.startsWith("audio/") || m in ZIP
    }

    /** The name Homes gets: the file's own, else one its [mime] says, as Homes tells the kind by the name. */
    fun name(name: String?, mime: String?, n: Int): String {
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val m = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val endung = when {
            m in ZIP -> "zip"
            m == "audio/flac" || m == "audio/x-flac" -> "flac"
            m == "audio/ogg" -> "ogg"
            m == "audio/opus" -> "opus"
            m.startsWith("audio/wav") || m == "audio/x-wav" -> "wav"
            m == "audio/mp4" || m == "audio/x-m4a" || m == "audio/aac" -> "m4a"
            else -> "mp3"
        }
        return "Musik-$n.$endung"
    }
}
