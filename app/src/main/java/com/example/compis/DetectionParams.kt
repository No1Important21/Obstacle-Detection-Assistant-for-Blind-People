package com.example.compis

data class DetectionParams(
    val step: Int = 12,
    val percentile: Double = 90.0,
    val strongMul: Double = 2.0,
    val radialMin: Double = 0.25,
    val enterSide: Double = 0.14,
    val enterCenter: Double = 0.18,
    val centerMargin: Double = 0.04,
    val forwardMeanMin: Double = 0.12,
    val bottomWeight: Int = 2,
    val roiTopFrac: Double = 0.25,
    val roiBottomFrac: Double = 0.85,
    val inputWidth: Int = 320,
    val speak: Boolean = true
)
