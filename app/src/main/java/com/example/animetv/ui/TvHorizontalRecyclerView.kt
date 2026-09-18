package com.example.animetv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * TV-optimized horizontal RecyclerView with strict focus containment (Netflix / Nuvio style).
 * Completely prevents horizontal D-pad focus from escaping to other rows or the top navigation bar.
 */
class TvHorizontalRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun focusSearch(focused: View, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        // If moving horizontally (LEFT or RIGHT) and the candidate view is outside of this RecyclerView,
        // clamp focus on the current view to prevent focus escaping across rows or into the header!
        if (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT) {
            if (result == null || !isDescendantOfThis(result)) {
                return focused
            }
        }
        return result
    }

    private fun isDescendantOfThis(view: View): Boolean {
        var current: Any? = view
        while (current is View) {
            if (current === this) return true
            current = current.parent
        }
        return false
    }
}
