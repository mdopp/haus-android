package cloud.dopp.homes.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * A screen's [root] keeps its own padding plus the system bars': nothing sits
 * under the clock or the navigation bar, on a notch or a gesture phone alike.
 */
fun padForSystemBars(root: View) {
    val top = root.paddingTop
    val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(v.paddingLeft, top + bars.top, v.paddingRight, bottom + bars.bottom)
        insets
    }
}
