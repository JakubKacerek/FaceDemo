package com.example.facedemo

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View


class BanknoteOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var detections: List<BanknoteDetection> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var isFrontCamera = false
    var scale = 1f
    var offsetX = 0f
    var offsetY = 0f

    private val boxPaint = Paint().apply {
        color  = Color.parseColor("#FF9800")
        style  = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color     = Color.WHITE
        textSize  = 42f
        isAntiAlias = true
        style     = Paint.Style.FILL
        typeface  = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        val imageW = (width.toFloat() - 2f * offsetX).coerceAtLeast(1f)
        val imageH = (height.toFloat() - 2f * offsetY).coerceAtLeast(1f)

        for (det in detections) {
            val box = det.boundingBox // normalized [0,1]

            var left = offsetX + box.left * imageW
            var right = offsetX + box.right * imageW
            val top = offsetY + box.top * imageH
            val bottom = offsetY + box.bottom * imageH

            if (isFrontCamera) {
                val tmp = left
                left = width - right
                right = width - tmp
            }

            val rect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(rect, 12f, 12f, boxPaint)

            // Label background + text
            val label = "${det.label}  ${(det.confidence * 100).toInt()}%"
            val textW = textPaint.measureText(label)
            val textH = textPaint.textSize

            val labelLeft   = left
            val labelTop    = (top - textH - 12f).coerceAtLeast(0f)
            val labelRight  = (left + textW + 16f).coerceAtMost(width.toFloat())
            val labelBottom = labelTop + textH + 12f

            canvas.drawRoundRect(RectF(labelLeft, labelTop, labelRight, labelBottom), 8f, 8f, bgPaint)
            canvas.drawText(label, labelLeft + 8f, labelBottom - 6f, textPaint)
        }
    }
}
