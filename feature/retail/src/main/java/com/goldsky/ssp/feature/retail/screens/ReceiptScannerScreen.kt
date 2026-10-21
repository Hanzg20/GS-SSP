package com.goldsky.ssp.feature.retail.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goldsky.ssp.common.FeedbackManager
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.payment.ExpenseRepository
import com.goldsky.ssp.payment.ReceiptParser
import com.goldsky.ssp.payment.ReceiptParser.Flag
import com.goldsky.ssp.payment.StaffRepository
import com.goldsky.ssp.ui.theme.GsTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** One receipt photo, read: the file on this terminal, its text, and the fields found in it. */
private class Captured(val file: File, val preview: Bitmap, val text: String, val fields: ReceiptParser.Fields)

/**
 * A supplier receipt as an expense (the Journal, 2026-10-20). One photo per receipt -- a
 * long one folded so its head (store, date, HST number) meets its foot (total, tax). The
 * terminal reads store, date, total and GST/HST off it (Chinese and English), the owner
 * checks them -- anything not found or not adding up is marked -- and only then it is saved,
 * with the photo kept as the record (storage bucket `receipts`, never deleted).
 */
@Composable
fun ReceiptScannerScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    // Chinese model: reads Chinese and Latin text, so mixed receipts come out whole.
    val recognizer = remember { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    var busy by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<Captured?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val readFailed = stringResource(R.string.receipt_read_failed)

    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown(); recognizer.close() } }

    fun shoot() {
        if (busy) return
        busy = true; error = null
        imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = runCatching { image.toUprightBitmap() }.getOrNull()
                image.close()
                scope.launch {
                    val result = bitmap?.let { runCatching { read(context, it, recognizer) }.onFailure { Log.e("ReceiptScanner", "read failed", it) }.getOrNull() }
                    busy = false
                    if (result == null) error = readFailed else { FeedbackManager.success(context); captured = result }
                }
            }
            override fun onError(e: ImageCaptureException) {
                Log.e("ReceiptScanner", "capture failed", e)
                scope.launch { busy = false; error = readFailed }
            }
        })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        }.onFailure { Log.e("ReceiptScanner", "camera bind failed", it) }
                    }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // How to hold a long receipt.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.receipt_hint_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(stringResource(R.string.receipt_hint), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        }
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 4.dp)) {
            Icon(Icons.Default.ArrowBack, stringResource(R.string.retail_back), tint = Color.White)
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            error?.let { Text(it, color = Color.White, modifier = Modifier.background(GsTheme.colors.danger, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            // Shutter.
            Box(
                Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(6.dp).clip(CircleShape)
                    .background(if (busy) Color.Gray else Color.White).clickable(enabled = !busy) { shoot() },
                contentAlignment = Alignment.Center
            ) { if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = Color.Black, strokeWidth = 3.dp) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(if (busy) R.string.receipt_reading else R.string.receipt_shoot), color = Color.White, fontSize = 13.sp)
        }
    }

    captured?.let { c ->
        ReceiptConfirm(
            c,
            onRetake = { runCatching { c.file.delete() }; captured = null },
            onSaved = onDone
        )
    }
}

/** Upright bitmap from the capture (JPEG bytes + the rotation the camera reports). */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return raw
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}

/** Reads the full-size photo, keeps a 1600 px JPEG of it as the record, and finds the fields. */
private suspend fun read(context: android.content.Context, photo: Bitmap, recognizer: com.google.mlkit.vision.text.TextRecognizer): Captured {
    val result = kotlinx.coroutines.suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
        recognizer.process(InputImage.fromBitmap(photo, 0))
            .addOnSuccessListener { cont.resumeWith(Result.success(it)) }
            .addOnFailureListener { cont.resumeWith(Result.failure(it)) }
    }
    val lines = result.textBlocks.flatMap { b -> b.lines }.mapNotNull { l ->
        val box = l.boundingBox ?: return@mapNotNull null
        ReceiptParser.OcrLine(l.text, box.left, box.top, box.right, box.bottom)
    }
    val rows = ReceiptParser.rows(lines)
    var fields = ReceiptParser.parseRows(rows, ExpenseRepository.today())
    // No total by the rules: 小海狸 reads the text (managers only; its amounts must be printed
    // on the receipt). Offline or refused: the owner types what's missing, as before.
    val staff = StaffRepository.current
    if (ReceiptParser.Flag.TOTAL_MISSING in fields.flags && ReceiptParser.Flag.REFUND !in fields.flags && staff?.isManager == true && rows.isNotEmpty()) {
        val ai = com.goldsky.ssp.payment.ApexRepository.readReceipt(context, staff.id, rows.joinToString("\n"))
        if (ai is com.goldsky.ssp.payment.ApexRepository.Result.Receipt) {
            fields = ReceiptParser.merge(fields, ReceiptParser.Fields(ai.vendor, ai.date, ai.totalCents, ai.taxCents, null, ai.hstNumber, emptySet()))
        }
    }
    return withContext(Dispatchers.IO) {
        val scale = minOf(1f, 1600f / maxOf(photo.width, photo.height))
        val stored = if (scale < 1f) Bitmap.createScaledBitmap(photo, (photo.width * scale).toInt(), (photo.height * scale).toInt(), true) else photo
        val file = ExpenseRepository.newReceiptFile(context)
        file.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        Captured(file, stored, rows.joinToString("\n"), fields)
    }
}

private fun cents(text: String): Int? = text.trim().removePrefix("$").replace(",", "").toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
private fun dollars(cents: Int?): String = cents?.let { "%.2f".format(it / 100.0) } ?: ""

