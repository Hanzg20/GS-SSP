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
import androidx.compose.material.icons.filled.Mic
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
import com.goldsky.ssp.feature.retail.ui.EmptyArt
import com.goldsky.ssp.feature.retail.ui.EmptyState
import com.goldsky.ssp.ui.theme.GsTheme
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.ExpenseRepository
import com.goldsky.ssp.payment.StaffRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * The store's expenses, the Journal's third tab -- the same cloud records 小海狸
 * books by voice (ExpenseRepository): added here by hand or from a receipt photo,
 * listed from the cloud (offline copy when there's no connection), visible in CMP.
 */
@Composable
fun ExpensesPane(onScanReceipt: () -> Unit) {
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

    Box(Modifier.fillMaxSize()) {
        if (isLoading) {
            JournalSkeleton()
        } else if (expenses.isEmpty()) {
            EmptyState(EmptyArt.RECEIPT, stringResource(R.string.retail_no_expenses_recorded), stringResource(R.string.journal_expense_hint),
                Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                // Room under the last row for the buttons.
                contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp)
            ) {
                items(expenses, key = { it.id }) { expense ->
                    ExpenseRow(expense)
                }
            }
        }
        // Typed in, or from a receipt photo (the bigger, more common one).
        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { adding = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.retail_add_expense)) }
            FloatingActionButton(
                onClick = onScanReceipt,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.retail_scan_receipt))
            }
        }
    }
}

/** One expense, a row like a sale in Records: how it was booked, amount, date · category · paid to · by. */
@Composable
private fun ExpenseRow(expense: ExpenseEntity) {
    val context = LocalContext.current
    val dateStr = remember(expense.createdAt, expense.spentOn) {
        expense.spentOn ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(expense.createdAt))
    }
    JournalRow(
        icon = when (expense.source) { "voice" -> Icons.Default.Mic; "receipt" -> Icons.Default.CameraAlt; else -> Icons.Default.Receipt },
        title = "$" + "%.2f".format(expense.amountCents / 100.0),
        subtitle = listOfNotNull(dateStr, ExpenseRepository.label(context, expense.category),
            expense.vendor.ifBlank { expense.note }?.ifBlank { null }, expense.staffName).joinToString(" · "),
        trailing = if (expense.synced) null else {
            { JournalChip(stringResource(R.string.journal_not_uploaded), GsTheme.colors.warning) }
        }
    )
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
