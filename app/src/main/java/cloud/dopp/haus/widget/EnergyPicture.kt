package cloud.dopp.haus.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The energy widget's picture (owner, 2026-11-03: „Grafiken könnten helfen es
 * besser zu verstehen. Und ein aktueller Akkustand. Vielleicht ähnlich dem
 * zweiten Bild“ — the Energie page's flow): Solar on top, Netz left, Akku
 * right with how full it is as a ring, Haus below; the ways the power takes
 * now drawn thick in their colour with an arrow, the others as a quiet dotted
 * track. Haus's colours and words (internal/pages/energie_fluss.go,
 * energie_verlauf.go), no animation.
 *
 * A widget cannot host a view of its own, so the picture is a bitmap drawn
 * with Canvas at the widget's size and the screen's density, and handed over
 * shared (immutable), so a large one travels as shared memory, not inside
 * the binder call.
 */
object EnergyPicture {
    // Haus's dark palette (family.go, energie_verlauf.go): the widgets are dark.
    const val SOLAR = 0xFFE3B341.toInt()
    const val NETZ = 0xFF4F8FF7.toInt()
    const val AKKU = 0xFFA78BFA.toInt()
    const val HAUS = 0xFFF97316.toInt()
    const val EIGEN = 0xFFC27C0E.toInt()
    const val EINSP = 0xFF1FA592.toInt()
    const val BEZUG = 0xFF4F8FF7.toInt()
    private const val CARD = 0xFF14141A.toInt()
    private const val INK = 0xFFF2F2F5.toInt()
    private const val MUTED = 0xFF9AA0AE.toInt()
    private const val FAINT = 0xFF6B7280.toInt()
    private const val TRACK = 0x26FFFFFF

    /** A node's colour. */
    fun color(key: String): Int = when (key) {
        "solar" -> SOLAR
        "netz" -> NETZ
        "akku" -> AKKU
        else -> HAUS
    }

    /** A way's colour by its name (eigen, einsp, bezug, akku). */
    fun wayColor(farbe: String): Int = when (farbe) {
        "eigen" -> EIGEN
        "einsp" -> EINSP
        "bezug" -> BEZUG
        else -> AKKU
    }

    /** Where a node sits, in dp, and its radius. */
    data class Lage(val r: Float, val at: Map<String, Pair<Float, Float>>)

    /** The diamond in a [w]×[h] dp field: Solar on top, Netz left, Akku right, Haus below. Pure. */
    fun lage(w: Float, h: Float): Lage {
        val r = min(min(h * 0.165f, w * 0.14f), 34f).coerceAtLeast(12f)
        val ring = r * 0.14f
        val pad = 3f
        val cx = w / 2
        val spread = min(cx - r - ring - pad - 2f, r * 2.7f)
        return Lage(
            r,
            mapOf(
                "solar" to (cx to pad + r + 1f),
                "haus" to (cx to h - pad - r - 1f),
                "netz" to (cx - spread to h / 2),
                "akku" to (cx + spread to h / 2),
            ),
        )
    }

