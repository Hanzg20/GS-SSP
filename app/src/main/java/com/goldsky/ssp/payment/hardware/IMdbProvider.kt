package com.goldsky.ssp.payment.hardware

/**
 * Common interface for MDB (Multi-Drop Bus) communication.
 */
interface IMdbProvider {

    interface MdbEventListener {
        /**
         * Triggered when a Vending Machine Controller (VMC) requests a vend.
         * @param amount The requested amount (usually float dollars).
         * @param item The item/slot selection index.
         */
        fun onVendRequest(amount: Float, item: Int)
        fun onVendSuccess()
        fun onVendFailure()
    }

    /**
     * Starts the MDB event polling loop.
     */
    fun startPolling(listener: MdbEventListener)

    /**
     * Stops the MDB polling and releases resources.
     */
    fun stopPolling()

    /**
     * Approves a pending vend request.
     */
    fun approveVend(): Boolean

    /**
     * Denies a pending vend request.
     */
    fun denyVend(): Boolean
}
