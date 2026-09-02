package com.auraguard.app.ui.screens.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.auraguard.app.core.NormPoint
import com.auraguard.app.core.PerimeterState
import com.auraguard.app.perimeter.PerimeterEditState
import com.auraguard.app.perimeter.Zone
import kotlin.math.hypot

private val HIT_RADIUS_DP = 20.dp

/**
 * Renders saved zone polygons on top of the live video and, in
 * DEFINE PERIMETER mode, lets the operator tap to add corners, drag
 * existing corners, and see the in-progress polygon close live.
 *
 * All geometry is stored normalized (0..1) so it stays correctly aligned
 * across capture-resolution or orientation changes — this Composable is
 * the only place that multiplies by the current canvas size.
 */
@Composable
fun PerimeterOverlay(
    zones: List<Zone>,
    editState: PerimeterEditState,
    onAddPoint: (NormPoint) -> Unit,
    onMovePoint: (index: Int, point: NormPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .pointerInput(editState.isActive, editState.points.size) {
                if (!editState.isActive) return@pointerInput
                val hitRadiusPx = HIT_RADIUS_DP.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val downPos = down.position
                    val hitIndex = editState.points.indexOfFirst { pt ->
                        val px = pt.x * size.width
                        val py = pt.y * size.height
                        hypot((px - downPos.x).toDouble(), (py - downPos.y).toDouble()) <= hitRadiusPx
                    }
                    if (hitIndex >= 0) {
                        // Drag an existing corner.
                        var current = downPos
                        drag(down.id) { change: PointerInputChange ->
                            current = change.position
                            change.consume()
                            onMovePoint(
                                hitIndex,
                                NormPoint(
                                    (current.x / size.width).coerceIn(0f, 1f),
                                    (current.y / size.height).coerceIn(0f, 1f)
                                )
                            )
                        }
                    } else {
                        // Tap-to-add: only counts if the pointer doesn't turn into a drag.
                        var moved = false
                        drag(down.id) { change ->
                            val dx = change.position.x - downPos.x
                            val dy = change.position.y - downPos.y
                            if (hypot(dx.toDouble(), dy.toDouble()) > 12) moved = true
                            change.consume()
                        }
                        if (!moved) {
                            onAddPoint(
                                NormPoint(
                                    (downPos.x / size.width).coerceIn(0f, 1f),
                                    (downPos.y / size.height).coerceIn(0f, 1f)
                                )
                            )
                        }
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height

        // Saved / armed zones.
        for (zone in zones) {
            if (zone.points.size < 2) continue
            val color = Color(zone.color)
            val strokeColor = when (zone.currentState) {
                PerimeterState.BREACH -> Color(0xFFFF3B3B)
                PerimeterState.APPROACHING -> Color(0xFFFFB020)
                PerimeterState.SAFE -> color
            }
            val path = androidx.compose.ui.graphics.Path().apply {
                zone.points.forEachIndexed { i, p ->
                    val x = p.x * w; val y = p.y * h
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            val fillAlpha = when (zone.currentState) {
                PerimeterState.BREACH -> 0.28f
                PerimeterState.APPROACHING -> 0.16f
                PerimeterState.SAFE -> if (zone.armed) 0.08f else 0.03f
            }
            drawPath(path, color = strokeColor.copy(alpha = fillAlpha))
            drawPath(
                path,
                color = strokeColor,
                style = Stroke(
                    width = if (zone.currentState == PerimeterState.SAFE) 3f else 5f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // In-progress polygon being defined.
        if (editState.isActive && editState.points.isNotEmpty()) {
            val pts = editState.points
            for (i in pts.indices) {
                val a = pts[i]
                val ax = a.x * w; val ay = a.y * h
                if (i < pts.size - 1) {
                    val b = pts[i + 1]
                    drawLine(
                        color = Color(0xFF00E5A0), start = Offset(ax, ay),
                        end = Offset(b.x * w, b.y * h), strokeWidth = 4f, cap = StrokeCap.Round
                    )
                } else if (pts.size >= 3) {
                    // Preview closing segment back to the first point.
                    val first = pts[0]
                    drawLine(
                        color = Color(0xFF00E5A0).copy(alpha = 0.5f), start = Offset(ax, ay),
                        end = Offset(first.x * w, first.y * h), strokeWidth = 3f, cap = StrokeCap.Round
                    )
                }
                drawCircle(
                    color = if (i == 0) Color(0xFF00E5A0) else Color.White,
                    radius = if (i == 0) 10f else 7f,
                    center = Offset(ax, ay)
                )
            }
        }
    }
}
