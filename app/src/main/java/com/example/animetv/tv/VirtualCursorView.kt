package com.example.animetv.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Virtual mouse cursor overlay for Google TV / Android TV remotes.
 * Allows TV remote D-Pad navigation to click video controls, server chips, and episode lists.
 */
class VirtualCursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cursorX = 0f
        private set
    var cursorY = 0f
        private set

    var isCursorVisible: Boolean = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }

    private val baseRadius = 14f * resources.displayMetrics.density
    private var rippleRadius = 0f
    private var rippleAlpha = 0

    // Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.parseColor("#9D4EDD") // Anime purple glow
        setShadowLayer(8f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#7B2CBF"))
    }

    private val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676") // Vibrant green pointer center
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.WHITE
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.parseColor("#00E676")
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorX == 0f && cursorY == 0f) {
            // Position cursor at center of TV screen initially
            cursorX = w / 2f
            cursorY = h / 2f
        }
    }

    /**
     * Move the cursor by a delta. Clamps to view bounds and returns edge-scroll deltas.
     */
    fun moveBy(dx: Float, dy: Float, targetView: View? = null): Boolean {
        cursorX = (cursorX + dx).coerceIn(10f, width - 10f)
        cursorY = (cursorY + dy).coerceIn(10f, height - 10f)
        invalidate()

        // Edge scrolling: if cursor is near edges, scroll target view
        if (targetView != null && height > 0 && width > 0) {
            val edgeThreshold = 80f * resources.displayMetrics.density
            val scrollSpeed = 24 * resources.displayMetrics.density.toInt()

            if (cursorY < edgeThreshold) {
                targetView.scrollBy(0, -scrollSpeed)
            } else if (cursorY > height - edgeThreshold) {
                targetView.scrollBy(0, scrollSpeed)
            }
        }
        return true
    }

    /**
     * Dispatch touch event at current cursor position to simulate remote click on WebView.
     */
    fun dispatchClick(target: View) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        target.dispatchTouchEvent(down)
        down.recycle()

        postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(now, upTime, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
            target.dispatchTouchEvent(up)
            up.recycle()
        }, 80)

        // Visual click feedback animation
        startClickRipple()
    }

    private fun startClickRipple() {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                rippleRadius = baseRadius + (fraction * 28f * resources.displayMetrics.density)
                rippleAlpha = ((1f - fraction) * 220).toInt()
                invalidate()
            }
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isCursorVisible) return

        // Draw outer glow ring
        canvas.drawCircle(cursorX, cursorY, baseRadius, glowPaint)
        // Draw inner white ring
        canvas.drawCircle(cursorX, cursorY, baseRadius * 0.75f, ringPaint)
        // Draw sharp center dot
        canvas.drawCircle(cursorX, cursorY, 3.5f * resources.displayMetrics.density, innerDotPaint)

        // Draw click ripple effect if active
        if (rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(cursorX, cursorY, rippleRadius, ripplePaint)
        }
    }
}
