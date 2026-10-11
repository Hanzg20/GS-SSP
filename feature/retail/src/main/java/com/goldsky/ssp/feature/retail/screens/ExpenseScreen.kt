package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.db.ExpenseEntity
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.feature.retail.ui.GoldSkyCyan
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.ExpenseRepository
import com.goldsky.ssp.payment.StaffRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * The store's expenses -- the same cloud records 小海狸 books by voice
 * (ExpenseRepository): added here by hand or from a receipt photo, listed from
 * the cloud (offline copy when there's no connection), visible in CMP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseScreen(onScanReceipt: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expenses by remember { mutableStateOf<List<ExpenseEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            expenses = ExpenseRepository.list(context)
            isLoading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    if (adding) {
        AddExpenseDialog(onDismiss = { adding = false }, onSaved = { adding = false; reload() })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.retail_expenses), fontWeight = FontWeight.Black) },
                actions = {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.retail_add_expense)) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onScanReceipt,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.retail_scan_receipt))
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (expenses.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Receipt, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.retail_no_expenses_recorded), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { adding = true },
                    modifier = Modifier.padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Text(stringResource(R.string.retail_add_manually), fontWeight = FontWeight.Bold)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(expenses) { expense ->
                    ExpenseCard(expense)
                }
            }
        }
    }
}

@Composable
fun ExpenseCard(expense: ExpenseEntity) {
    val dateStr = remember(expense.createdAt, expense.spentOn) {
        expense.spentOn ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(expense.createdAt))
    }
    val via = when (expense.source) { "voice" -> " · 🎙"; "receipt" -> " · 📷"; else -> "" }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.vendor.ifBlank { expense.note ?: "" }.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "$${"%.2f".format(expense.amountCents / 100.0)}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$dateStr • ${ExpenseRepository.label(LocalContext.current, expense.category)}$via" +
                        (expense.staffName?.let { " • $it" } ?: "") + (if (expense.synced) "" else " • " + stringResource(R.string.retail_not_uploaded_yet)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (expense.imageUrl != null) {
                Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Manual entry: amount, date, who was paid, category, note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var spentOn by remember { mutableStateOf(ExpenseRepository.today()) }
    var vendor by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("INVENTORY") }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.retail_add_expense)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, Modifier.weight(1f),
                        label = { Text("Amount $") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    OutlinedTextField(spentOn, { spentOn = it.take(10) }, Modifier.weight(1f), label = { Text(stringResource(R.string.retail_date)) }, singleLine = true)
                }
                OutlinedTextField(vendor, { vendor = it.take(120) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.retail_paid_to)) }, singleLine = true)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ExpenseRepository.CATEGORIES) { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(ExpenseRepository.label(LocalContext.current, c)) })
                    }
                }
                OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.retail_note)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                val cents = amount.toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
                if (cents == null || cents <= 0) { error = "Enter the amount"; return@Button }
                if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(spentOn)) { error = "Date as 2026-10-10"; return@Button }
                saving = true
                scope.launch {
                    ExpenseRepository.record(context, vendor, category, cents, spentOn, note, "manual", StaffRepository.current)
                    saving = false
                    onSaved()
                }
            }) { Text(if (saving) "SAVING..." else "SAVE", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.retail_cancel)) } }
    )
}
