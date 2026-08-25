package com.goldsky.ssp.ui.screens

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.goldsky.ssp.payment.FeedbackManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

@Composable
fun ReceiptScannerScreen(
    onReceiptCaptured: (String, Int) -> Unit, // vendor, amount
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    var isProcessing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }.also { previewView ->
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    if (!isProcessing) {
                                        processImage(imageProxy, recognizer) { vendor, amount ->
                                            if (amount > 0) {
                                                isProcessing = true
                                                FeedbackManager.emitScanFeedback(context)
                                                onReceiptCaptured(vendor, amount)
                                            }
                                        }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                            }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalyzer
                            )
                        } catch (e: Exception) {
                            Log.e("Scanner", "Camera binding failed", e)
                        }
                    }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay: Scanning Frame
        ScannerOverlay()

        // Controls
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            ) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isProcessing) "PROCESSING..." else "AI SCANNING FOR TOTAL",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ScannerOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val frameWidth = width * 0.8f
        val frameHeight = height * 0.4f
        val left = (width - frameWidth) / 2
        val top = (height - frameHeight) / 2

        // Darken outside
        drawRect(color = Color.Black.copy(alpha = 0.5f))
        drawRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(frameWidth, frameHeight),
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear
        )
        
        // Gold Frame
        drawRect(
            color = Color(0xFFD4AF37),
            topLeft = Offset(left, top),
            size = Size(frameWidth, frameHeight),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImage(
    imageProxy: ImageProxy,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    onResult: (String, Int) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                var detectedVendor = "Merchant"
                var detectedAmount = 0
                
                val textBlocks = visionText.textBlocks
                for (block in textBlocks) {
                    for (line in block.lines) {
                        val text = line.text
                        // Try to find vendor
                        if (detectedVendor == "Merchant" && text.length > 3 && !text.any { it.isDigit() }) {
                            detectedVendor = text
                        }
                        
                        // Look for amount
                        val amountMatch = Regex("""\d+\.\d{2}""").find(text)
                        if (amountMatch != null) {
                            val value = amountMatch.value.toDoubleOrNull()
                            if (value != null && (value * 100).toInt() > detectedAmount) {
                                detectedAmount = (value * 100).toInt()
                            }
                        }
                    }
                }
                
                if (detectedAmount > 0) {
                    onResult(detectedVendor, detectedAmount)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
