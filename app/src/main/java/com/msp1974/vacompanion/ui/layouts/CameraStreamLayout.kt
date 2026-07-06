package com.msp1974.vacompanion.ui.layouts

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.msp1974.vacompanion.device.MotionDetectionEngine
import com.msp1974.vacompanion.device.MotionDetectionMode
import com.msp1974.vacompanion.device.MotionResult
import com.msp1974.vacompanion.ui.VAViewModel
import android.graphics.RectF
import timber.log.Timber
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CameraStreamLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val executor = remember { Executors.newSingleThreadExecutor() }
    val motionEngine = remember { MotionDetectionEngine() }
    var currentResult by remember { mutableStateOf<MotionResult?>(null) }
    
    val vaUiState by viewModel.vacaState.collectAsState()

    var showMotionIndicator by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Track if we are ready to start the camera (after background stop)
    var isCameraReady by remember { mutableStateOf(false) }

    // PreviewView reference to allow binding after inflation
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }

    // Ensure screen stays on while viewing the stream
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    // Apply sensitivity whenever it changes
    LaunchedEffect(vaUiState.motionDetectionSensitivity) {
        motionEngine.setSensitivity(vaUiState.motionDetectionSensitivity)
    }

    LaunchedEffect(vaUiState.motionDetectionMode) {
        motionEngine.detectorMode = if (vaUiState.motionDetectionMode == "face") MotionDetectionMode.FACE_DETECTION else MotionDetectionMode.PIXEL_DIFF
    }

    // Camera Provider state to handle safe unbinding on exit
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        viewModel.setCameraStreamActive(true)
        scope.launch {
            delay(500) // Give background task time to stop
            isCameraReady = true
        }
        onDispose {
            viewModel.setCameraStreamActive(false)
            hideJob?.cancel()
            executor.shutdown()
            motionEngine.close()
            // Explicitly unbind everything to prevent CAMERA_DISCONNECTED errors on exit
            runCatching {
                cameraProviderRef.value?.unbindAll()
            }
        }
    }

    // Collect motion results for visualization and detection status
    LaunchedEffect(motionEngine) {
        motionEngine.motionFlow.collect { result ->
            currentResult = result
            if (result.hasMotion) {
                showMotionIndicator = true
                hideJob?.cancel()
                hideJob = launch {
                    delay(1000)
                    showMotionIndicator = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera Preview
        if (isCameraReady) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        previewViewRef.value = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { } 
            )
            
            // Handle camera binding once when ready and view is inflated
            LaunchedEffect(previewViewRef.value) {
                val previewView = previewViewRef.value
                if (isCameraReady && previewView != null) {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProviderRef.value = cameraProvider

                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(executor) { image ->
                                        if (vaUiState.motionDetectionMode == "face") {
                                            scope.launch {
                                                try {
                                                    motionEngine.processImageProxy(image)
                                                } finally {
                                                    image.close()
                                                }
                                            }
                                        } else {
                                            val planes = image.planes
                                            val buffer = planes[0].buffer
                                            val data = ByteArray(buffer.remaining())
                                            buffer.get(data)

                                            val luma = IntArray(data.size) { data[it].toInt() and 0xFF }
                                            val width = image.width
                                            val height = image.height
                                            val rotation = image.imageInfo.rotationDegrees

                                            scope.launch {
                                                motionEngine.processFrame(luma, width, height, rotation)
                                            }

                                            image.close()
                                        }
                                    }
                                }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (ex: Exception) {
                            Timber.e("Camera binding failed: $ex")
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            }
        }

        // Motion Visualization Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            currentResult?.let { result ->
                result.boundingBoxes.forEach { rect ->
                    // Transform normalized coordinates based on rotation and front-camera mirroring
                    val rotatedRect = transformRect(rect, result.rotation)
                    
                    val left = rotatedRect.left
                    val top = rotatedRect.top
                    val right = rotatedRect.right
                    val bottom = rotatedRect.bottom

                    drawRect(
                        color = Color.Red,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            x = left * size.width,
                            y = top * size.height
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            width = (right - left) * size.width,
                            height = (bottom - top) * size.height
                        ),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // Overlay Header with Back Button
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart),
            color = Color.Black.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ArrowLeft,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = "Camera Stream",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                    color = Color.White
                )
                
                if (showMotionIndicator) {
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        color = Color.Red,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = vaUiState.motionDetectionMode.uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun transformRect(rect: RectF, rotation: Int): RectF {
    // 1. Start with SENSOR coordinates (normalized 0..1)
    // For front camera, we mirror the sensor X axis FIRST.
    // This is because the camera itself is a mirror.
    val cx1 = 1f - rect.right
    val cy1 = rect.top
    val cx2 = 1f - rect.left
    val cy2 = rect.bottom
    
    // 2. Now apply rotation to the mirrored sensor coordinates to match display
    return when (rotation) {
        90 -> RectF(1f - cy2, cx1, 1f - cy1, cx2)
        180 -> RectF(1f - cx2, 1f - cy2, 1f - cx1, 1f - cy1)
        270 -> RectF(cy1, 1f - cx2, cy2, 1f - cx1)
        else -> RectF(cx1, cy1, cx2, cy2)
    }
}
