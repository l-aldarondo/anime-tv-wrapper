package com.example.animetv.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * High-performance virtual pointer overlay for Google TV & Android TV remotes.
 * Features 60/120 FPS continuous velocity physics, smooth acceleration,
 * fractional sub-pixel edge scrolling, and responsive remote click simulation.
 */
class VirtualCursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

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

    var targetView: View? = null

    private val density = resources.displayMetrics.density
    private val baseRadius = 14f * density

    // Velocities in pixels per second
    private var vx = 0f
    private var vy = 0f
    private var targetVx = 0f
    private var targetVy = 0f

    // Held direction keys
    private var upHeld = false
    private var downHeld = false
    private var leftHeld = false
    private var rightHeld = false
    private var holdDurationMs = 0L
    private var lastFrameTimeNanos = 0L

    // Edge scroll accumulator
    private var scrollAccumulatorY = 0f

    // Direct scroll mode velocities
    var isDirectScrollMode = false
    private var directScrollVy = 0f
    private var directScrollAccumulatorY = 0f

    // Ripple effect on click
    private var rippleRadius = 0f
    private var rippleAlpha = 0

    // Hardware-accelerated Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.parseColor("#9D4EDD") // Anime purple glow
    }

    private val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676") // Vibrant green pointer center
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.parseColor("#00E676")
    }

    private var isLoopRunning = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    fun startLoop() {
        if (!isLoopRunning) {
            isLoopRunning = true
            lastFrameTimeNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stopLoop() {
        isLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorX == 0f && cursorY == 0f && w > 0 && h > 0) {
            cursorX = w / 2f
            cursorY = h / 2f
        }
    }

    fun onDpadKey(keyCode: Int, isDown: Boolean) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> upHeld = isDown
            KeyEvent.KEYCODE_DPAD_DOWN -> downHeld = isDown
            KeyEvent.KEYCODE_DPAD_LEFT -> leftHeld = isDown
            KeyEvent.KEYCODE_DPAD_RIGHT -> rightHeld = isDown
        }

        if (isDown) {
            startLoop()
        }

        updateTargetVelocity()
    }

    private fun updateTargetVelocity() {
        val anyHeld = upHeld || downHeld || leftHeld || rightHeld
        if (!anyHeld) {
            holdDurationMs = 0L
            targetVx = 0f
            targetVy = 0f
            directScrollVy = 0f
            return
        }

        if (isDirectScrollMode) {
            // Direct scroll velocity: smoothly glides page at 260dp/s to 600dp/s
            val scrollSpeed = (260f + (holdDurationMs * 0.25f).coerceAtMost(340f)) * density
            var sDirY = 0f
            if (upHeld) sDirY -= 1f
            if (downHeld) sDirY += 1f
            directScrollVy = sDirY * scrollSpeed
            return
        }

        // Pointer mode speed: begins at a controllable 480dp/s, smoothly accelerating up to 1000dp/s
        val currentSpeed = (480f + (holdDurationMs * 0.45f).coerceAtMost(520f)) * density

        var dirX = 0f
        var dirY = 0f
        if (leftHeld) dirX -= 1f
        if (rightHeld) dirX += 1f
        if (upHeld) dirY -= 1f
        if (downHeld) dirY += 1f

        if (dirX != 0f && dirY != 0f) {
            val invSqrt = 0.7071f
            dirX *= invSqrt
            dirY *= invSqrt
        }

        targetVx = dirX * currentSpeed
        targetVy = dirY * currentSpeed
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isLoopRunning) return

        if (lastFrameTimeNanos != 0L) {
            val dt = ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)

            if (upHeld || downHeld || leftHeld || rightHeld) {
                holdDurationMs += (dt * 1000).toLong()
                updateTargetVelocity()
            }

            if (isDirectScrollMode) {
                val target = targetView
                if (target != null && directScrollVy != 0f) {
                    directScrollAccumulatorY += directScrollVy * dt
                    val px = directScrollAccumulatorY.toInt()
                    if (px != 0) {
                        target.scrollBy(0, px)
                        directScrollAccumulatorY -= px
                    }
                }
            } else {
                val lerpFactor = (1.0 - Math.exp(-22.0 * dt)).toFloat()
                vx += (targetVx - vx) * lerpFactor
                vy += (targetVy - vy) * lerpFactor

                if (Math.abs(vx) < 1f && targetVx == 0f) vx = 0f
                if (Math.abs(vy) < 1f && targetVy == 0f) vy = 0f

                if (vx != 0f || vy != 0f) {
                    val pad = 12f * density
                    cursorX = (cursorX + (vx * dt)).coerceIn(pad, (width - pad).coerceAtLeast(pad))
                    cursorY = (cursorY + (vy * dt)).coerceIn(pad, (height - pad).coerceAtLeast(pad))
                    invalidate()
                }

                // Ultra-smooth controllable edge scrolling while pointer is near borders
                val target = targetView
                if (target != null && height > 0 && width > 0 && isCursorVisible) {
                    val edgeZone = 90f * density
                    var scrollRate = 0f

                    if (cursorY < edgeZone) {
                        val penetration = ((edgeZone - cursorY) / edgeZone).coerceIn(0f, 1f)
                        scrollRate = -((150f + (penetration * 320f)) * density)
                    } else if (cursorY > height - edgeZone) {
                        val penetration = ((cursorY - (height - edgeZone)) / edgeZone).coerceIn(0f, 1f)
                        scrollRate = ((150f + (penetration * 320f)) * density)
                    }

                    if (scrollRate != 0f) {
                        scrollAccumulatorY += scrollRate * dt
                        val scrollPixels = scrollAccumulatorY.toInt()
                        if (scrollPixels != 0) {
                            target.scrollBy(0, scrollPixels)
                            scrollAccumulatorY -= scrollPixels
                        }
                    } else {
                        scrollAccumulatorY = 0f
                    }
                }
            }
        }

        lastFrameTimeNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
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
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                rippleRadius = baseRadius + (fraction * 30f * density)
                rippleAlpha = ((1f - fraction) * 230).toInt()
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
        canvas.drawCircle(cursorX, cursorY, baseRadius * 0.72f, ringPaint)
        // Draw sharp center dot
        canvas.drawCircle(cursorX, cursorY, 4f * density, innerDotPaint)

        // Draw click ripple effect if active
        if (rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(cursorX, cursorY, rippleRadius, ripplePaint)
        }
    }
}
