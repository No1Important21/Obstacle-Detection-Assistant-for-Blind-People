package com.example.compis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import android.graphics.Paint
import android.graphics.RectF

@Composable
fun OverlayComposable(
    status: String,
    statusColor: Color,
    roiTop: Int,
    roiBottom: Int,
    fps: Float,
    boxes: List<DetBox> = emptyList()
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val t = roiTop / 480f * h
        val b = roiBottom / 480f * h

        drawRect(
            color = Color(0x222255FF),
            topLeft = androidx.compose.ui.geometry.Offset(0f, t),
            size = androidx.compose.ui.geometry.Size(w, b - t)
        )
        drawLine(
            color = Color(0x552255FF),
            start = Offset(0f, (t + b) / 2f),
            end = Offset(w, (t + b) / 2f),
            strokeWidth = 2f
        )

        boxes.forEach { box ->
            val left = box.left * w
            val top = box.top * h
            val right = box.right * w
            val bottom = box.bottom * h

            val strokeCol = if (box.isApproaching)
                Color(0xFFFF4444)
            else
                Color(0xFF4CAF50)

            // box
            drawRect(
                color = strokeCol.copy(alpha = 0.95f),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                style = Stroke(width = 3f)
            )

            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            val scale = 120f
            val end = Offset(
                cx + box.vx * w * scale / 480f,
                cy + box.vy * h * scale / 640f
            )
            drawLine(
                color = strokeCol,
                start = Offset(cx, cy),
                end = end,
                strokeWidth = 3f
            )

            if (box.isApproaching) {
                drawContext.canvas.nativeCanvas.apply {
                    val pad = 6f
                    val pTxt = Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 26f
                        isAntiAlias = true
                        isFakeBoldText = true
                    }
                    val text = "mendekat"
                    val tw = pTxt.measureText(text)
                    val fm = pTxt.fontMetrics
                    val th = fm.bottom - fm.top
                    val rect = RectF(left, top - th - pad * 2, left + tw + pad * 2, top - 2f)
                    val pBg = Paint().apply {
                        color = Color(0xAAE53935).toArgb()
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    drawRoundRect(rect, 10f, 10f, pBg)
                    drawText(text, rect.left + pad, rect.bottom - pad - fm.bottom, pTxt)
                }
            }
        }

        drawContext.canvas.nativeCanvas.apply {
            val p = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 34f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val bg = Paint().apply {
                color = statusColor.copy(alpha = 0.28f).toArgb()
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val pad = 14f
            val tw = p.measureText(status)
            val fm = p.fontMetrics
            val th = fm.bottom - fm.top
            val rect = RectF(12f, 12f, 12f + tw + pad * 2, 12f + th + pad * 2)
            drawRoundRect(rect, 18f, 18f, bg)
            drawText(status, 12f + pad, 12f + pad - fm.top, p)

            val small = Paint(p).apply { textSize = 24f; isFakeBoldText = false }
            val fpsText = "FPS: ${"%.1f".format(fps)}"
            val tw2 = small.measureText(fpsText)
            val rect2 = RectF(
                w - tw2 - 24f,
                12f,
                w - 12f,
                12f + (small.fontMetrics.bottom - small.fontMetrics.top) + 20f
            )
            val bg2 = Paint(bg).apply { color = Color(0x66000000).toArgb() }
            drawRoundRect(rect2, 14f, 14f, bg2)
            drawText(fpsText, rect2.left + 10f, rect2.top + 14f - small.fontMetrics.top, small)
        }
    }
}
