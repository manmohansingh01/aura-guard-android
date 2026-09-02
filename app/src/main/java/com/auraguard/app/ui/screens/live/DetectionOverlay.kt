package com.auraguard.app.ui.screens.live

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.auraguard.app.core.PerimeterState
import com.auraguard.app.tracking.TrackedObject

private val SAFE_COLOR = Color(0xFF00E5A0)
private val WARNING_COLOR = Color(0xFFFFB020)
private val CRITICAL_COLOR = Color(0xFFFF3B3B)

/** Draws bounding boxes, class/ID/confidence labels, and short movement trails for every tracked object. */
@Composable
fun DetectionOverlay(objects: List<TrackedObject>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val labelPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 28f
            typeface = Typeface.MONOSPACE
        }
        val bgPaint = Paint().apply { isAntiAlias = true }

        for (obj in objects) {
            val color = when (obj.perimeterState) {
                PerimeterState.BREACH -> CRITICAL_COLOR
                PerimeterState.APPROACHING -> WARNING_COLOR
                PerimeterState.SAFE -> SAFE_COLOR
            }

            // Movement trail.
            if (obj.trail.size >= 2) {
                for (i in 0 until obj.trail.size - 1) {
                    val a = obj.trail[i]; val b = obj.trail[i + 1]
                    val alpha = 0.15f + 0.55f * (i.toFloat() / obj.trail.size)
                    drawLine(
                        color = color.copy(alpha = alpha),
                        start = Offset(a.x * w, a.y * h),
                        end = Offset(b.x * w, b.y * h),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
            }

            val left = obj.box.left * w
            val top = obj.box.top * h
            val right = obj.box.right * w
            val bottom = obj.box.bottom * h

            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                style = Stroke(width = if (obj.perimeterState == PerimeterState.SAFE) 3f else 5f)
            )

            val idText = "${obj.label}  #${obj.id.toString().padStart(2, '0')}"
            val confText = "CONF ${(obj.confidence * 100).toInt()}%"
            val lines = listOf(idText, confText)
            val lineHeight = 30f
            val textWidth = lines.maxOf { labelPaint.measureText(it) }
            val labelTop = (top - lines.size * lineHeight - 6f).coerceAtLeast(0f)

            bgPaint.color = color.copy(alpha = 0.85f).toArgb()
            drawContext.canvas.nativeCanvas.drawRect(
                left, labelTop, left + textWidth + 16f, labelTop + lines.size * lineHeight + 6f, bgPaint
            )
            labelPaint.color = if (color == WARNING_COLOR) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            lines.forEachIndexed { i, line ->
                drawContext.canvas.nativeCanvas.drawText(
                    line, left + 8f, labelTop + (i + 1) * lineHeight - 6f, labelPaint
                )
            }
        }
    }
}
