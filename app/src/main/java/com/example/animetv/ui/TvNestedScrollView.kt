package com.example.animetv.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView
import com.example.animetv.R

/**
 * TV-optimized NestedScrollView (Nuvio / Netflix TV style).
 * Overrides default child focus scrolling so that when any card in a row gains focus,
 * the ENTIRE ROW (including its title txtRowTitle and all card artwork) is smoothly aligned
 * with full visibility and breathing room, preventing the row title from ever being chopped off.
 */
class TvNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    override fun requestChildFocus(child: View?, focused: View?) {
        if (child == null || focused == null) {
            super.requestChildFocus(child, focused)
            return
        }

        // Find the top-level row container (direct child of layoutCatalogRows)
        val rowView = findContainingRowView(focused)
        if (rowView != null) {
            scrollToRow(rowView)
            // Pass null as focused to prevent Android's default single-view scroller
            // from chopping off the row title or fighting row alignment
            super.requestChildFocus(child, null)
        } else {
            super.requestChildFocus(child, focused)
        }
    }

    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean
    ): Boolean {
        // Prevent default nested rectangle-on-screen scrolling from fighting row alignment
        val rowView = findContainingRowView(child)
        if (rowView != null) {
            scrollToRow(rowView)
            return true
        }
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }

    private fun findContainingRowView(view: View): View? {
        var current: Any? = view
        while (current is View) {
            val parent = current.parent
            if (parent is View && parent.id == R.id.layoutCatalogRows) {
                return current
            }
            current = parent
        }
        return null
    }

    private fun scrollToRow(rowView: View) {
        if (height <= 0) {
            post { scrollToRow(rowView) }
            return
        }

        val rowTop = rowView.top
        val currentScrollY = scrollY

        // 1. If this is the first row (or near the top), smoothly scroll to top (0)
        // so the top row, its title, and the hero above are displayed completely.
        if (rowTop <= 10) {
            smoothScrollTo(0, 0)
            return
        }

        // 2. We align the entire row (title + cards) so it is 100% visible on screen.
        // In Netflix and Nuvio, the focused row is aligned with a comfortable top margin
        // so that the title is fully readable and cards are completely in view.
        val topPadding = (16 * resources.displayMetrics.density).toInt()
        val targetY = (rowTop - topPadding).coerceAtLeast(0)

        // Only scroll if we are not already at the target position (prevents jitter)
        if (Math.abs(currentScrollY - targetY) > 6) {
            smoothScrollTo(0, targetY)
        }
    }
}
