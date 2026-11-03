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
 * zweiten Bild“ — the Energie page's flow; second round the same evening:
 * heavier type, ovals instead of small circles, spread across the width).
 * Solar on top, Netz at the left edge, Akku at the right edge with how full
 * it is as a ring around its oval, Haus below; each oval says its name in its
 * colour, its number and what it does now. Only the ways the power takes
 * now are drawn, thick in their colour with an arrow. Haus's colours and words (internal/pages/energie_fluss.go,
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

    /** Where the ovals sit, in dp: their size and each centre. */
    data class Lage(val pw: Float, val ph: Float, val at: Map<String, Pair<Float, Float>>) {
        /** An oval's bounds. */
        fun box(key: String): RectF = at.getValue(key).let { (x, y) -> RectF(x - pw / 2, y - ph / 2, x + pw / 2, y + ph / 2) }

        /** From an oval's centre to its border in the direction ([ux], [uy]), a unit vector. */
        fun reach(ux: Float, uy: Float): Float {
            val a = pw / 2
            val b = ph / 2
            return 1f / kotlin.math.sqrt((ux / a) * (ux / a) + (uy / b) * (uy / b))
        }
    }

    /** The ring's room around Akku's oval, and its stroke. */
    private const val RING = 5f
    private const val RING_W = 3f

    /** The side ovals keep the ring and its stroke inside the field. */
    private const val EDGE = RING + RING_W

    /** The air between an oval and the column beside it, so a way runs between. */
    private const val CLEAR = 8f

    /** A way's air off an oval's border. */
    private const val GAP = 3f

    /**
     * The diamond over a [w]×[h] dp field (owner, 2026-11-03: ovals, spread
     * over the width): Solar on top and Haus below in the middle column, Netz
     * at the left edge and Akku at the right edge between them, the three
     * columns [CLEAR] apart so every way is a straight line between two ovals'
     * nearest borders. Pure.
     */
    fun lage(w: Float, h: Float): Lage {
        val pw = (w - 2 * EDGE - 2 * CLEAR) / 3f
        val ph = minOf(h * 0.27f, 46f, pw / 1.2f)
        return Lage(
            pw, ph,
            mapOf(
                "solar" to (w / 2 to 3f + ph / 2),
                "haus" to (w / 2 to h - 3f - ph / 2),
                "netz" to (EDGE + pw / 2 to h / 2),
                "akku" to (w - EDGE - pw / 2 to h / 2),
            ),
        )
    }

    /** A way's air off [key]'s oval: Akku's ring takes its own room. */
    private fun off(key: String) = if (key == "akku") RING + RING_W / 2 + GAP else GAP

    /**
     * The way from [von] to [nach]: a straight line from the source oval's
     * border to the destination oval's, along the line between their centres;
     * it never enters an oval. Pure geometry.
     */
    fun way(von: String, nach: String, l: Lage): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val (ax, ay) = l.at.getValue(von)
        val (zx, zy) = l.at.getValue(nach)
        val d = hypot(zx - ax, zy - ay)
        val ux = (zx - ax) / d
        val uy = (zy - ay) / d
        val s = l.reach(ux, uy) + off(von)
        val e = l.reach(ux, uy) + off(nach)
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
     * nothing read yet: the ovals dashed, „…“ in them. Only the ways the power
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
        for (key in EnergyFluss.KEYS) if (key in da) oval(c, f, key, lage)
        return shared(bmp)
    }

    private fun oval(c: Canvas, f: EnergyFluss?, key: String, l: Lage) {
        val box = l.box(key)
        val (x, y) = l.at.getValue(key)
        val known = f != null && key in f.known
        val col = color(key)
        if (known) {
            c.drawOval(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; alpha = 46 })
            c.drawOval(box, stroke(col, 2.6f))
        } else {
            c.drawOval(box, stroke(FAINT, 2f).apply { pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f) })
        }
        val charge = f?.charge.takeIf { key == "akku" }
        if (charge != null) {
            // how full: a ring around the oval from the top, clockwise, centred on it
            val ring = RectF(box.left - RING, box.top - RING, box.right + RING, box.bottom + RING)
            c.drawOval(ring, stroke(col, RING_W).apply { alpha = 60 })
            c.drawArc(ring, -90f, (360f * charge / 100f).toFloat(), false, stroke(col, RING_W).apply { strokeCap = Paint.Cap.ROUND })
        }
        // three lines: the name (Akku with how full), the number, what it does now
        val (zahl, said) = if (f == null) "…" to "" else f.text(key)
        // the grid says where the power goes in its own words, „ins Netz“ / „aus dem Netz“, in its colour
        val phrase = key == "netz" && said.endsWith("Netz")
        val name = when {
            phrase -> said
            charge != null -> "Akku " + EnergyFluss.percent(charge)
            else -> NAMES.getValue(key)
        }
        val wort = if (phrase) "" else said
        val ph = l.ph
        fun widthAt(dy: Float) = l.pw * kotlin.math.sqrt((1f - (dy / (ph / 2)) * (dy / (ph / 2))).coerceAtLeast(0.05f)) - 6f
        val zahlP = fit(zahl, text(if (known || f == null) INK else FAINT, ph * 0.32f, 800), widthAt(ph * 0.12f))
        val nameP = fit(name, text(col, ph * 0.2f, 800), widthAt(ph * 0.27f))
        val wortP = fit(wort, text(SOFT, ph * 0.2f, 700), widthAt(ph * 0.3f))
        val zahlY = y + zahlP.textSize * 0.36f
        c.drawText(name, x, zahlY - zahlP.textSize * 0.95f, nameP)
        c.drawText(zahl, x, zahlY, zahlP)
        if (wort.isNotEmpty()) c.drawText(wort, x, zahlY + wortP.textSize * 1.2f, wortP)
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
