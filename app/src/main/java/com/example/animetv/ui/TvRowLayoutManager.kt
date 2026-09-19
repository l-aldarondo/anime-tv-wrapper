package com.example.animetv.ui

import android.content.Context
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * TV-optimized horizontal LinearLayoutManager that enforces Focus Containment (Netflix / Nuvio style).
 * Prevents horizontal D-pad focus from escaping to other rows when reaching the first or last card,
 * and guards against crashes during rapid remote scrolling.
 */
class TvRowLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {

    init {
        recycleChildrenOnDetach = false
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: Exception) {
            Log.e("TvRowLayoutManager", "Suppressed layout exception during rapid scroll: ${e.message}")
        }
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        return try {
            super.scrollHorizontallyBy(dx, recycler, state)
        } catch (e: Exception) {
            0
        }
    }

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
            }
        }
        return super.onInterceptFocusSearch(focused, direction)
    }

    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        val fromPos = findContainingItemView(focused)?.let { getPosition(it) } ?: RecyclerView.NO_POSITION
        val count = itemCount
        if (count > 0 && fromPos != RecyclerView.NO_POSITION) {
            if (focusDirection == View.FOCUS_RIGHT && fromPos >= count - 1) {
                return null
            }
            if (focusDirection == View.FOCUS_LEFT && fromPos <= 0) {
                return null
            }
        }
        return try {
            super.onFocusSearchFailed(focused, focusDirection, recycler, state)
        } catch (e: Exception) {
            null
        }
    }
}
