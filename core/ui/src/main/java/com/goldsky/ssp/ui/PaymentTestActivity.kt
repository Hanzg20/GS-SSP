package com.goldsky.ssp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.PaymentTestSuite
import com.goldsky.ssp.payment.PaymentTestSuite.Status
import com.goldsky.ssp.payment.wizarpos.WizarPosPaymentProvider
import kotlinx.coroutines.launch

/**
 * Technician runner for the WizarPOS / Nuvei AIDL test cases
 * ([PaymentTestSuite]): one button per case, live PASS / FAIL with the key
 * response fields, and a report uploaded to device-logs for WizarPOS / Nuvei.
 * Refuses to run unless the terminal is flagged devices.payment_test_enabled
 * (a UAT terminal): the case amounts are real money on production.
 */
class PaymentTestActivity : ComponentActivity() {

    private val bg = Color(0xFF0F141A)
    private val card = Color(0xFF1A222C)
    private val amber = Color(0xFFFFB800)
    private val green = Color(0xFF2ECC71)
    private val red = Color(0xFFFF5A5F)
    private val muted = Color(0xFF8A96A3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN"
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val provider = runCatching {
            PaymentProviderFactory.getPaymentProvider(this, DeviceRepository.getPersistedHardwareVendor()) as? WizarPosPaymentProvider
        }.getOrNull()

        setContent {
            var enabled by remember { mutableStateOf<Boolean?>(null) }
            val results = remember { mutableStateMapOf<String, PaymentTestSuite.Result>() }
            var running by remember { mutableStateOf<String?>(null) }
            var expanded by remember { mutableStateOf<String?>(null) }
            var uploadMsg by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { enabled = PaymentTestSuite.isEnabled(sn) }

            fun run(case: PaymentTestSuite.Case) {
                val p = provider ?: return
                val request = case.request(PaymentTestSuite.newRef(case.id), results.toMap())
                if (request == null) {
                    results[case.id] = PaymentTestSuite.Result(case.id, Status.BLOCKED, "Run ${case.needs.joinToString()} first (it must return a TransID)", emptyMap(), null)
                    return
                }
                running = case.id
                lifecycleScope.launch {
                    val response = p.executeRaw(request)
                    results[case.id] = PaymentTestSuite.evaluate(case, request, response)
                    running = null
                }
            }

            Column(Modifier.fillMaxSize().background(bg).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Payment test cases (AIDL)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("$sn · v$version · passed ${results.values.count { it.status == Status.PASS }}/${PaymentTestSuite.cases.size}", color = muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { finish() }) { Text("Close", color = amber) }
                }
                when {
                    enabled == null -> Text("Checking whether this terminal is a test terminal…", color = muted, fontSize = 13.sp)
                    enabled == false -> Text(
                        "This terminal is not flagged as a test (UAT) terminal, so the test cases are disabled: their amounts would be real charges here. Flag it in the cloud (devices.payment_test_enabled) to enable.",
                        color = red, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
                    )
                    provider == null -> Text("No WizarPOS payment provider on this terminal.", color = red, fontSize = 13.sp)
                }
                LazyColumn(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PaymentTestSuite.cases, key = { it.id }) { c ->
                        val r = results[c.id]
                        Column(Modifier.fillMaxWidth().background(card, RoundedCornerShape(10.dp)).clickable { expanded = if (expanded == c.id) null else c.id }.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${c.id}  ${c.title}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(c.instruction, color = muted, fontSize = 11.sp)
                                }
                                r?.let {
                                    val col = when (it.status) { Status.PASS -> green; Status.FAIL, Status.NO_RESPONSE -> red; Status.BLOCKED -> amber }
                                    Text(it.status.name, color = col, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                                }
                                Button(
                                    onClick = { run(c) },
                                    enabled = enabled == true && provider != null && running == null,
                                    colors = ButtonDefaults.buttonColors(containerColor = amber, contentColor = bg),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) { Text(if (running == c.id) "…" else "Run", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                            r?.let { Text(it.note, color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                            if (expanded == c.id && r != null) {
                                Text("Request: ${r.request}", color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                                Text("Response: ${r.response ?: "(none)"}", color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val text = PaymentTestSuite.report(sn, version, results.values.sortedBy { it.at })
                            uploadMsg = "Uploading…"
                            lifecycleScope.launch {
                                uploadMsg = PaymentTestSuite.upload(sn, text)?.let { "Report uploaded: $it" } ?: "Upload failed (check network)"
                            }
                        },
                        enabled = results.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = amber, contentColor = bg),
                    ) { Text("Upload report", fontWeight = FontWeight.Bold) }
                    uploadMsg?.let { Text(it, color = muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp)) }
                }
            }
        }
    }
}
