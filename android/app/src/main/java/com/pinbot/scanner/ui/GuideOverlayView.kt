package com.pinbot.scanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Darkens everything outside the aiming frame and outlines it.
 * Turns green once the scanner sees a plausible code.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val dimPaint = Paint().apply { color = Color.argb(130, 0, 0, 0) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(220, 255, 255, 255)
    }
    private val hotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.parseColor("#2FD07A")
    }
    private val shade = Path()
    private val guide = RectF()
    private var hot = false

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setHot(value: Boolean) {
        if (hot == value) return
        hot = value
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val margin = width * 0.05f
        val guideHeight = height * 0.22f
        val top = height * 0.30f
        guide.set(margin, top, width - margin, top + guideHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shade.reset()
        shade.fillType = Path.FillType.EVEN_ODD
        shade.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        shade.addRoundRect(guide, 28f, 28f, Path.Direction.CW)
        canvas.drawPath(shade, dimPaint)
        canvas.drawRoundRect(guide, 28f, 28f, if (hot) hotPaint else framePaint)
    }
}