/**
 * What was read, for the owner to check and correct before it is booked: the photo, store,
 * date, total, GST/HST, category. Marked: what wasn't found or doesn't add up, and why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptConfirm(c: Captured, onRetake: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = c.fields
    var vendor by remember { mutableStateOf(f.vendor ?: "") }
    var date by remember { mutableStateOf(f.date ?: ExpenseRepository.today()) }
    var total by remember { mutableStateOf(dollars(f.totalCents)) }
    var tax by remember { mutableStateOf(dollars(f.taxCents)) }
    var category by remember { mutableStateOf("INVENTORY") }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val warn = GsTheme.colors.warning
    val badAmount = stringResource(R.string.receipt_err_amount)
    val badDate = stringResource(R.string.receipt_err_date)

    @Composable
    fun label(text: String, flagged: Boolean): @Composable () -> Unit = {
        Text(if (flagged) text + " · " + stringResource(R.string.receipt_check) else text, color = if (flagged) warn else Color.Unspecified)
    }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.receipt_confirm_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetake, enabled = !saving) { Text(stringResource(R.string.receipt_retake)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The photo is the record: tap to read it.
                    Image(
                        c.preview.asImageBitmap(), stringResource(R.string.receipt_photo),
                        modifier = Modifier.width(110.dp).heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).clickable { zoom = true },
                        contentScale = ContentScale.Fit
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        f.hstNumber?.let { Text(stringResource(R.string.receipt_hst_number, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        // Why something is marked, in plain words.
                        listOfNotNull(
                            R.string.receipt_flag_refund.takeIf { Flag.REFUND in f.flags },
                            R.string.receipt_flag_total_missing.takeIf { Flag.TOTAL_MISSING in f.flags && Flag.REFUND !in f.flags },
                            R.string.receipt_flag_mismatch.takeIf { Flag.TOTAL_MISMATCH in f.flags },
                            R.string.receipt_flag_tax_missing.takeIf { Flag.TAX_MISSING in f.flags },
                            R.string.receipt_flag_tax_odd.takeIf { Flag.TAX_ODD in f.flags },
                            R.string.receipt_flag_date_missing.takeIf { Flag.DATE_MISSING in f.flags },
                            R.string.receipt_flag_hst_number.takeIf { Flag.HST_NUMBER_MISSING in f.flags },
                            R.string.receipt_flag_large.takeIf { Flag.LARGE_PURCHASE in f.flags },
                            R.string.receipt_flag_ai.takeIf { Flag.AI_FILLED in f.flags },
                        ).forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodySmall, color = warn) }
                        if (f.flags.isEmpty()) Text(stringResource(R.string.receipt_all_found), style = MaterialTheme.typography.bodySmall, color = GsTheme.colors.success)
                    }
                }
                OutlinedTextField(vendor, { vendor = it.take(120) }, Modifier.fillMaxWidth(), label = label(stringResource(R.string.retail_paid_to), f.vendor == null), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(total, { total = it.filter { ch -> ch.isDigit() || ch == '.' }.take(10) }, Modifier.weight(1f),
                        label = label(stringResource(R.string.receipt_total), Flag.TOTAL_MISSING in f.flags || Flag.TOTAL_MISMATCH in f.flags), singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(tax, { tax = it.filter { ch -> ch.isDigit() || ch == '.' }.take(10) }, Modifier.weight(1f),
                        label = label(stringResource(R.string.receipt_tax), Flag.TAX_MISSING in f.flags || Flag.TAX_ODD in f.flags), singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                OutlinedTextField(date, { date = it.take(10) }, Modifier.fillMaxWidth(), label = label(stringResource(R.string.retail_date), Flag.DATE_MISSING in f.flags), singleLine = true)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ExpenseRepository.CATEGORIES.size) { i ->
                        val cat = ExpenseRepository.CATEGORIES[i]
                        FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(ExpenseRepository.label(context, cat)) })
                    }
                }
                OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.retail_note)) }, singleLine = true)
                error?.let { Text(it, color = GsTheme.colors.danger) }
                Button(
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = {
                        val amount = cents(total)
                        val taxCents = tax.takeIf { it.isNotBlank() }?.let { cents(it) ?: -1 }
                        if (amount == null || amount <= 0 || (taxCents != null && (taxCents < 0 || taxCents >= amount))) { error = badAmount; return@Button }
                        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) { error = badDate; return@Button }
                        saving = true
                        scope.launch {
                            ExpenseRepository.record(
                                context, vendor, category, amount, date, note, "receipt", StaffRepository.current,
                                taxCents = taxCents, receiptFile = c.file.absolutePath, receiptText = c.text
                            )
                            saving = false
                            onSaved()
                        }
                    }
                ) { Text(stringResource(if (saving) R.string.receipt_saving else R.string.receipt_save), fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (zoom) ReceiptPhotoDialog(c.preview) { zoom = false }
}

/** A receipt photo full screen, pinch to zoom, to read what the fields came from. */
@Composable
fun ReceiptPhotoDialog(photo: Bitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                photo.asImageBitmap(), null,
                modifier = Modifier.fillMaxSize()
                    .pointerInputTransform { zoomChange, pan -> scale = (scale * zoomChange).coerceIn(1f, 5f); offset += pan }
                    .graphicsLayerZoom(scale, offset),
                contentScale = ContentScale.Fit
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
        }
    }
}

private fun Modifier.pointerInputTransform(onTransform: (Float, androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    this.then(Modifier.pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ -> onTransform(zoom, pan) }
    })

private fun Modifier.graphicsLayerZoom(scale: Float, offset: androidx.compose.ui.geometry.Offset): Modifier =
    this.then(Modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
