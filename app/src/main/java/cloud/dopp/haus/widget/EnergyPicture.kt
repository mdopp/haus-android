package cloud.dopp.haus.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The energy widget's picture (owner, 2026-11-03: „Grafiken könnten helfen es
 * besser zu verstehen. Und ein aktueller Akkustand. Vielleicht ähnlich dem
 * zweiten Bild“ — the Energie page's flow; second round the same evening:
 * heavier type, pills instead of small circles, spread across the width).
 * Solar on top, Netz at the left edge, Akku at the right edge with how full
 * it is as a ring around its pill, Haus below; each pill says its name in its
 * colour, its number and what it does now. The ways the power takes now are
 * drawn thick in their colour with an arrow, the others as a quiet dotted
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
    private const val INK = 0xFFF2F2F5.toInt()
    private const val SOFT = 0xFFC9CDD6.toInt() // the words: lighter than ink, heavier than Haus's muted
    private const val FAINT = 0xFF6B7280.toInt()
    private const val TRACK = 0x33FFFFFF

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

    /** Where the pills sit, in dp: their size and each centre. */
    data class Lage(val pw: Float, val ph: Float, val at: Map<String, Pair<Float, Float>>)

    /** The ring's room around a pill. */
    private const val RING = 5f

    /** The air between the three rows, room for the ways and their arrows. */
    private const val ROW_GAP = 14f

    /**
     * The diamond spread over a [w]×[h] dp field in three rows: Solar on top
     * and Haus below in the middle, Netz at the left edge and Akku at the
     * right between them. The rows keep [ROW_GAP] apart, so the pills can be
     * wide. Pure.
     */
    fun lage(w: Float, h: Float): Lage {
        val ph = min(40f, (h - 2 * RING - 2 * ROW_GAP) / 3f).coerceAtLeast(24f)
        val pw = min(ph * 2.4f, (w - 2 * RING) / 2f - 8f).coerceAtLeast(ph)
        return Lage(
            pw, ph,
            mapOf(
                "solar" to (w / 2 to RING + ph / 2),
                "haus" to (w / 2 to h - RING - ph / 2),
                "netz" to (RING + pw / 2 to h / 2),
                "akku" to (w - RING - pw / 2 to h / 2),
            ),
        )
    }

    /**
     * The flow picture at [wDp]×[hDp] dp and [density] px per dp. [f] null is
     * nothing read yet: the pills dashed, „…“ in them.
     */
    fun flow(f: EnergyFluss?, wDp: Int, hDp: Int, density: Float): Bitmap {
        val (bmp, c) = canvas(wDp, hDp, density)
        val lage = lage(wDp.toFloat(), hDp.toFloat())
        val da = f?.da ?: EnergyFluss.KEYS.toSet()
        val at = lage.at.filterKeys { it in da }
        val gap = RING + 2f

        // the tracks first: every way the house could have, quiet and dotted
        val track = stroke(TRACK, 1.4f).apply { pathEffect = DashPathEffect(floatArrayOf(2f, 4f), 0f) }
        for ((von, nach) in TRACKS) {
            if (von in at && nach in at) c.drawPath(way(von, nach, lage, gap), track)
        }
        // the ways the power takes now, as thick as they carry, an arrow saying where to
        val aktiv = f?.aktiv.orEmpty()
        val big = aktiv.maxOfOrNull { it.w } ?: 0.0
        for (w in aktiv) {
            if (w.von !in at || w.nach !in at) continue
            val path = way(w.von, w.nach, lage, gap)
            val width = 3f + 4f * (w.w / big).toFloat()
            val col = wayColor(w.farbe)
            c.drawPath(path, stroke(col, width).apply { alpha = 150; strokeCap = Paint.Cap.ROUND })
            arrow(c, path, width, col)
        }
        for ((key, p) in at) pill(c, f, key, p, lage.pw, lage.ph)
        return shared(bmp)
    }

    private fun pill(c: Canvas, f: EnergyFluss?, key: String, p: Pair<Float, Float>, pw: Float, ph: Float) {
        val (x, y) = p
        val known = f != null && key in f.known
        val col = color(key)
        val box = RectF(x - pw / 2, y - ph / 2, x + pw / 2, y + ph / 2)
        val rad = ph / 2
        if (known) {
            c.drawRoundRect(box, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; alpha = 46 })
            c.drawRoundRect(box, rad, rad, stroke(col, 2.6f))
        } else {
            c.drawRoundRect(box, rad, rad, stroke(FAINT, 2f).apply { pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f) })
        }
        val charge = f?.charge.takeIf { key == "akku" }
        if (charge != null) ring(c, RectF(box.left - RING, box.top - RING, box.right + RING, box.bottom + RING), charge, col)
        // two lines: the number, then who does what now — „Netz speist ein“
        val (zahl, wort) = if (f == null) "…" to "" else f.text(key)
        val name = NAMES.getValue(key)
        val inner = pw - ph * 0.5f
        val zahlP = fit(zahl, text(if (known || f == null) INK else FAINT, ph * 0.4f, 800), inner)
        val nameP = text(col, ph * 0.27f, 800)
        val wortP = text(SOFT, ph * 0.27f, 700)
        // the second line shrinks as one, name and word alike
        while (nameP.textSize > 6f && nameP.measureText("$name ") + wortP.measureText(wort) > inner) {
            nameP.textSize *= 0.93f
            wortP.textSize = nameP.textSize
        }
        val total = zahlP.textSize * 0.95f + ph * 0.06f + nameP.textSize * 0.95f
        val zahlY = y - total / 2 + zahlP.textSize * 0.82f
        c.drawText(zahl, x, zahlY, zahlP)
        val lineY = zahlY + ph * 0.06f + nameP.textSize * 0.98f
        val nameW = nameP.measureText(if (wort.isEmpty()) name else "$name ")
        val start = x - (nameW + wortP.measureText(wort)) / 2
        nameP.textAlign = Paint.Align.LEFT
        wortP.textAlign = Paint.Align.LEFT
        c.drawText(if (wort.isEmpty()) name else "$name ", start, lineY, nameP)
        if (wort.isNotEmpty()) c.drawText(wort, start + nameW, lineY, wortP)
        if (charge != null) chip(c, x, box.top - RING, EnergyFluss.percent(charge), col, ph)
    }

    /** How full, in words, on the ring's start: a small filled label at the pill's top. */
    private fun chip(c: Canvas, x: Float, y: Float, s: String, col: Int, ph: Float) {
        val p = text(0xFF000000.toInt(), ph * 0.26f, 800)
        val w = p.measureText(s) + ph * 0.3f
        val h = p.textSize * 1.35f
        c.drawRoundRect(RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), h / 2, h / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        c.drawText(s, x, y + p.textSize * 0.36f, p)
    }

    /** How full, as a ring around [box] from the top middle, clockwise. */
    private fun ring(c: Canvas, box: RectF, pct: Double, col: Int) {
        val r = box.height() / 2
        val cx = box.centerX()
        val path = Path().apply {
            moveTo(cx, box.top)
            lineTo(box.right - r, box.top)
            arcTo(RectF(box.right - 2 * r, box.top, box.right, box.bottom), -90f, 180f)
            lineTo(box.left + r, box.bottom)
            arcTo(RectF(box.left, box.top, box.left + 2 * r, box.bottom), 90f, 180f)
            lineTo(cx, box.top)
        }
        val m = PathMeasure(path, false)
        val part = Path()
        m.getSegment(0f, m.length * (pct / 100.0).toFloat().coerceIn(0f, 1f), part, true)
        c.drawPath(path, stroke(col, 3f).apply { alpha = 40 })
        c.drawPath(part, stroke(col, 3f).apply { strokeCap = Paint.Cap.ROUND })
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
        val key = singleKey(f)
        val x = s / 2
        val known = f != null && key in f.known
        val col = color(key)
        c.drawCircle(x, x, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; alpha = 46 })
        c.drawCircle(x, x, r, if (known) stroke(col, 2.6f) else stroke(FAINT, 2f).apply { pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f) })
        val charge = f?.charge.takeIf { key == "akku" }
        if (charge != null) {
            val rr = r + 4f
            c.drawCircle(x, x, rr, stroke(col, 3f).apply { alpha = 40 })
            c.drawArc(RectF(x - rr, x - rr, x + rr, x + rr), -90f, (360f * charge / 100f).toFloat(), false, stroke(col, 3f).apply { strokeCap = Paint.Cap.ROUND })
        }
        val (zahl, wort) = when {
            f == null -> "…" to ""
            charge != null -> EnergyFluss.percent(charge) to f.text(key).second
            else -> f.text(key)
        }
        val zahlP = fit(zahl, text(if (known || f == null) INK else FAINT, r * 0.5f, 800), r * 1.6f)
        val wortP = fit(wort, text(SOFT, r * 0.34f, 600), r * 1.6f)
        val zahlY = if (wort.isEmpty()) x + zahlP.textSize * 0.35f else x + zahlP.textSize * 0.2f
        c.drawText(zahl, x, zahlY, zahlP)
        if (wort.isNotEmpty()) c.drawText(wort, x, zahlY + wortP.textSize * 1.2f, wortP)
        return shared(bmp)
    }

    /** Which node the small picture shows: the battery when its charge is known, else the grid. Pure. */
    fun singleKey(f: EnergyFluss?): String = if (f?.charge != null) "akku" else "netz"

    private val NAMES = mapOf("solar" to "Solar", "haus" to "Haus", "netz" to "Netz", "akku" to "Akku")

    /** The tracks the page draws, never Netz → Akku. */
    private val TRACKS = listOf("solar" to "haus", "solar" to "akku", "solar" to "netz", "akku" to "haus", "netz" to "haus")

    /**
     * The way from [von] to [nach] between their pills, [gap] off them.
     * Solar and Haus share the middle column: a straight line down; Netz and
     * Akku share the middle row: a straight line across. Between a side pill
     * and the column an elbow: out of the side pill's top or bottom, near its
     * inner end, curving into the middle pill's flank — long enough to see,
     * and clear of the other pills.
     */
    private fun way(von: String, nach: String, l: Lage, gap: Float): Path {
        val a = l.at.getValue(von)
        val z = l.at.getValue(nach)
        val path = Path()
        val middle = setOf("solar", "haus")
        when {
            von in middle && nach in middle -> {
                val dir = if (z.second > a.second) 1 else -1
                path.moveTo(a.first, a.second + dir * (l.ph / 2 + gap))
                path.lineTo(z.first, z.second - dir * (l.ph / 2 + gap))
            }
            von !in middle && nach !in middle -> {
                val dir = if (z.first > a.first) 1 else -1
                path.moveTo(a.first + dir * (l.pw / 2 + gap), a.second)
                path.lineTo(z.first - dir * (l.pw / 2 + gap), z.second)
            }
            else -> {
                val side = if (von in middle) z else a
                val mid = if (von in middle) a else z
                val inward = if (mid.first > side.first) 1 else -1
                val vertical = if (mid.second > side.second) 1 else -1
                val sidePt = side.first + inward * l.pw * 0.3f to side.second + vertical * (l.ph / 2 + gap)
                val midPt = mid.first - inward * (l.pw / 2 + gap) to mid.second
                val (p, q) = if (von in middle) midPt to sidePt else sidePt to midPt
                path.moveTo(p.first, p.second)
                path.quadTo(sidePt.first, midPt.second, q.first, q.second)
            }
        }
        return path
    }

    /** An arrow at the middle of [path], pointing along it. */
    private fun arrow(c: Canvas, path: Path, width: Float, col: Int) {
        val m = PathMeasure(path, false)
        if (m.length < 4f) return
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        m.getPosTan(m.length / 2, pos, tan)
        val ux = tan[0]
        val uy = tan[1]
        val long = 7f + width * 0.7f
        val half = 4.5f + width * 0.5f
        val (mx, my) = pos[0] to pos[1]
        val arrowPath = Path().apply {
            moveTo(mx + ux * long / 2, my + uy * long / 2)
            lineTo(mx - ux * long / 2 - uy * half, my - uy * long / 2 + ux * half)
            lineTo(mx - ux * long / 2 + uy * half, my - uy * long / 2 - ux * half)
            close()
        }
        c.drawPath(arrowPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
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

    /** Centred text of [weight] (owner: „zu dünn und filigran“ — 600 and up). */
    private fun text(col: Int, size: Float, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = weighted(weight)
    }

    private fun weighted(weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, weight, false)
        else if (weight >= 700) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** [p] shrunk until [s] is no wider than [maxW]. */
    private fun fit(s: String, p: Paint, maxW: Float): Paint {
        while (p.textSize > 6f && p.measureText(s) > maxW) p.textSize *= 0.92f
        return p
    }
}
