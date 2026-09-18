package com.example.animetv.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager

/**
 * TV-optimized horizontal LinearLayoutManager that enforces Focus Containment (Netflix / Nuvio style).
 * Prevents horizontal D-pad focus from escaping to other rows when reaching the first or last card.
 */
class TvRowLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val count = itemCount
        if (count > 0) {
            val itemView = findContainingItemView(focused)
            if (itemView != null) {
                val position = getPosition(itemView)
                // If moving right on the last item, trap focus on the same card (do not jump to other rows)
                if (direction == View.FOCUS_RIGHT && position >= count - 1) {
                    return focused
                }
                // If moving left on the first item, trap focus on the same card
                if (direction == View.FOCUS_LEFT && position <= 0) {
                    return focused
                }
            }
        }
        return super.onInterceptFocusSearch(focused, direction)
    }
}
