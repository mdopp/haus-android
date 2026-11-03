package cloud.dopp.homes.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import cloud.dopp.homes.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider

/**
 * A favorite's level in a bottom sheet (owner, 2026-11-03, as Homes' devices
 * page since homes#230): a slider on the row moved when the list was
 * scrolled, so the row only says its level and a tap on it opens this.
 *
 * The thing's name as the title, the level large, a full-width slider that
 * sends when the finger lets go, the quick [DeviceControls.Slider.steps] —
 * each one call, the sheet stays open — and „Fertig“. Fertig, back and a tap
 * outside close it. Every call goes through [send], the device receiver's
 * way, so Homes' rule still decides it.
 */
class LevelSheet(
    context: Context,
    /** The entity this sheet sets; the screen hands it fresh levels by this. */
    val entityId: String,
    name: String,
    private var level: DeviceControls.Slider,
    private val send: (service: String, data: Map<String, Any>?) -> Unit,
) {
    val dialog = BottomSheetDialog(context, R.style.Theme_Homes_Sheet)

    private val value: TextView
    private val slider: Slider
    private var tracking = false

    init {
        val v = LayoutInflater.from(dialog.context).inflate(R.layout.sheet_level, null, false)
        v.findViewById<TextView>(R.id.sheet_title).text = name
        v.findViewById<TextView>(R.id.sheet_label).text = level.label
        value = v.findViewById(R.id.sheet_value)
        slider = v.findViewById(R.id.sheet_slider)
        slider.contentDescription = "$name ${level.label}"
        slider.setLabelFormatter { StartDevices.percent(it.toInt()) }
        slider.addOnChangeListener { _, at, fromUser ->
            show(at.toInt())
            // a screen reader or a key moves it without a touch: send at once
            if (fromUser && !tracking) pick(at.toInt())
        }
        slider.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) {
                    tracking = true
                }

                override fun onStopTrackingTouch(s: Slider) {
                    tracking = false
                    pick(s.value.toInt())
                }
            },
        )
        val steps = v.findViewById<LinearLayout>(R.id.sheet_steps)
        val gap = context.resources.getDimensionPixelSize(R.dimen.space_xs)
        level.steps.forEachIndexed { i, step ->
            val b = Button(ContextThemeWrapper(context, R.style.Widget_Homes_Button_Step), null, 0)
            b.text = step.label
            b.contentDescription = "${level.label} ${step.label}"
            b.setOnClickListener {
                set(step.value)
                pick(step.value)
            }
            steps.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = gap },
            )
        }
        if (level.steps.isEmpty()) steps.visibility = android.view.View.GONE
        v.findViewById<Button>(R.id.sheet_done).setOnClickListener { dialog.dismiss() }
        set(level.value)
        dialog.setContentView(v)
        dialog.setCanceledOnTouchOutside(true)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun show() = dialog.show()

    /** Homes' fresh level for this thing; kept while the finger is on the slider, and when the thing has none now (a lamp just turned off). */
    fun refresh(fresh: DeviceControls.Slider?) {
        if (fresh == null || tracking) return
        level = fresh
        set(fresh.value)
    }

    private fun set(v: Int) {
        slider.value = v.coerceIn(0, 100).toFloat()
        show(v)
    }

    private fun show(v: Int) {
        value.text = StartDevices.percent(v)
    }

    private fun pick(v: Int) {
        val (service, data) = level.call(v)
        send(service, data)
    }
}
