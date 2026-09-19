package com.goldsky.ssp.ev.logic

/**
 * Abstraction for OCPP-style communication with the backend.
 * Logic based on ocpp-kotlin implementation patterns.
 */
interface IEvCloudClient {
    /** OCPP BootNotification.req */
    suspend fun boot()
    
    /** OCPP Authorize.req */
    suspend fun authorize(idTag: String): Boolean
    
    /** OCPP StartTransaction.req */
    suspend fun startTransaction(connectorId: Int, idTag: String, meterStart: Int): String?
    
    /** OCPP MeterValues.req */
    suspend fun sendMeterValues(transactionId: String, currentKwh: Double, powerKw: Double)
    
    /** OCPP StopTransaction.req */
    suspend fun stopTransaction(transactionId: String, meterStop: Int, reason: String)
}
