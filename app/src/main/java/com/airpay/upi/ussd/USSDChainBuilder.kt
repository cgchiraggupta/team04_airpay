package com.airpay.upi.ussd

import android.util.Log

/**
 * Builds chained USSD dial strings to skip interactive menu navigation.
 *
 * Standard *99# flow:
 *   Step 0: Dial *99#        → Main menu
 *   Step 1: Select "1"       → Send Money
 *   Step 2: Select "3"       → Via UPI ID
 *   Step 3: Enter UPI ID
 *   Step 4: Enter Amount
 *   Step 5: Enter remark "1" → Skip
 *   Step 6: Enter PIN        → Manual by user
 *
 * Chaining pre-fills steps into the dial string:
 *   *99*1*3*upiId*amount*1#  → Jumps directly to PIN entry
 */
object USSDChainBuilder {

    private const val TAG = "USSDChainBuilder"

    enum class ChainLevel(val label: String) {
        /** *99*1*3*upiId*amount*1# — skip everything, jump to PIN */
        FULL("Full chain → PIN"),

        /** *99*1*3*upiId*amount# — skip to remark/confirm */
        TO_REMARK("Chain → remark"),

        /** *99*1*3*upiId# — skip to amount entry */
        TO_AMOUNT("Chain → amount"),

        /** *99*1*3# — skip to UPI ID entry (confirmed working by user) */
        TO_UPI_ENTRY("Chain → UPI entry"),

        /** *99*1# — skip to payment type sub-menu */
        TO_PAY_TYPE("Chain → pay type"),

        /** *99# — no chaining, original interactive flow */
        NONE("No chaining");
    }

    /**
     * Ordered list of levels to attempt for payment flows.
     * Skips intermediate ones to minimize wasted retry time.
     */
    val PAYMENT_ATTEMPT_ORDER = listOf(
        // We do NOT use TO_UPI_ENTRY (*99*1*3#) as a fallback because the option number 
        // for "UPI ID" varies by bank. If we hardcode *3, we might enter the "Mobile Number" 
        // option by mistake, causing text fields to strip the "@" symbol.
        ChainLevel.FULL,
        ChainLevel.TO_PAY_TYPE,    // *99*1# (Send Money menu is standard across banks)
        ChainLevel.NONE            // absolute fallback (*99#)
    )

    /**
     * Build the USSD dial string for the given chain level.
     */
    fun build(level: ChainLevel, payment: UPIData? = null): String {
        return when (level) {
            ChainLevel.FULL -> {
                requireNotNull(payment) { "Payment data required for FULL chain" }
                "*99*1*3*${payment.upiId}*${sanitizeAmount(payment.amount)}*1#"
            }
            ChainLevel.TO_REMARK -> {
                requireNotNull(payment) { "Payment data required for TO_REMARK chain" }
                "*99*1*3*${payment.upiId}*${sanitizeAmount(payment.amount)}#"
            }
            ChainLevel.TO_AMOUNT -> {
                requireNotNull(payment) { "Payment data required for TO_AMOUNT chain" }
                "*99*1*3*${payment.upiId}#"
            }
            ChainLevel.TO_UPI_ENTRY -> "*99*1*3#"
            ChainLevel.TO_PAY_TYPE -> "*99*1#"
            ChainLevel.NONE -> "*99#"
        }
    }

    /**
     * Determine the best starting chain level for a payment.
     * Accounts for UPI ID characters and string length that might
     * break USSD encoding or get blocked by OEM dialers.
     */
    fun bestStartLevel(payment: UPIData): ChainLevel {
        // UPI IDs containing USSD separator chars would break chaining
        if (payment.upiId.contains("*") || payment.upiId.contains("#")) {
            Log.w(TAG, "UPI ID has USSD separator chars → limiting to TO_UPI_ENTRY")
            return ChainLevel.TO_UPI_ENTRY
        }

        // Check total dial string length — MIUI typically blocks > ~25-30 chars
        val fullString = build(ChainLevel.FULL, payment)
        Log.d(TAG, "Full chain length=${fullString.length} chars")

        return when {
            fullString.length <= 30 -> ChainLevel.FULL
            else -> {
                // Full chain too long for MIUI, still try it but expect fallback
                Log.d(TAG, "Full chain is ${fullString.length} chars, will try but may fall back")
                ChainLevel.FULL
            }
        }
    }

    private fun sanitizeAmount(amount: String): String {
        // Remove anything except digits and decimal point
        return amount.replace(Regex("[^0-9.]"), "")
    }
}
