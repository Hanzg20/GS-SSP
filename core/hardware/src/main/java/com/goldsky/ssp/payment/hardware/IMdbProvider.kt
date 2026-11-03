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
        /** The machine cancelled (its cancel button, or it ended the session); already answered. */
        fun onVendCancel() {}
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

    /** The MDB board could be opened (polling is running). */
    fun isOpen(): Boolean = true

    /** One line for the technician panel: board, protocol level, whether a machine is on the bus. */
    fun describe(): String = "MDB: not available"

    /** A vending machine (VMC) is talking on the bus; null = can't tell. */
    fun busOnline(): Boolean? = null

    /** Cashless "Begin Session": a card is good for up to [funds] (dollars). False = not supported / failed. */
    fun beginSession(funds: Float): Boolean = false

    /** Ends the current cashless session. */
    fun endSession(): Boolean = false
}
