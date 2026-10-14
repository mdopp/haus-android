package cloud.dopp.haus.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas → Bitmap helpers for widgets. RemoteViews can't draw charts directly,
 * so trends are rendered to a Bitmap and set via `setImageViewBitmap`.
 */
object ChartRenderer {

    /**
     * Hard cap on a chart bitmap edge. RemoteViews ship the bitmap across a
     * bounded Binder transaction; an oversized bitmap makes the host reject the
     * update ("Widget kann nicht geladen werden", #32). Clamp every edge here.
     */
    const val MAX_EDGE = 640

    /** A filled sparkline of [points] (chronological). Empty bitmap if <2 points. */
    fun sparkline(
        widthPx: Int,
        heightPx: Int,
        points: List<Float>,
        lineColor: Int,
        fillColor: Int,
    ): Bitmap {
        val w = min(MAX_EDGE, max(1, widthPx))
        val h = min(MAX_EDGE, max(1, heightPx))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (points.size < 2) return bmp
        val c = Canvas(bmp)

        val minV = points.min()
        val maxV = points.max()
        val range = (maxV - minV).let { if (it == 0f) 1f else it }
        val pad = h * 0.12f

        fun px(i: Int) = i.toFloat() / (points.size - 1) * w
        fun py(v: Float) = h - pad - (v - minV) / range * (h - 2 * pad)

        val line = Path()
        val fill = Path()
        points.forEachIndexed { i, v ->
            val x = px(i); val y = py(v)
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, h.toFloat()); fill.lineTo(x, y)
            } else {
                line.lineTo(x, y); fill.lineTo(x, y)
            }
        }
        fill.lineTo(w.toFloat(), h.toFloat())
        fill.close()

        c.drawPath(fill, Paint().apply {
            color = fillColor; style = Paint.Style.FILL; isAntiAlias = true
        })
        c.drawPath(line, Paint().apply {
            color = lineColor; style = Paint.Style.STROKE
            strokeWidth = max(2f, h * 0.03f); isAntiAlias = true
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        return bmp
    }

    /** One line series with its color, for [multiLine]. */
    data class Series(val points: List<Float>, val color: Int)

    /**
     * A multi-line chart (PV/Haus/Netz/Akku) sharing one vertical scale, on a
     * transparent bitmap for the Verlauf widget. Each series is drawn with its own
     * color; a faint zero baseline is added when the data crosses zero.
     */
    fun multiLine(widthPx: Int, heightPx: Int, series: List<Series>): Bitmap {
        val w = min(MAX_EDGE, max(1, widthPx))
        val h = min(MAX_EDGE, max(1, heightPx))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val drawable = series.filter { it.points.size >= 2 }
        if (drawable.isEmpty()) return bmp
        val c = Canvas(bmp)

        val all = drawable.flatMap { it.points }
        val minV = all.min()
        val maxV = all.max()
        val range = (maxV - minV).let { if (it == 0f) 1f else it }
        val pad = h * 0.10f

        fun py(v: Float) = h - pad - (v - minV) / range * (h - 2 * pad)

        // Faint zero baseline when the scale straddles zero.
        if (minV < 0f && maxV > 0f) {
            c.drawLine(0f, py(0f), w.toFloat(), py(0f), Paint().apply {
                color = 0x33FFFFFF; strokeWidth = max(1f, h * 0.008f)
            })
        }

        val stroke = max(2f, h * 0.025f)
        for (s in drawable) {
            val n = s.points.size
            val path = Path()
            s.points.forEachIndexed { i, v ->
                val x = i.toFloat() / (n - 1) * w
                val y = py(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, Paint().apply {
                color = s.color; style = Paint.Style.STROKE
                strokeWidth = stroke; isAntiAlias = true
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            })
        }
        return bmp
    }
}
