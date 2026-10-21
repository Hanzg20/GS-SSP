package com.goldsky.ssp.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.goldsky.ssp.payment.HeartbeatWorker
import com.goldsky.ssp.payment.KioskFront
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Sends a heartbeat (which carries [KioskFront]) as soon as the kiosk screen has been
 * covered for [COVERED_MS], and again when it is back -- the periodic heartbeat can be
 * an hour apart on the Q3mini, so Cael would show the PAYWizard page covering 253
 * long after the fact. [COVERED_MS] leaves out a card sale, whose PAYWizard screen
 * covers the kiosk for a minute or so.
 */
object KioskFrontWatch {
    private const val TAG = "KioskFrontWatch"
    private const val POLL_MS = 30_000L
    private const val COVERED_MS = 2 * 60_000L

    private var started = false
    private var coveredSince = 0L
    private var reportedCovered = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(app: Application) {
        if (started) return
        started = true
        KioskFront.watch()
        handler.postDelayed(object : Runnable {
            override fun run() { poll(app); handler.postDelayed(this, POLL_MS) }
        }, POLL_MS)
    }

    private fun poll(app: Application) {
        val screenOn = runCatching { (app.getSystemService(PowerManager::class.java)).isInteractive }.getOrDefault(true)
        if (!screenOn) { coveredSince = 0L; return }
        val now = SystemClock.elapsedRealtime()
        if (KioskFront.inFront()) {
            coveredSince = 0L
            if (reportedCovered) { reportedCovered = false; send(app, "back in front") }
            return
        }
        if (coveredSince == 0L) coveredSince = now
        if (!reportedCovered && now - coveredSince >= COVERED_MS) { reportedCovered = true; send(app, "covered") }
    }

    private fun send(app: Application, what: String) {
        Log.w(TAG, "Kiosk $what; heartbeat now")
        scope.launch { HeartbeatWorker.send(app) }
    }
}
