package cloud.dopp.homes.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The energy widget's picture (owner, 2026-11-03: „Grafiken könnten helfen es
 * besser zu verstehen. Und ein aktueller Akkustand. Vielleicht ähnlich dem
 * zweiten Bild“ — the Energie page's flow; second round the same evening:
 * heavier type, wide low pills with round ends instead of small circles, spread across the width).
 * Solar on top, Netz at the left edge, Akku at the right edge with how full
 * it is as a ring around its pill, Haus below; each pill says its name in its
 * colour, its number and what it does now. Only the ways the power takes
 * now are drawn, thick in their colour with an arrow. Homes' colours and words (internal/pages/energie_fluss.go,
 * energie_verlauf.go), no animation.
 *
 * A widget cannot host a view of its own, so the picture is a bitmap drawn
 * with Canvas at the widget's size and the screen's density, and handed over
 * shared (immutable), so a large one travels as shared memory, not inside
 * the binder call.
 */
object EnergyPicture {
    // Homes' dark palette (family.go, energie_verlauf.go): the widgets are dark.
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
    data class Lage(val pw: Float, val ph: Float, val at: Map<String, Pair<Float, Float>>) {
        /** A pill's bounds. */
        fun box(key: String): RectF = at.getValue(key).let { (x, y) -> RectF(x - pw / 2, y - ph / 2, x + pw / 2, y + ph / 2) }

        /**
         * From a pill's centre to its border in the direction ([ux], [uy]), a
         * unit vector: a stadium, the flat sides and the half-circle ends.
         */
        fun reach(ux: Float, uy: Float, m: Float = 0f): Float {
            val r = ph / 2 + m
            val flat = pw / 2 - ph / 2
            val ax = abs(ux)
            val ay = abs(uy)
            if (ay > 1e-4f && ax * (r / ay) <= flat) return r / ay
            // through the round end: |t·u − (flat, 0)| = r
            return flat * ax + sqrt(max(0f, flat * flat * ax * ax - flat * flat + r * r))
        }

        /** Is ([x], [y]) on the pill of [key], grown by [m]? */
        fun on(key: String, x: Float, y: Float, m: Float = 0f): Boolean {
            val (cx, cy) = at.getValue(key)
            val r = ph / 2 + m
            val dx = max(abs(x - cx) - (pw / 2 - ph / 2), 0f)
            return hypot(dx, y - cy) <= r
        }

        /** Where the battery's „83 %“ sits: on the ring's top, right of the middle, clear of the ways. */
        fun chip(label: Float): RectF {
            val b = box("akku")
            val cx = b.centerX() + pw * 0.2f
            val h = ph * 0.26f * 1.35f
            val y = b.top - RING
            return RectF(cx - label / 2, y - h / 2, cx + label / 2, y + h / 2)
        }
    }

    /** The ring's room around Akku's pill, and its stroke. */
    private const val RING = 5f
    private const val RING_W = 3f

    /** The side pills keep the ring and its stroke inside the field. */
    private const val EDGE = RING + RING_W

    /** Between Netz and Akku, room for a way across. */
    private const val ACROSS = 27f

    /** A way's air off a pill's border. */
    private const val GAP = 3f

    /** The six ways the power can take. */
    val WAYS = listOf("solar" to "haus", "solar" to "akku", "solar" to "netz", "netz" to "haus", "akku" to "haus", "netz" to "akku")

    /**
     * The diamond over a [w]×[h] dp field (owner, 2026-11-03: wide, low pills
     * with round ends, spread over the width): Solar on top and Haus below in
     * the middle, Netz at the left edge and Akku at the right edge between
     * them. As high as the field lets every way between two pills keep room
     * for its line and its head. Pure.
     */
    fun lage(w: Float, h: Float): Lage {
        // all four the same width: as wide as Netz and Akku side by side allow,
        // no wider than the second line wants at its full size
        val widest = min((w - 2 * EDGE - ACROSS) / 2f, LINE_MAX * LONGEST_EM + 0.5f * 54f)
        val need = arrowSize(6f).first + 5f
        // a racetrack: at most half as high as wide; lower when a way needs the
        // room, and only in a flat field narrower too
        var pw = widest
        while (pw >= 30f) {
            var ph = minOf(pw / 2f, h * 0.3f, 54f)
            while (ph >= 18f) {
                val l = place(w, h, pw, ph)
                if (WAYS.all { (a, z) -> length(a, z, l) >= need }) return l
                ph -= 1f
            }
            pw -= 4f
        }
        return place(w, h, widest, 18f)
    }

