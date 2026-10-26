package com.goldsky.ssp.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Keeps WizarPOS PAYWizard (OPC, `com.wizarpos.opc`) running: every card sale goes to its
 * socket on 127.0.0.1:6031, and when OPC isn't running the kiosk can only say "card payment
 * is temporarily unavailable". Seen on 255 (2026-10-14): OPC didn't come up after a reboot,
 * and later stopped by itself twice in a few hours.
 *
 * While the kiosk is idle (never during a sale) it checks the port every [CHECK_MS]. When
 * nothing answers: first bind OPC's payment service (allowed for a foreground app, no
 * screen change); if the port is still closed, open OPC briefly, wait for the port, and
 * bring the kiosk back to the front. Each recovery is reported (PAYWIZARD_RESTARTED).
 * WizarPOS terminals with OPC installed only.
 */
object PayWizardGuard {
    private const val TAG = "PayWizardGuard"
    private const val OPC = "com.wizarpos.opc"
    private const val OPC_SERVICE_ACTION = "com.wizarpos.payment.aidl.pay"
    private const val PORT = 6031
    private const val FIRST_CHECK_MS = 20_000L
    private const val CHECK_MS = 60_000L
    private const val BIND_WAIT_MS = 6_000L
    private const val LAUNCH_WAIT_MS = 15_000L

    /**
     * Starts the guard for [activity]'s lifetime. [idle]: true only when no sale is in
     * progress and the customer screen may be covered for a moment.
     */
    fun <A> start(activity: A, idle: () -> Boolean) where A : Activity, A : LifecycleOwner {
        if (DeviceRepository.getPersistedHardwareVendor().uppercase() != "WIZARPOS") return
        if (!installed(activity)) return
        activity.lifecycleScope.launch {
            delay(FIRST_CHECK_MS)
            while (isActive) {
                // Only the app on screen acts: a kiosk left running in the background (two apps
                // on a test terminal) must never open PAYWizard over the one the customer uses.
                if (foreground(activity) && idle() && !reachable()) recover(activity) { foreground(activity) && idle() }
                delay(CHECK_MS)
            }
        }
    }

    private fun foreground(owner: LifecycleOwner) = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(OPC, 0); true }.getOrDefault(false)

    /**
     * OPC can take a sale: its AIDL service binds (AIDL mode has no socket, and binding
     * starts OPC without showing it) or its port answers (socket mode).
     */
    private suspend fun reachable(): Boolean =
        com.goldsky.ssp.payment.wizarpos.WizarPosAidlClient.available() || portOpen()

    /** True when something accepts a connection on PAYWizard's port. */
    suspend fun portOpen(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 800) }; true }.getOrDefault(false)
    }

    private suspend fun recover(activity: Activity, idle: () -> Boolean) {
        val sn = DeviceRepository.getPersistedDeviceSn().orEmpty()
        Log.w(TAG, "PAYWizard not answering on $PORT: restarting it")

        // 1. Bind its service: starts the process without showing anything.
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val bound = runCatching {
            activity.bindService(Intent(OPC_SERVICE_ACTION).setPackage(OPC), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) {
            delay(BIND_WAIT_MS)
            val up = portOpen()
            runCatching { activity.unbindService(conn) }
            if (up) {
                Log.i(TAG, "PAYWizard back (service bind)")
                DiagnosticManager.reportError(sn, "PAYWIZARD_RESTARTED", severity = "WARNING", trace = "port $PORT was closed; restarted by binding its service")
                return
            }
        }

        // 2. Open OPC itself, only while still idle, then come back to the front.
        if (!idle()) return
        val launch = activity.packageManager.getLaunchIntentForPackage(OPC) ?: return
        runCatching { activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            Log.w(TAG, "Could not open PAYWizard: ${it.message}"); return
        }
        var waited = 0L
        while (waited < LAUNCH_WAIT_MS && !portOpen()) { delay(1_000); waited += 1_000 }
        val up = portOpen()
        runCatching {
            activity.startActivity(Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        Log.w(TAG, if (up) "PAYWizard back (opened it)" else "PAYWizard still not answering after opening it")
        DiagnosticManager.reportError(
            sn, if (up) "PAYWIZARD_RESTARTED" else "PAYWIZARD_DOWN", severity = if (up) "WARNING" else "CRITICAL",
            trace = if (up) "port $PORT was closed; restarted by opening PAYWizard" else "port $PORT still closed after opening PAYWizard -- check its settings (PaymentApp PkgName)",
        )
    }
}
