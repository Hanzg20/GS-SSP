package com.goldsky.ssp.feature.retail.dine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.DineRepository
import com.goldsky.ssp.payment.NetworkKitchenPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings > Kitchen printer: the kitchen's network printer, with a test print. */
@Composable
fun KitchenPrinterSettings() {
    var value by remember { mutableStateOf(DeviceRepository.getKitchenPrinter() ?: "") }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.trim(); DeviceRepository.persistKitchenPrinter(value); testResult = null },
            label = { Text(stringResource(R.string.retail_kitchen_printer_ip)) },
            supportingText = { Text(stringResource(R.string.retail_kitchen_printer_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = DeviceRepository.getKitchenPrinterHost() != null && !testing,
                onClick = {
                    testing = true
                    scope.launch {
                        testResult = withContext(Dispatchers.IO) {
                            NetworkKitchenPrinter.print(DeviceRepository.getKitchenPrinterHost()!!, DeviceRepository.getKitchenPrinterPort(), NetworkKitchenPrinter.testLines())
                        }
                        testing = false
                    }
                }
            ) { Text(stringResource(R.string.retail_kitchen_printer_test)) }
            Spacer(Modifier.width(12.dp))
            when (testResult) {
                true -> Text(stringResource(R.string.retail_kitchen_printer_ok), color = MaterialTheme.colorScheme.primary)
                false -> Text(stringResource(R.string.retail_kitchen_printer_fail), color = MaterialTheme.colorScheme.error)
                null -> {}
            }
        }
    }
}

/**
 * Settings > Tables (Ourea, managers): the floor every terminal of the store
 * shares. Areas group the tables on the floor screen (Main hall, Patio...).
 * A table in use can't be removed.
 */
@Composable
fun TableSettings() {
    val scope = rememberCoroutineScope()
    var tables by remember { mutableStateOf<List<DineRepository.DiningTable>?>(null) }
    var editing by remember { mutableStateOf<DineRepository.DiningTable?>(null) }
    var adding by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun reload() { scope.launch { tables = DineRepository.tables() } }
    LaunchedEffect(Unit) { reload() }

    var site by remember { mutableStateOf(DeviceRepository.getScanSiteUrl() ?: "") }
    var showingQr by remember { mutableStateOf<DineRepository.DiningTable?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = site, onValueChange = { site = it.trim(); DeviceRepository.persistScanSiteUrl(site) },
            label = { Text(stringResource(R.string.scan_site_label)) },
            supportingText = { Text(stringResource(R.string.scan_site_hint)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        val list = tables
        when {
            list == null -> Text(stringResource(R.string.retail_tables_loading), style = MaterialTheme.typography.bodySmall)
            list.isEmpty() -> Text(stringResource(R.string.retail_tables_none), style = MaterialTheme.typography.bodySmall)
            else -> list.groupBy { it.area ?: "" }.forEach { (area, inArea) ->
                if (area.isNotEmpty()) Text(area, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    inArea.forEach { t ->
                        Surface(
                            shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.clickable { editing = t }
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(t.name, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.retail_table_seats, t.seats), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.retail_table_add)) }
            OutlinedButton(onClick = { bulk = true }) { Text(stringResource(R.string.retail_table_bulk)) }
        }
    }

    val errorText = mapOf(
        "name_taken" to stringResource(R.string.retail_table_err_taken),
        "table_in_use" to stringResource(R.string.retail_table_err_in_use)
    )
    val offline = stringResource(R.string.retail_table_err_offline)
    fun save(id: String?, name: String, area: String, seats: Int, sort: Int, active: Boolean) {
        scope.launch {
            val r = DineRepository.saveTable(id, name, area, seats, sort, active)
            message = if (r.ok) null else errorText[r.message] ?: r.message ?: offline
            reload()
        }
    }

    if (adding || editing != null) {
        val t = editing
        TableDialog(
            initial = t,
            onSave = { name, area, seats -> save(t?.id, name, area, seats, t?.sort ?: ((tables?.size ?: 0) + 1), true); adding = false; editing = null },
            onRemove = if (t != null) ({ save(t.id, t.name, t.area ?: "", t.seats, t.sort, false); editing = null }) else null,
            onShowQr = if (t?.qr_token != null && DeviceRepository.getScanSiteUrl() != null) ({ editing = null; showingQr = t }) else null,
            onDismiss = { adding = false; editing = null }
        )
    }
    showingQr?.let { t ->
        TableQrDialog(
            table = t,
            onRotate = {
                scope.launch {
                    val r = DineRepository.rotateQr(t.id)
                    if (!r.ok) message = r.message ?: offline
                    tables = DineRepository.tables()
                    showingQr = tables?.find { it.id == t.id }
                }
            },
            onDismiss = { showingQr = null }
        )
    }
    if (bulk) {
        BulkTablesDialog(
            onSave = { prefix, from, to, area, seats ->
                bulk = false
                scope.launch {
                    val start = tables?.size ?: 0
                    var failed: String? = null
                    for ((i, n) in (from..to).withIndex()) {
                        val r = DineRepository.saveTable(null, "$prefix$n", area, seats, start + i + 1)
                        if (!r.ok && failed == null) failed = errorText[r.message] ?: r.message ?: offline
                    }
                    message = failed
                    reload()
                }
            },
            onDismiss = { bulk = false }
        )
    }
}

@Composable
private fun TableDialog(initial: DineRepository.DiningTable?, onSave: (String, String, Int) -> Unit, onRemove: (() -> Unit)?, onShowQr: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var area by remember { mutableStateOf(initial?.area ?: "") }
    var seats by remember { mutableStateOf((initial?.seats ?: 4).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.retail_table_add else R.string.retail_table_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(12) }, label = { Text(stringResource(R.string.retail_table_name)) }, singleLine = true)
                OutlinedTextField(area, { area = it.take(20) }, label = { Text(stringResource(R.string.retail_table_area)) }, singleLine = true)
                OutlinedTextField(seats, { seats = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.retail_table_seats_label)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                onShowQr?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.scan_qr_show)) } }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onSave(name, area, seats.toIntOrNull() ?: 4) }) { Text(stringResource(R.string.retail_done)) }
        },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text(stringResource(R.string.retail_table_remove), color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_mod_cancel)) }
            }
        }
    )
}

