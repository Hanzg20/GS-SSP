package com.goldsky.ssp.flex.logic

/**
 * Why a sale ended without the product. VIP: [NO_CODE] nothing scanned, [VIP_INVALID] not a
 * member code of this store (or inactive / expired / daily limit), [VIP_LOW_BALANCE] the balance
 * doesn't cover it, [VIP_OFFLINE] the server couldn't be reached.
 */
enum class SaleReason {
    CANCELLED, DECLINED, NOT_DISPENSED,
    NO_CODE, VIP_INVALID, VIP_LOW_BALANCE, VIP_OFFLINE,
}
