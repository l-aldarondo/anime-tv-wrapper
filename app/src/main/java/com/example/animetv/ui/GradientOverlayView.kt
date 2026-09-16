package com.example.animetv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Paints a precise multi-stop linear gradient — arbitrary colors at arbitrary positions, which
 * Android's XML `<gradient>` shape drawable can't express (it only supports evenly-spaced
 * start/center/end colors). Used for the hero banner's targeted text- and row-protection
 * overlays: a solid color pocket that dissolves away at specific percentages, rather than a
 * flat uniform dim over the whole image.
 */
class GradientOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Direction { LEFT_TO_RIGHT, BOTTOM_TO_TOP }

    private var direction: Direction = Direction.LEFT_TO_RIGHT
    private var stopColors: IntArray = intArrayOf()
    private var stopPositions: FloatArray = floatArrayOf()
    private val paint = Paint()

    fun setGradient(direction: Direction, colors: IntArray, positions: FloatArray) {
        this.direction = direction
        this.stopColors = colors
        this.stopPositions = positions
        buildShader()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShader()
    }

    private fun buildShader() {
        if (width == 0 || height == 0 || stopColors.isEmpty()) return
        paint.shader = when (direction) {
            Direction.LEFT_TO_RIGHT -> LinearGradient(
                0f, 0f, width.toFloat(), 0f, stopColors, stopPositions, Shader.TileMode.CLAMP
            )
            Direction.BOTTOM_TO_TOP -> LinearGradient(
                0f, height.toFloat(), 0f, 0f, stopColors, stopPositions, Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paint.shader != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