@Composable
private fun BulkTablesDialog(onSave: (String, Int, Int, String, Int) -> Unit, onDismiss: () -> Unit) {
    var prefix by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf("10") }
    var area by remember { mutableStateOf("") }
    var seats by remember { mutableStateOf("4") }
    val f = from.toIntOrNull(); val t = to.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.retail_table_bulk)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(prefix, { prefix = it.take(6) }, label = { Text(stringResource(R.string.retail_table_prefix)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.retail_table_from)) },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(to, { to = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.retail_table_to)) },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(area, { area = it.take(20) }, label = { Text(stringResource(R.string.retail_table_area)) }, singleLine = true)
                OutlinedTextField(seats, { seats = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.retail_table_seats_label)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (f != null && t != null && t >= f) Text("$prefix$f … $prefix$t", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = f != null && t != null && t >= f && t - f < 60, onClick = { onSave(prefix.trim(), f!!, t!!, area, seats.toIntOrNull() ?: 4) }) {
                Text(stringResource(R.string.retail_done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_mod_cancel)) } }
    )
}

/** The table's QR (<scan site>/?t=<token>), big enough to photograph or print. */
@Composable
private fun TableQrDialog(table: DineRepository.DiningTable, onRotate: () -> Unit, onDismiss: () -> Unit) {
    val url = "${DeviceRepository.getScanSiteUrl()}/?t=${table.qr_token}"
    val bitmap = remember(url) { com.goldsky.ssp.common.QrUtils.generateQrCode(url, 640, 640) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_qr_title, table.name)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bitmap?.let {
                    androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = url, modifier = Modifier.size(260.dp))
                }
                Text(table.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(stringResource(R.string.scan_qr_desc), style = MaterialTheme.typography.bodySmall)
                Text(url, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onRotate) { Text(stringResource(R.string.scan_qr_rotate), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.retail_done)) } }
    )
}
