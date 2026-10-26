package com.goldsky.ssp.payment.wizarpos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.wizarpos.payment.aidl.IPaymentPay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

/**
 * WizarPOS OPC (PAYWizard) over its AIDL service -- the way WizarPOS says an app on the
 * terminal itself should call OPC (2026-10-25). The socket on 6031 is meant for an external
 * cashier, and in socket mode OPC starts in the foreground, covering the kiosk after a reboot
 * (253 / 254). Same JSON both ways.
 *
 * The binding is kept for the process's lifetime: it also keeps OPC running.
 */
object WizarPosAidlClient {
    private const val TAG = "WizarPosAidl"
    private const val OPC = "com.wizarpos.opc"
    private const val ACTION = "com.wizarpos.payment.aidl.pay"
    private const val BIND_TIMEOUT_MS = 8_000L
    // Same reasoning as WizarPosSocketClient.READ_TIMEOUT_MS: outlast OPC's own 180 s.
    private const val CALL_TIMEOUT_MS = 200_000L

    @Volatile var appContext: Context? = null
    @Volatile private var service: IPaymentPay? = null
    private var connection: ServiceConnection? = null
    private val bindLock = Mutex()

    sealed interface Outcome {
        /** No OPC service to call: nothing was sent. */
        data object Unavailable : Outcome
        /** The request went to OPC; [json] is its answer, null when none came back. */
        data class Sent(val json: String?) : Outcome
    }

    /** True when OPC's AIDL service can be bound (which also starts OPC). */
    suspend fun available(): Boolean = connect() != null

    suspend fun transact(request: String): Outcome = withContext(Dispatchers.IO) {
        val s = connect() ?: return@withContext Outcome.Unavailable
        // A binder call can't be interrupted: run it on its own thread and stop waiting
        // after CALL_TIMEOUT_MS. No answer is treated like a socket read timeout.
        val answer = CompletableDeferred<String?>()
        thread(name = "opc-aidl") {
            answer.complete(runCatching { s.transact(request) }.onFailure {
                Log.e(TAG, "transact failed: ${it.message}")
                service = null
            }.getOrNull())
        }
        Outcome.Sent(withTimeoutOrNull(CALL_TIMEOUT_MS) { answer.await() })
    }

    private suspend fun connect(): IPaymentPay? = bindLock.withLock {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        val ctx = appContext ?: return@withLock null
        connection?.let { runCatching { ctx.unbindService(it) } }
        connection = null
        val bound = CompletableDeferred<IPaymentPay?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val s = binder?.let { IPaymentPay.Stub.asInterface(it) }
                service = s
                bound.complete(s)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "OPC service disconnected")
                service = null
            }
            override fun onNullBinding(name: ComponentName?) { bound.complete(null) }
        }
        val ok = runCatching { ctx.bindService(Intent(ACTION).setPackage(OPC), conn, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "bindService failed: ${it.message}") }.getOrDefault(false)
        if (!ok) {
            runCatching { ctx.unbindService(conn) }
            return@withLock null
        }
        connection = conn
        withTimeoutOrNull(BIND_TIMEOUT_MS) { bound.await() }.also {
            if (it == null) Log.w(TAG, "OPC service didn't connect within $BIND_TIMEOUT_MS ms")
            else Log.i(TAG, "OPC service bound")
        }
    }
}