    /** The second line's full size, sp (coordinator, 2026-11-03: about 13–14 bold). */
    const val LINE_MAX = 13.5f

    /** The widest second line, „Haus verbraucht“, in em at weight 800 (96 dp at 13 sp). */
    private const val LONGEST_EM = 96f / 13f

    /**
     * The second lines' one size in [l]: the largest at which „Haus
     * verbraucht“ fits a pill, at most [LINE_MAX] — the same in all four.
     */
    fun lineSize(l: Lage): Float = minOf(LINE_MAX, (l.pw - 0.5f * l.ph) / LONGEST_EM, l.ph * 0.34f)

    private fun place(w: Float, h: Float, pw: Float, ph: Float) = Lage(
        pw, ph,
        mapOf(
            "solar" to (w / 2 to 3f + ph / 2),
            "haus" to (w / 2 to h - 3f - ph / 2),
            "netz" to (EDGE + pw / 2 to h / 2),
            "akku" to (w - EDGE - pw / 2 to h / 2),
        ),
    )

    private fun length(a: String, z: String, l: Lage): Float = way(a, z, l).let { (p, q) -> hypot(q.first - p.first, q.second - p.second) }

    /** A way's air off [key]'s pill: Akku's ring takes its own room. */
    private fun off(key: String) = if (key == "akku") RING + RING_W / 2 + GAP else GAP

