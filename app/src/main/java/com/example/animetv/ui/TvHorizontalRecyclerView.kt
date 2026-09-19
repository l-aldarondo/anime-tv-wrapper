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

    init {
        // Disabling ItemAnimator prevents animation churn and state corruption during rapid D-pad repeat
        itemAnimator = null
        setHasFixedSize(true)
        descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event?.action == android.view.KeyEvent.ACTION_DOWN) {
            val focusedChild = findFocus()
            if (focusedChild != null) {
                val itemView = findContainingItemView(focusedChild)
                val lm = layoutManager
                if (itemView != null && lm != null) {
                    val pos = lm.getPosition(itemView)
                    val count = adapter?.itemCount ?: 0
                    if (count > 0 && pos != NO_POSITION) {
                        // Trapping focus at right edge: ALWAYS advance within row and NEVER let event escape
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                            if (pos >= count - 1) {
                                return true // Clamp at end of row
                            }
                            val nextChild = lm.findViewByPosition(pos + 1)
                            if (nextChild != null) {
                                nextChild.requestFocus()
                                return true
                            } else {
                                scrollToPosition(pos + 1)
                                post {
                                    lm.findViewByPosition(pos + 1)?.requestFocus()
                                }
                                return true
                            }
                        }
                        // Left edge: within row advance left; at position 0 allow event to enter Left Sidebar
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                            if (pos <= 0) {
                                return super.dispatchKeyEvent(event)
                            }
                            val prevChild = lm.findViewByPosition(pos - 1)
                            if (prevChild != null) {
                                prevChild.requestFocus()
                                return true
                            } else {
                                scrollToPosition(pos - 1)
                                post {
                                    lm.findViewByPosition(pos - 1)?.requestFocus()
                                }
                                return true
                            }
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        // Strictly prevent horizontal RIGHT escaping. Allow horizontal LEFT to reach the Left Sidebar when at pos 0.
        if (direction == View.FOCUS_RIGHT) {
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