    /**
     * The flow picture at [wDp]×[hDp] dp and [density] px per dp. [f] null is
     * nothing read yet: the nodes dashed, „…“ in them.
     */
    fun flow(f: EnergyFluss?, wDp: Int, hDp: Int, density: Float): Bitmap {
        val (bmp, c) = canvas(wDp, hDp, density)
        val w = wDp.toFloat()
        val h = hDp.toFloat()
        val lage = lage(w, h)
        val r = lage.r
        val k = r / 42f // the page draws at r = 42
        val da = f?.da ?: EnergyFluss.KEYS.toSet()
        val at = lage.at.filterKeys { it in da }

        // the tracks first: every way the house could have, quiet and dotted
        val track = stroke(TRACK, 1.2f).apply { pathEffect = DashPathEffect(floatArrayOf(1.5f, 4f), 0f) }
        for ((von, nach) in TRACKS) {
            val a = at[von] ?: continue
            val z = at[nach] ?: continue
            val (p, q) = ends(a, z, r + 5 * k)
            c.drawLine(p.first, p.second, q.first, q.second, track)
        }
        // the ways the power takes now, as thick as they carry, an arrow saying where to
        val aktiv = f?.aktiv.orEmpty()
        val big = aktiv.maxOfOrNull { it.w } ?: 0.0
        for (way in aktiv) {
            val a = at[way.von] ?: continue
            val z = at[way.nach] ?: continue
            val (p, q) = ends(a, z, r + 5 * k)
            val width = ((2.5f + 9f * (way.w / big).toFloat()) * k).coerceAtLeast(2f)
            val col = wayColor(way.farbe)
            c.drawLine(p.first, p.second, q.first, q.second, stroke(col, width).apply { alpha = 97; strokeCap = Paint.Cap.ROUND })
            val dots = stroke(col, max(1.5f, width * 0.45f)).apply {
                strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(0.1f, 11f * k + 2f), 0f)
            }
            c.drawLine(p.first, p.second, q.first, q.second, dots)
            arrow(c, p, q, width, k, col)
        }
        // the nodes
        for ((key, p) in at) node(c, f, key, p, r, k, label = true)
        // Autarkie in the corner the diamond leaves below Netz, when it fits
        val autarkie = f?.autarkie
        val haus = lage.at.getValue("haus")
        val netz = lage.at.getValue("netz")
        val cornerW = haus.first - r - 6f
        val cornerH = h - (netz.second + r) - 6f
        if (autarkie != null && cornerW >= 46f && cornerH >= 24f) {
            val size = min(15f, cornerH - 11f)
            c.drawText("Autarkie", 2f, h - size - 4f, text(MUTED, 9f, bold = true).apply { textAlign = Paint.Align.LEFT })
            c.drawText(EnergyFluss.percent(autarkie * 100), 2f, h - 2f, text(INK, size, bold = true).apply { textAlign = Paint.Align.LEFT })
        }
        return shared(bmp)
    }

    /**
     * The reduced picture for a small widget: one node in a [sizeDp] square —
     * the battery with how full it is as a ring and in its middle when Haus
     * says it, else the grid with its number.
     */
    fun single(f: EnergyFluss?, sizeDp: Int, density: Float): Bitmap {
        val (bmp, c) = canvas(sizeDp, sizeDp, density)
        val s = sizeDp.toFloat()
        val r = s / 2f - s * 0.1f
        val k = r / 42f
        val key = singleKey(f)
        node(c, f, key, s / 2 to s / 2, r, k, label = false, chargeInside = true)
        return shared(bmp)
    }

    /** Which node the small picture shows: the battery when its charge is known, else the grid. Pure. */
    fun singleKey(f: EnergyFluss?): String = if (f?.charge != null) "akku" else "netz"

    private fun node(c: Canvas, f: EnergyFluss?, key: String, p: Pair<Float, Float>, r: Float, k: Float, label: Boolean, chargeInside: Boolean = false) {
        val (x, y) = p
        val known = f != null && key in f.known
        val col = color(key)
        c.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD })
        if (known) {
            c.drawCircle(x, y, r, stroke(col, max(1.6f, 3f * k)))
        } else {
            c.drawCircle(x, y, r, stroke(FAINT, max(1.2f, 2f * k)).apply { pathEffect = DashPathEffect(floatArrayOf(4f * k + 1, 4f * k + 1), 0f) })
        }
        val charge = f?.charge.takeIf { key == "akku" }
        if (charge != null) {
            // how full: a ring around it from the top, clockwise
            val rr = r + 5f * k + 1f
            val ring = stroke(col, max(2.5f, 5f * k)).apply { alpha = 140; strokeCap = Paint.Cap.ROUND }
            c.drawArc(RectF(x - rr, y - rr, x + rr, y + rr), -90f, (360f * charge / 100f).toFloat(), false, ring)
        }
        val (zahl, wort) = when {
            f == null -> "…" to ""
            chargeInside && charge != null -> EnergyFluss.percent(charge) to f.text(key).second
            else -> f.text(key)
        }
        val zahlPaint = fit(zahl, text(if (known || f == null) INK else FAINT, r * 0.5f, bold = true), r * 1.6f)
        val wortPaint = fit(wort, text(MUTED, r * 0.36f), r * 1.65f)
        val zahlY = if (wort.isEmpty()) y + zahlPaint.textSize * 0.35f else y + zahlPaint.textSize * 0.2f
        c.drawText(zahl, x, zahlY, zahlPaint)
        if (wort.isNotEmpty()) c.drawText(wort, x, zahlY + wortPaint.textSize * 1.25f, wortPaint)
        if (!label) return
        val name = f?.name(key) ?: NAMES.getValue(key)
        val namePaint = text(MUTED, max(8.5f, min(11f, r * 0.42f)), bold = true)
        when (key) {
            // Solar's name on its left, Haus's on its right: Akku's, above its ring, stays clear of both
            "solar" -> {
                namePaint.textAlign = Paint.Align.RIGHT
                c.drawText(name, x - r - 6f * k - 3f, y + namePaint.textSize * 0.35f, namePaint)
            }
            "haus" -> {
                namePaint.textAlign = Paint.Align.LEFT
                c.drawText(name, x + r + 6f * k + 3f, y + namePaint.textSize * 0.35f, namePaint)
            }
            else -> {
                val lift = if (charge != null) r + 5f * k + 1f + max(2.5f, 5f * k) / 2 else r
                c.drawText(name, x, y - lift - 4f, namePaint)
            }
        }
    }

    private val NAMES = mapOf("solar" to "Solar", "haus" to "Haus", "netz" to "Netz", "akku" to "Akku")

    /** The tracks the page draws, never Netz → Akku. */
    private val TRACKS = listOf("solar" to "haus", "solar" to "akku", "solar" to "netz", "akku" to "haus", "netz" to "haus")

    private fun ends(a: Pair<Float, Float>, z: Pair<Float, Float>, off: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val dx = z.first - a.first
        val dy = z.second - a.second
        val l = hypot(dx, dy)
        return (a.first + dx / l * off to a.second + dy / l * off) to (z.first - dx / l * off to z.second - dy / l * off)
    }

    private fun arrow(c: Canvas, a: Pair<Float, Float>, z: Pair<Float, Float>, width: Float, k: Float, col: Int) {
        val dx = z.first - a.first
        val dy = z.second - a.second
        val l = hypot(dx, dy)
        if (l < 1f) return
        val ux = dx / l
        val uy = dy / l
        val mx = (a.first + z.first) / 2
        val my = (a.second + z.second) / 2
        val long = (7f * k + width * 0.7f).coerceAtLeast(5f)
        val half = (4.5f * k + width * 0.55f).coerceAtLeast(3.5f)
        val tipX = mx + ux * long / 2
        val tipY = my + uy * long / 2
        val backX = mx - ux * long / 2
        val backY = my - uy * long / 2
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(backX - uy * half, backY + ux * half)
            lineTo(backX + uy * half, backY - ux * half)
            close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
    }

    /** A transparent bitmap of [wDp]×[hDp] dp and a canvas on it that draws in dp. */
    private fun canvas(wDp: Int, hDp: Int, density: Float): Pair<Bitmap, Canvas> {
        // no larger than about 2 MB of pixels: a launcher's widget host is no place for more
        val scale = min(density, kotlin.math.sqrt(2_000_000f / 4f / max(1, wDp * hDp)))
        val bmp = Bitmap.createBitmap(max(1, (wDp * scale).roundToInt()), max(1, (hDp * scale).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        return bmp to c
    }

    /** Immutable, and in shared memory where Android has it, so the widget update carries a handle, not the pixels. */
    private fun shared(b: Bitmap): Bitmap =
        if (Build.VERSION.SDK_INT >= 31) b.asShared() else b.copy(Bitmap.Config.ARGB_8888, false).also { b.recycle() }

    private fun stroke(col: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun text(col: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** [p] shrunk until [s] is no wider than [maxW]. */
    private fun fit(s: String, p: Paint, maxW: Float): Paint {
        while (p.textSize > 6f && p.measureText(s) > maxW) p.textSize *= 0.92f
        return p
    }
}
