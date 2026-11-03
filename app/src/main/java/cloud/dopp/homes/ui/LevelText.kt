package cloud.dopp.homes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A favorite's level in its row („60 %“), the button that opens its
 * [LevelSheet] (owner, 2026-11-03, homes#230): the accent colour with a
 * dotted underline, as on Homes' devices page. The row gives it a touch
 * target of at least 48 dp; the text stays the size of the line.
 */
class LevelText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        val d = resources.displayMetrics.density
        strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(1.5f * d, 2f * d), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (l.lineCount == 0) return
        dots.color = currentTextColor
        val y = totalPaddingTop + l.getLineBaseline(0) + 3f * resources.displayMetrics.density
        val x0 = totalPaddingLeft + l.getLineLeft(0)
        canvas.drawLine(x0, y, x0 + l.getLineWidth(0), y, dots)
    }
}
