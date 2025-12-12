package com.example.compis

import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class OpticalFlowAnalyzer(
    private val getParams: () -> DetectionParams,
    private val onResult: (Result) -> Unit
) : ImageAnalysis.Analyzer {

    data class Result(
        val statusText: String,
        val statusColor: Int,
        val leftCount: Int,
        val centerCount: Int,
        val rightCount: Int,
        val roiTop: Int,
        val roiBottom: Int,
        val ttsToSpeak: String? = null
    )

    private var prevGray: Mat? = null

    // smoothing
    private var emaL = 0.0
    private var emaC = 0.0
    private var emaR = 0.0
    private val ALPHA = 0.30

    // TTS cooldown
    private var current = "Jalur aman, lanjut"
    private var lastSpeak = 0L
    private val safeCooldown = 3500L
    private val dangerCooldown = 900L

    override fun analyze(image: ImageProxy) {
        val params = getParams()

        val grayFull = try {
            Yuv.toGrayMat(image) ?: run { image.close(); return }
        } catch (_: Exception) {
            image.close(); return
        }

        try {
            val h = grayFull.rows()
            val w = grayFull.cols()

            val roiTop = (h * params.roiTopFrac).toInt().coerceIn(0, h - 2)
            val roiBottom = (h * params.roiBottomFrac).toInt().coerceIn(roiTop + 2, h)
            val roiRect = Rect(0, roiTop, w, roiBottom - roiTop)

            val grayCrop = Mat(grayFull, roiRect)
            val scale =
                if (grayCrop.cols() > params.inputWidth) params.inputWidth.toDouble() / grayCrop.cols()
                else 1.0

            val gray = if (scale < 1.0) {
                val resized = Mat()
                Imgproc.resize(grayCrop, resized, Size(), scale, scale, Imgproc.INTER_AREA)
                resized
            } else {
                grayCrop.clone()
            }
            grayCrop.release()

            if (prevGray == null || prevGray!!.size() != gray.size()) {
                prevGray?.release()
                prevGray = gray.clone()
                onResult(Result("Menunggu frame…", Color.YELLOW, 0, 0, 0, roiTop, roiBottom))
                return
            }

            val flow = Mat(gray.size(), CvType.CV_32FC2)
            Video.calcOpticalFlowFarneback(
                prevGray, gray, flow,
                0.5, 3, 15, 3, 5, 1.2, 0
            )

            val channels = ArrayList<Mat>(2)
            Core.split(flow, channels)
            val fx = channels[0]
            val fy = channels[1]

            val mag = Mat()
            Core.magnitude(fx, fy, mag)

            val thr = max(2.0, percentile(mag, params.percentile))
            val strongThr = thr * params.strongMul

            val fxMed = medianOfMat(fx)
            val fyMed = medianOfMat(fy)
            if (hypot(fxMed, fyMed) < 1.2) {
                Core.subtract(fx, Scalar(fxMed), fx)
                Core.subtract(fy, Scalar(fyMed), fy)
                Core.magnitude(fx, fy, mag)
            }

            val roiH = gray.rows()
            val roiW = gray.cols()
            val leftEnd = (roiW / 3.0).toInt()
            val centerEnd = (roiW * 2.0 / 3.0).toInt()

            val cx = roiW / 2.0
            val cy = roiH / 2.0

            val bottomStart = (roiH * 2 / 3)

            val step = max(8, params.step)
            val samplesTotal = (roiH / step) * (roiW / step)
            val zoneSamples = max(1, samplesTotal / 3)

            var L = 0
            var C = 0
            var R = 0

            val bufX = FloatArray(1)
            val bufY = FloatArray(1)
            val bufM = FloatArray(1)

            var y = 0
            while (y < roiH) {
                var x = 0
                while (x < roiW) {
                    fx.get(y, x, bufX)
                    fy.get(y, x, bufY)
                    mag.get(y, x, bufM)

                    val m = bufM[0].toDouble()

                    // proyeksi menaikkan “radial keluar kamera” (positive)
                    val vx = x - cx
                    val vy = y - cy
                    val radial = (bufX[0] * vx + bufY[0] * vy) / (sqrt(vx * vx + vy * vy) + 1e-6)

                    val active = (m >= thr && radial > params.radialMin) || (m >= strongThr)
                    if (active) {
                        val weight = if (y >= bottomStart) params.bottomWeight else 1
                        when {
                            x < leftEnd   -> L += weight
                            x < centerEnd -> C += weight
                            else          -> R += weight
                        }
                    }
                    x += step
                }
                y += step
            }

            // EMA per zona
            val z = max(1, zoneSamples)
            emaL = (1 - ALPHA) * emaL + ALPHA * (L.toDouble() / z)
            emaC = (1 - ALPHA) * emaC + ALPHA * (C.toDouble() / z)
            emaR = (1 - ALPHA) * emaR + ALPHA * (R.toDouble() / z)

            // keputusan
            val lHit = emaL >= params.enterSide
            val rHit = emaR >= params.enterSide
            val centerDominant =
                emaC >= params.enterCenter && emaC >= max(emaL, emaR) + params.centerMargin

            val balancedSides = abs(emaL - emaR) < 0.03
            val meanRate = (emaL + emaC + emaR) / 3.0
            val forwardMotionButClear = balancedSides && !centerDominant && meanRate >= params.forwardMeanMin

            var desired = "Jalur aman, lanjut"
            var color = Color.GREEN
            var speak: String? = null

            if (forwardMotionButClear) {
                desired = "Jalur aman, lanjut"
            } else if (centerDominant || (lHit && rHit && emaC >= params.enterSide)) {
                desired = "Di depan ada tembok, berhenti"
                color = Color.RED
                speak = desired
            } else if (lHit && !rHit) {
                desired = "Halangan di kiri, belok ke kanan"
                color = 0xFFFFA500.toInt()
                speak = desired
            } else if (rHit && !lHit) {
                desired = "Halangan di kanan, belok ke kiri"
                color = 0xFFFFA500.toInt()
                speak = desired
            }

            val now = System.currentTimeMillis()
            val cd = if (desired == "Jalur aman, lanjut") safeCooldown else dangerCooldown
            if (desired != current || now - lastSpeak > cd) {
                current = desired
                if (speak != null && params.speak) lastSpeak = now
            } else {
                speak = null
            }

            onResult(
                Result(
                    statusText = current,
                    statusColor = color,
                    leftCount = L,
                    centerCount = C,
                    rightCount = R,
                    roiTop = roiTop,
                    roiBottom = roiBottom,
                    ttsToSpeak = if (params.speak) speak else null
                )
            )

            prevGray?.release()
            prevGray = gray.clone()

            mag.release()
            fx.release(); fy.release()
            channels.forEach { it.release() }
            flow.release()
            gray.release()

        } catch (_: Exception) {
        } finally {
            grayFull.release()
            image.close()
        }
    }

    private fun percentile(m: Mat, p: Double): Double {
        val histSize = 256
        val hist = Mat()
        val ranges = MatOfFloat(0f, 32f)
        Imgproc.calcHist(listOf(m), MatOfInt(0), Mat(), hist, MatOfInt(histSize), ranges)
        val total = Core.sumElems(hist).`val`[0].coerceAtLeast(1.0)
        val target = total * (p / 100.0).coerceIn(0.0, 1.0)
        var acc = 0.0
        var idx = 0
        while (idx < histSize) {
            acc += hist.get(idx, 0)[0]
            if (acc >= target) break
            idx++
        }
        hist.release(); ranges.release()
        val maxRange = 32.0
        return (idx / histSize.toDouble()) * maxRange
    }

    private fun medianOfMat(m: Mat): Double {
        val flat = Mat()
        m.reshape(1, 1).copyTo(flat)
        val arr = DoubleArray(flat.cols()) { i -> flat.get(0, i)[0] }
        arr.sort()
        val med = if (arr.isEmpty()) 0.0 else arr[arr.size / 2]
        flat.release()
        return med
    }
}
