package com.example.compis

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

data class DetBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String? = null,
    val confidence: Float = 0f,
    val trackingId: Int? = null,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val growth: Float = 0f,
    val isApproaching: Boolean = false
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = (right - left) * (bottom - top)
}
class MlKitObjectAnalyzer(
    private val onObjects: (List<DetBox>) -> Unit,
    private val minBoxArea: Float = 0.03f,
    private val smoothAlpha: Float = 0.4f,
    private val approachThresh: Float = 0.012f,
    private val maxObjects: Int = 8
) : ImageAnalysis.Analyzer {

    private val odt = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    )

    private data class Prev(val cx: Float, val cy: Float, val area: Float, val vx: Float, val vy: Float, val g: Float)
    private val prevById = mutableMapOf<Int, Prev>()

    override fun analyze(image: ImageProxy) {
        try {
            val nv21 = yuv420ToNv21(image)
            val rotation = image.imageInfo.rotationDegrees
            val input = InputImage.fromByteArray(
                nv21,
                image.width,
                image.height,
                rotation,
                ImageFormat.NV21
            )

            val rotW = if (rotation % 180 == 0) image.width else image.height
            val rotH = if (rotation % 180 == 0) image.height else image.width
            val wF = rotW.toFloat(); val hF = rotH.toFloat()

            odt.process(input)
                .addOnSuccessListener { objects ->
                    val centerL = 1f / 3f
                    val centerR = 2f / 3f
                    val ordered: List<DetectedObject> = objects.sortedWith(
                        compareByDescending<DetectedObject> { it.boundingBox.width().toLong() * it.boundingBox.height() }
                            .thenBy { o ->
                                val cx = ((o.boundingBox.left + o.boundingBox.right) * 0.5f) / wF
                                when {
                                    cx < centerL -> centerL - cx
                                    cx > centerR -> cx - centerR
                                    else -> 0f
                                }
                            }
                    )

                    val out = ArrayList<DetBox>(min(maxObjects, ordered.size))
                    val nextPrev = mutableMapOf<Int, Prev>()

                    for (obj in ordered) {
                        val r = obj.boundingBox
                        val (L, T, R, B) = normalize(r.left, r.top, r.right, r.bottom, wF, hF)
                        val area = (R - L) * (B - T)
                        if (area < minBoxArea) continue

                        val cx = (L + R) * 0.5f
                        val cy = (T + B) * 0.5f
                        val id = obj.trackingId

                        var vx = 0f; var vy = 0f; var g = 0f
                        if (id != null) {
                            prevById[id]?.let { prev ->
                                val dx = cx - prev.cx
                                val dy = cy - prev.cy
                                val da = area - prev.area
                                vx = ema(prev.vx, dx, smoothAlpha)
                                vy = ema(prev.vy, dy, smoothAlpha)
                                g  = ema(prev.g,  da, smoothAlpha)
                            }
                            nextPrev[id] = Prev(cx, cy, area, vx, vy, g)
                        }

                        val approaching = g > approachThresh || (vy > 0.02f && cy > 0.45f)

                        out += DetBox(
                            left = L, top = T, right = R, bottom = B,
                            trackingId = id,
                            vx = vx, vy = vy, growth = g, isApproaching = approaching
                        )

                        if (out.size >= maxObjects) break
                    }

                    prevById.clear()
                    prevById.putAll(nextPrev)

                    onObjects(out)
                }
                .addOnFailureListener { onObjects(emptyList()) }
                .addOnCompleteListener { image.close() }
        } catch (_: Exception) {
            image.close()
        }
    }


    private fun ema(prev: Float, x: Float, a: Float): Float = (1f - a) * prev + a * x

    private fun normalize(l: Int, t: Int, r: Int, b: Int, w: Float, h: Float): FloatArray {
        val L = clamp01(l / w); val T = clamp01(t / h)
        val R = clamp01(r / w); val B = clamp01(b / h)
        return floatArrayOf(min(L, R), min(T, B), max(L, R), max(T, B))
    }

    private fun clamp01(v: Float) = when {
        v < 0f -> 0f
        v > 1f -> 1f
        else -> v
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val nv21 = ByteArray(ySize + (ySize / 2))

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var dst = 0
        if (yRowStride == w) {
            yBuf.get(nv21, 0, ySize)
            dst = ySize
        } else {
            var src = 0
            for (row in 0 until h) {
                yBuf.position(src)
                yBuf.get(nv21, dst, w)
                src += yRowStride
                dst += w
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var chromaDst = ySize
        for (row in 0 until h / 2) {
            var uSrc = row * uRowStride
            var vSrc = row * vRowStride
            var col = 0
            while (col < w) {
                nv21[chromaDst++] = vBuf.get(vSrc)
                nv21[chromaDst++] = uBuf.get(uSrc)
                vSrc += vPixelStride
                uSrc += uPixelStride
                col += 2
            }
        }
        return nv21
    }
}
