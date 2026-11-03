package com.goldsky.ssp.common

object CoreConfig {
    var supabaseUrl: String = ""
    var supabaseKey: String = ""
    var isMock: Boolean = false
    var region: String = "CA"
    /**
     * Aegis Vend's VMC-simulator build only (`-Pvmcsim`): no vending machine, a simulated one
     * driven over adb, payments real. Never true in an APK a store gets.
     */
    var vmcSimulator: Boolean = false
}
