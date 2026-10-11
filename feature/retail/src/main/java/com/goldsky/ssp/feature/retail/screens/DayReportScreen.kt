package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.layout.*
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
import com.goldsky.ssp.ui.theme.GsTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.payment.ReceiptPrinterManager
import com.goldsky.ssp.payment.DayReport
import com.goldsky.ssp.payment.DayReportRepository
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.feature.retail.ui.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayReportScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedCalendar by remember { mutableStateOf(Calendar.getInstance()) }
    var basis by remember { mutableStateOf(DayReport.Basis.REFUND_DATE) }
    var isLoading by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<DayReport?>(null) }
    var isPrinting by remember { mutableStateOf(false) }

    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd (EEE)", Locale.US) }
    val dayMillis = selectedCalendar.timeInMillis

    fun loadReport() {
        scope.launch {
            isLoading = true
            val (startMs, endMs) = DayReportRepository.dayBounds(dayMillis)
            // Timestamps come back in UTC; a "yyyy-MM-dd" prefix test dropped the
            // evening (after 20:00 in Toronto) and took in the previous evening.
            val inDay = { ts: String -> DayReportRepository.parseIsoMillis(ts)?.let { it in startMs until endMs } == true }
            val startIso = DayReportRepository.isoUtc(startMs)
            val endIso = DayReportRepository.isoUtc(endMs)
            val deviceSn = DeviceRepository.getPersistedDeviceSn() ?: ""

            val fetched = DayReportRepository.fetch(deviceSn, startIso, endIso)
            if (fetched != null) {
                val (rows, items) = fetched
                report = DayReport.build(rows, items, inDay = inDay, basis = basis)
            } else {
                report = null
            }
            isLoading = false
        }
    }

    LaunchedEffect(dayMillis, basis) {
        loadReport()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.retail_day_settlement_report), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.retail_back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = report != null && !isPrinting,
                        onClick = {
                            report?.let { r ->
                                scope.launch {
                                    isPrinting = true
                                    val vendor = DeviceRepository.getPersistedHardwareVendor()
                                    val formattedLines = formatReportForPrint(r, dateFmt.format(selectedCalendar.time), basis)
                                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        ReceiptPrinterManager.printLines(context, formattedLines, vendor)
                                    }
                                    isPrinting = false
                                    if (!ok) android.widget.Toast.makeText(context, context.getString(R.string.retail_print_failed), android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        if (isPrinting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Default.Print, contentDescription = stringResource(R.string.retail_print_report))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Date Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val c = selectedCalendar.clone() as Calendar
                    c.add(Calendar.DAY_OF_MONTH, -1)
                    selectedCalendar = c
                }) {
                    Icon(Icons.Default.ChevronLeft, stringResource(R.string.retail_previous_day), tint = MaterialTheme.colorScheme.primary)
                }

                Text(
                    text = dateFmt.format(selectedCalendar.time),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                val isToday = DayReportRepository.dayBounds(dayMillis).second > System.currentTimeMillis()
                IconButton(enabled = !isToday, onClick = {
                    val c = selectedCalendar.clone() as Calendar
                    c.add(Calendar.DAY_OF_MONTH, 1)
                    selectedCalendar = c
                }) {
                    Icon(Icons.Default.ChevronRight, stringResource(R.string.retail_next_day), tint = MaterialTheme.colorScheme.primary)
                }
            }

            // 2. Basis Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = basis == DayReport.Basis.REFUND_DATE,
                    onClick = { basis = DayReport.Basis.REFUND_DATE },
                    label = { Text(stringResource(R.string.retail_refund_date_drawer)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = basis == DayReport.Basis.SALE_DATE,
                    onClick = { basis = DayReport.Basis.SALE_DATE },
                    label = { Text(stringResource(R.string.retail_sale_date_net)) }
                )
            }

            // 3. Content
            if (isLoading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (report == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.retail_could_not_fetch_report_data), color = MaterialTheme.colorScheme.error)
                }
            } else {
                val r = report!!

                // Summary Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.retail_summary), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        ReportRow("Sales (${r.sales.count} txns)", "$${"%.2f".format(r.sales.cents / 100.0)}")
                        ReportRow("Refunds (${r.refunds.size} txns)", "-$${"%.2f".format(r.refundCents / 100.0)}", color = GsTheme.colors.danger)
                        Divider(color = MaterialTheme.colorScheme.outline)
                        ReportRow("Net Sales", "$${"%.2f".format(r.netCents / 100.0)}", isBold = true)
                        ReportRow("Expected Cash (Drawer)", "$${"%.2f".format(r.expectedCashCents / 100.0)}", isBold = true, color = GsTheme.colors.emphasis)
                    }
                }

                // Payment Method Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.retail_by_payment_method), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        if (r.byMethod.isEmpty()) {
                            Text(stringResource(R.string.retail_no_transactions), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            r.byMethod.forEach { (method, totals) ->
                                ReportRow(method, "${totals.count} txns · $${"%.2f".format(totals.cents / 100.0)}")
                            }
                        }
                    }
                }

                // Staff Breakdown Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.retail_by_staff), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        if (r.byStaff.isEmpty()) {
                            Text(stringResource(R.string.retail_no_staff_data), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            r.byStaff.forEach { (staff, totals) ->
                                ReportRow("$staff (${totals.count})", money(totals.cents), isBold = true)
                                ReportRow("   cash / card", "${money(totals.cashCents)} / ${money(totals.cardCents)}")
                                if (totals.refundCents > 0) ReportRow("   refunds", "-" + money(totals.refundCents), color = GsTheme.colors.danger)
                            }
                        }
                    }
                }

                // Tax & Tips Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.retail_tax_tips), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        ReportRow("Total Tax", "$${"%.2f".format(r.taxCents / 100.0)}")
                        ReportRow("Total Tips", "$${"%.2f".format(r.tipCents / 100.0)}")
                        ReportRow("Taxable Amount", "$${"%.2f".format(r.taxableCents / 100.0)}")
                        ReportRow("Exempt Amount", "$${"%.2f".format(r.exemptCents / 100.0)}")
                    }
                }

                // Refunds & voids, one per line (owner asked for time, amount, method, approver)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.retail_refunds_voids), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        if (r.refunds.isEmpty()) {
                            Text(stringResource(R.string.retail_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            r.refunds.forEach { f ->
                                ReportRow("${localTime(f.at)}  ${methodShort(f.method)} " + stringResource(if (f.status == "VOIDED") R.string.retail_void else R.string.retail_refund_word), "-" + money(f.cents))
                                Text("   " + stringResource(R.string.retail_approved_by_sale_by, f.approvedBy ?: "-", f.staff ?: "-"), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportRow(
    label: String,
    value: String,
    isBold: Boolean = false,
    color: Color = Color.Unspecified
) {
    val finalColor = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = finalColor, fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal)
        Text(value, color = finalColor, fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun money(cents: Int) = "$%.2f".format(cents / 100.0)

private fun methodShort(m: String) = when (m) {
    "CASH" -> "Cash"; "CREDIT_CARD" -> "Credit"; "DEBIT_CARD" -> "Debit"; "VIP_CARD" -> "VIP"; else -> m
}

private fun localTime(iso: String): String =
    DayReportRepository.parseIsoMillis(iso)?.let { SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(it)) } ?: iso

/** 32 columns, label left / amount right (ASCII; staff names are on their own line). */
private fun line(left: String, right: String): String =
    left + " ".repeat((32 - left.length - right.length).coerceAtLeast(1)) + right

private fun formatReportForPrint(r: DayReport, dateStr: String, basis: DayReport.Basis): List<String> {
    val sep = "-".repeat(32)
    val out = mutableListOf<String>()
    out += "        DAY REPORT"
    out += DeviceRepository.getStoreName()
    out += "Date: $dateStr"
    out += "Device: ${(DeviceRepository.getPersistedDeviceSn() ?: "").takeLast(8)}"
    out += "Basis: " + if (basis == DayReport.Basis.REFUND_DATE) "refunds made this day" else "refunds of this day's sales"
    out += "Printed: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date())}" +
        (com.goldsky.ssp.payment.StaffRepository.current?.let { " by ${it.name}" } ?: "")
    out += sep
    out += line("Sales (${r.sales.count})", money(r.sales.cents))
    out += line("Refunds (${r.refunds.size})", "-" + money(r.refundCents))
    out += line("NET", money(r.netCents))
    out += line("EXPECTED CASH", money(r.expectedCashCents))
    out += sep
    out += "BY METHOD"
    r.byMethod.forEach { (m, t) -> out += line("  ${methodShort(m)} (${t.count})", money(t.cents)) }
    out += sep
    out += "BY STAFF"
    r.byStaff.forEach { (name, t) ->
        out += "$name (${t.count})"
        out += line("  total", money(t.cents))
        out += line("  cash", money(t.cashCents))
        out += line("  card", money(t.cardCents))
        if (t.refundCents > 0) out += line("  refunds", "-" + money(t.refundCents))
    }
    out += sep
    out += line("HST", money(r.taxCents))
    out += line("Taxable sales", money(r.taxableCents))
    out += line("Exempt sales", money(r.exemptCents))
    out += line("Tips", money(r.tipCents))
    out += sep
    out += "REFUNDS & VOIDS"
    if (r.refunds.isEmpty()) out += "  none"
    r.refunds.forEach { f ->
        out += line("  ${localTime(f.at)} ${methodShort(f.method)} ${if (f.status == "VOIDED") "void" else "refund"}", "-" + money(f.cents))
        out += "    ok: ${f.approvedBy ?: "-"}"
    }
    out += sep
    return out
}
