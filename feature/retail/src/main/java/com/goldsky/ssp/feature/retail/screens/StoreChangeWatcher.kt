package com.goldsky.ssp.feature.retail.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.feature.retail.RetailRepository
import com.goldsky.ssp.payment.DeviceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CHECK_EVERY_MS = 60_000L

/**
 * Notices when Cael moves this terminal to another store (cmp_assign_device):
 * checks the device's identity every minute, and once the store changed asks
 * staff to reload -- no automatic restart, a payment may be under way. Reload
 * drops the saved carts (old store's products), fetches the new catalog and
 * starts the app over at the PIN screen with the new store's staff.
 */
@Composable
fun StoreChangeWatcher(deviceSn: String) {
    val context = LocalContext.current
    val changes by DeviceRepository.storeChanges.collectAsState()
    val scope = rememberCoroutineScope()
    var reloading by remember { mutableStateOf(false) }

    LaunchedEffect(deviceSn) {
        while (true) {
            delay(CHECK_EVERY_MS)
            DeviceRepository.syncDeviceIdentity(deviceSn)
        }
    }

    if (changes > 0) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.store_changed_title)) },
            text = { Text(stringResource(R.string.store_changed_body)) },
            confirmButton = {
                TextButton(enabled = !reloading, onClick = {
                    reloading = true
                    scope.launch { reloadForNewStore(context) }
                }) { Text(stringResource(R.string.store_changed_reload)) }
            },
        )
    }
}

private suspend fun reloadForNewStore(context: Context) {
    context.applicationContext.getSharedPreferences("retail_cart", Context.MODE_PRIVATE).edit().clear().apply()
    RetailRepository.syncWithCloud(context)
    val activity = context as? Activity ?: return
    // A new task, not recreate(): recreate() keeps the ViewModels, and with them the old store's cart.
    activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.let {
        activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    activity.finish()
}
