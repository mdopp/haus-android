package cloud.dopp.haus.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cloud.dopp.haus.data.Napi.PersonaJson
import org.json.JSONObject

/**
 * The house persona (laib ADR 0020, laib#130): who the house is to the family
 * — "Joshua" — as Haus serves it at `GET /napi/persona`: its name, its two
 * colours, and an icon at `/napi/persona/icon.png` when it has one. The app
 * shows the name, the colours and the icon on its home screen.
 */
data class Persona(
    val display: String,
    val greeting: String,
    val accent: String,
    val ground: String = "",
    val hasIcon: Boolean = false,
) {
    companion object {
        /** Haus's answer, or null when it names nobody. */
        fun parse(o: JSONObject): Persona? {
            val display = o.optString(PersonaJson.DISPLAY).trim()
            if (display.isEmpty()) return null
            return Persona(
                display = display,
                greeting = o.optString(PersonaJson.GREETING).trim(),
                accent = o.optString(PersonaJson.ACCENT).trim(),
                ground = o.optString(PersonaJson.GROUND).trim(),
                hasIcon = o.optString(PersonaJson.ICON).trim().isNotEmpty(),
            )
        }
    }
}

/**
 * The persona's icon: the PNG Haus serves at `/napi/persona/icon.png`, which
 * Android draws without a library (haus#28). Kept small ([MAX_BYTES]) — it is
 * fetched, stored and decoded whole, and decoding runs off the main thread
 * (`HomeActivity`), so a large or broken file costs a missing icon, never a
 * frozen screen.
 */
object PersonaIcon {
    /** The largest icon the app takes; an icon is a few KB. */
    const val MAX_BYTES = 256 * 1024

    /** What every PNG starts with. */
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** [png] when it is a PNG within [MAX_BYTES], else null. */
    fun accept(png: ByteArray?): ByteArray? =
        png?.takeIf { it.size in SIGNATURE.size..MAX_BYTES && it.copyOf(SIGNATURE.size).contentEquals(SIGNATURE) }

    /** [png] as a bitmap, sampled down to at most [maxSide] px a side; null when it does not decode. */
    fun decode(png: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
