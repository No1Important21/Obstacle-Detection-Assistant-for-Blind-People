package com.example.compis

import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Core

object Yuv {
    fun toGrayMat(image: ImageProxy): Mat? {
        val w = image.width
        val h = image.height
        val yPlane = image.planes.getOrNull(0) ?: return null
        val rowStride = yPlane.rowStride

        val buf = yPlane.buffer
        buf.rewind()
        val all = ByteArray(buf.remaining())
        buf.get(all)

        val mat = Mat(h, w, CvType.CV_8UC1)
        var src = 0
        for (r in 0 until h) {
            mat.put(r, 0, all, src, w)
            src += rowStride
        }

        val rotated = Mat()
        when (image.imageInfo.rotationDegrees) {
            0 -> return mat
            90 -> { Core.rotate(mat, rotated, Core.ROTATE_90_CLOCKWISE); mat.release(); return rotated }
            180 -> { Core.rotate(mat, rotated, Core.ROTATE_180); mat.release(); return rotated }
            270 -> { Core.rotate(mat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE); mat.release(); return rotated }
            else -> return mat
        }
    }
}