    /**
     * The way from [von] to [nach]: a straight line from the source pill's
     * border to the destination pill's, along the line between their centres;
     * it never enters a pill. Pure geometry.
     */
    fun way(von: String, nach: String, l: Lage): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val (ax, ay) = l.at.getValue(von)
        val (zx, zy) = l.at.getValue(nach)
        val d = hypot(zx - ax, zy - ay)
        val ux = (zx - ax) / d
        val uy = (zy - ay) / d
        // the pill grown by the air (and Akku's ring): a shallow way keeps it too
        val s = l.reach(ux, uy, off(von))
        val e = l.reach(ux, uy, off(nach))
        return (ax + ux * s to ay + uy * s) to (zx - ux * e to zy - uy * e)
    }

    /** The arrow's length along the way and its half width, by the way's width. */
    fun arrowSize(width: Float): Pair<Float, Float> = (5.5f + width * 0.6f) to (3.2f + width * 0.45f)

    /**
     * The arrowhead of a way from [p] to [q]: its tip at [q], the end by the
     * destination's border, pointing into it; then its two back corners.
     */
    fun head(p: Pair<Float, Float>, q: Pair<Float, Float>, width: Float): List<Pair<Float, Float>> {
        val d = hypot(q.first - p.first, q.second - p.second)
        val ux = (q.first - p.first) / d
        val uy = (q.second - p.second) / d
        val (long, half) = arrowSize(width)
        val bx = q.first - ux * long
        val by = q.second - uy * long
        return listOf(q, (bx - uy * half) to (by + ux * half), (bx + uy * half) to (by - ux * half))
    }

    /**
     * The flow picture at [wDp]×[hDp] dp and [density] px per dp. [f] null is
     * nothing read yet: the pills dashed, „…“ in them. Only the ways the power
     * takes now are drawn, each with its head at the destination.
     */
    fun flow(f: EnergyFluss?, wDp: Int, hDp: Int, density: Float): Bitmap {
        val (bmp, c) = canvas(wDp, hDp, density)
        val lage = lage(wDp.toFloat(), hDp.toFloat())
        val da = f?.da ?: EnergyFluss.KEYS.toSet()
        val aktiv = f?.aktiv.orEmpty()
        val big = aktiv.maxOfOrNull { it.w } ?: 0.0
        for (w in aktiv) {
            if (w.von !in da || w.nach !in da) continue
            val (p, q) = way(w.von, w.nach, lage)
            val width = 3f + 3f * (w.w / big).toFloat()
            val col = wayColor(w.farbe)
            val corners = head(p, q, width)
            // the line stops inside the head, so its round cap does not poke past the tip
            val (long, _) = arrowSize(width)
            val d = hypot(q.first - p.first, q.second - p.second)
            val t = ((d - long * 0.6f) / d).coerceAtLeast(0f)
            c.drawLine(p.first, p.second, p.first + (q.first - p.first) * t, p.second + (q.second - p.second) * t,
                stroke(col, width).apply { alpha = 170; strokeCap = Paint.Cap.ROUND })
            c.drawPath(Path().apply {
                moveTo(corners[0].first, corners[0].second)
                lineTo(corners[1].first, corners[1].second)
                lineTo(corners[2].first, corners[2].second)
                close()
            }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        }
        for (key in EnergyFluss.KEYS) if (key in da) pill(c, f, key, lage)
        return shared(bmp)
    }

    private fun pill(c: Canvas, f: EnergyFluss?, key: String, l: Lage) {
        val box = l.box(key)
        val (x, y) = l.at.getValue(key)
        val ph = l.ph
        val rad = ph / 2
        val known = f != null && key in f.known
        val col = color(key)
        if (known) {
            c.drawRoundRect(box, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; alpha = 46 })
            c.drawRoundRect(box, rad, rad, stroke(col, 2.6f))
        } else {
            c.drawRoundRect(box, rad, rad, stroke(FAINT, 2f).apply { pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f) })
        }
        val charge = f?.charge.takeIf { key == "akku" }
        if (charge != null) ring(c, RectF(box.left - RING, box.top - RING, box.right + RING, box.bottom + RING), charge, col)
        // two lines: the number, then the name in its colour and what it does now
        // („Akku entlädt“); the grid says where the power goes („ins Netz“)
        val (zahl, said) = if (f == null) "…" to "" else f.text(key)
        val name = NAMES.getValue(key)
        val parts: List<Pair<String, Int>> = when {
            key == "netz" && said.endsWith("Netz") -> listOf(said.removeSuffix("Netz") to SOFT, "Netz" to col)
            said.isEmpty() -> listOf(name to col)
            else -> listOf("$name " to col, said to SOFT)
        }
        val inner = l.pw - ph * 0.5f
        // one size for every second line, set by the longest (no shrinking per pill)
        val lineP = text(SOFT, lineSize(l), 800).apply { textAlign = Paint.Align.LEFT }
        val zahlP = fit(zahl, text(if (known || f == null) INK else FAINT, min(ph * 0.42f, lineP.textSize * 1.8f), 800), inner)
        val total = zahlP.textSize * 0.95f + ph * 0.05f + lineP.textSize * 0.95f
        val zahlY = y - total / 2 + zahlP.textSize * 0.8f
        c.drawText(zahl, x, zahlY, zahlP)
        val lineY = zahlY + ph * 0.05f + lineP.textSize * 0.98f
        var lx = x - parts.sumOf { lineP.measureText(it.first).toDouble() }.toFloat() / 2
        for ((t, cl) in parts) {
            lineP.color = cl
            c.drawText(t, lx, lineY, lineP)
            lx += lineP.measureText(t)
        }
        if (charge != null) chip(c, l, EnergyFluss.percent(charge), col)
    }

    /** The chip's text size and its width for [s]. */
    private fun chipPaint(ph: Float) = text(0xFF000000.toInt(), ph * 0.26f, 800)

    /** The width of the „83 %“ label, for [Lage.chip]. */
    fun chipWidth(s: String, ph: Float): Float = chipPaint(ph).measureText(s) + ph * 0.3f

    /** How full, in words, on the ring: a small filled label. */
    private fun chip(c: Canvas, l: Lage, s: String, col: Int) {
        val p = chipPaint(l.ph)
        val r = l.chip(chipWidth(s, l.ph))
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        c.drawText(s, r.centerX(), r.centerY() + p.textSize * 0.36f, p)
    }

    /** How full, as a ring around [box] (a stadium) from the top middle, clockwise. */
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
        c.drawPath(path, stroke(col, RING_W).apply { alpha = 60 })
        c.drawPath(part, stroke(col, RING_W).apply { strokeCap = Paint.Cap.ROUND })
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
