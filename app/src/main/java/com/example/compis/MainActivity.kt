package com.example.compis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.opencv.android.OpenCVLoader
import java.util.Locale
import java.util.concurrent.Executors
import androidx.compose.ui.graphics.Color as CColor

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech

    private val flowExecutor = Executors.newSingleThreadExecutor()
    private val mlExecutor   = Executors.newSingleThreadExecutor()

    private val lastAnnounceById = mutableMapOf<Int, Long>()
    private var lastGlobalAnnounce = 0L
    private val announceCooldownMs = 3000L

    private val ROI_TOP_FRAC = 0.10
    private val ROI_BOTTOM_FRAC = 0.50

    private val PERCENTILE     = 90.0
    private val CENTER_MARGIN  = 0.05
    private val ENTER_CENTER   = 0.18
    private val ENTER_SIDE     = 0.12
    private val STRONG_MUL     = 2.0
    private val RADIAL_MIN     = 0.25
    private val BOTTOM_WEIGHT  = 2
    private val FORWARD_MEAN   = 0.06
    private val STEP           = 10
    private val INPUT_WIDTH    = 480

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        OpenCVLoader.initDebug()
        tts = TextToSpeech(this, this)

        setContent {
            MaterialTheme {
                val previewView = remember { PreviewView(this@MainActivity) }

                var detBoxes by remember { mutableStateOf(emptyList<DetBox>()) }
                val mlAnalyzer = remember {
                    MlKitObjectAnalyzer(
                        onObjects  = { boxes -> detBoxes = boxes },
                        minBoxArea = 0.03f
                    )
                }

                var status by remember { mutableStateOf("Menyiapkan kamera…") }
                var color by remember { mutableStateOf(CColor.Yellow) }
                var roiTop by remember { mutableStateOf(0) }
                var roiBottom by remember { mutableStateOf(0) }

                var fps by remember { mutableFloatStateOf(0f) }
                var lastTs by remember { mutableLongStateOf(0L) }
                val fpsAlpha = 0.2f

                var speakOn by remember { mutableStateOf(true) }

                val getParams = {
                    DetectionParams(
                        percentile     = PERCENTILE,
                        centerMargin   = CENTER_MARGIN,
                        enterCenter    = ENTER_CENTER,
                        enterSide      = ENTER_SIDE,
                        strongMul      = STRONG_MUL,
                        radialMin      = RADIAL_MIN,
                        bottomWeight   = BOTTOM_WEIGHT,
                        forwardMeanMin = FORWARD_MEAN,
                        step           = STEP,
                        roiTopFrac     = ROI_TOP_FRAC,
                        roiBottomFrac  = ROI_BOTTOM_FRAC,
                        inputWidth     = INPUT_WIDTH,
                        speak          = speakOn
                    )
                }

                val flowAnalyzer = remember {
                    OpticalFlowAnalyzer(
                        getParams = getParams,
                        onResult = { res ->
                            val now = System.currentTimeMillis()
                            if (lastTs != 0L) {
                                val dt = (now - lastTs).coerceAtLeast(1L)
                                val inst = 1000f / dt.toFloat()
                                fps = fps * (1f - fpsAlpha) + inst * fpsAlpha
                            }
                            lastTs = now

                            val fused = fuseWithMlKit(res.statusText, detBoxes)
                            val finalText = fused?.first ?: res.statusText
                            val ttsText   = fused?.second ?: res.ttsToSpeak

                            status = finalText
                            color = CColor(
                                ((res.statusColor shr 16) and 0xFF) / 255f,
                                ((res.statusColor shr 8) and 0xFF) / 255f,
                                (res.statusColor and 0xFF) / 255f
                            )
                            roiTop = res.roiTop
                            roiBottom = res.roiBottom

                            if (speakOn) ttsText?.let { speak(it) }
                        }
                    )
                }

                var hasPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }
                val permissionLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        hasPermission = granted
                        status = if (granted) "Mengaktifkan kamera…" else "Izin kamera ditolak"
                    }
                LaunchedEffect(Unit) {
                    if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
                }
                LaunchedEffect(hasPermission) {
                    if (hasPermission) {
                        startCamera(previewView, flowAnalyzer, mlAnalyzer, this@MainActivity)
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                        OverlayComposable(
                            status = status,
                            statusColor = color,
                            roiTop = roiTop,
                            roiBottom = roiBottom,
                            fps = fps,
                            boxes = detBoxes
                        )
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TTS", fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = speakOn,
                                onCheckedChange = { speakOn = it },
                                modifier = Modifier.height(18.dp)
                            )
                        }
                        Text(
                            "FPS: ${"%.1f".format(fps)}",
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    private fun fuseWithMlKit(
        baseText: String,
        boxes: List<DetBox>
    ): Pair<String?, String?>? {
        if (boxes.isEmpty()) return null

        val now = System.currentTimeMillis()
        val centerL = 1f / 3f
        val centerR = 2f / 3f

        val near = boxes.filter { it.isApproaching && it.area > 0.02f }
        if (near.isEmpty()) return null

        val leftHit   = near.any { it.centerX < centerL }
        val centerHit = near.any { it.centerX in centerL..centerR }
        val rightHit  = near.any { it.centerX > centerR }

        val uiText = when {
            centerHit -> "Objek mendekat di depan, berhenti"
            leftHit   -> "Objek mendekat di kiri, belok ke kanan"
            rightHit  -> "Objek mendekat di kanan, belok ke kiri"
            else      -> null
        } ?: return null

        val idForCooldown = near.firstOrNull { it.trackingId != null }?.trackingId
        val ttsText = if (canAnnounce(idForCooldown, now)) uiText else null
        return uiText to ttsText
    }

    private fun canAnnounce(id: Int?, now: Long): Boolean {
        if (id == null) {
            val ok = now - lastGlobalAnnounce > announceCooldownMs
            if (ok) lastGlobalAnnounce = now
            return ok
        }
        val last = lastAnnounceById[id] ?: 0L
        val ok = now - last > announceCooldownMs
        if (ok) lastAnnounceById[id] = now
        return ok
    }

    private fun startCamera(
        previewView: PreviewView,
        flowAnalyzer: ImageAnalysis.Analyzer,
        mlAnalyzer: ImageAnalysis.Analyzer,
        owner: LifecycleOwner
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysisFlow = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(flowExecutor, flowAnalyzer) }

            val analysisMl = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(mlExecutor, mlAnalyzer) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysisFlow,
                    analysisMl
                )
                Log.i("CameraX", "Camera bound OK (flow + ml)")
            } catch (e: Exception) {
                Log.e("CameraX", "Bind failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun speak(text: String) {
        if (::tts.isInitialized) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_nav")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("id", "ID")
            tts.setSpeechRate(1.0f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        flowExecutor.shutdown()
        mlExecutor.shutdown()
    }
}
