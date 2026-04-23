package com.airpay.upi.ussd

import android.util.Log
import com.airpay.upi.ussd.TargetType.PHONE
import com.airpay.upi.ussd.TargetType.UPI

object USSDController {

    private const val DEFAULT_USSD_ENTRY = "*99#"

    enum class State {
        IDLE,
        SELECT_SIM,
        MENU_MAIN,
        ENTER_UPI,
        ENTER_AMOUNT,
        CONFIRM,
        SUCCESS,
        FAILED,
        BALANCE_RESULT
    }

    enum class Flow {
        NONE,
        PAYMENT,
        BALANCE
    }

    @Volatile
    var currentState: State = State.IDLE
        private set

    @Volatile
    var currentFlow: Flow = Flow.NONE

    @Volatile
    var currentPayment: UPIData? = null

    @Volatile
    var currentAttemptId: String = ""

    @Volatile
    var lastBalance: String = ""

    @Volatile
    var sessionStartTime: Long = 0

    @Volatile
    var retryCount: Int = 0

    @Volatile
    var lastCompletionTime: Long = 0

    @Volatile
    var lastTransactionRef: String = ""

    private const val MAX_RETRIES = 2
    private const val SESSION_TIMEOUT = 120000L // 2 minutes in milliseconds

    /**
     * Format and validate balance amount for display
     * Returns formatted string with Rs. prefix or error message
     */
    fun formatBalanceAmount(rawBalance: String): String {
        val cleanBalance = rawBalance.replace("[^\\d.]".toRegex(), "")

        return try {
            val amount = cleanBalance.toDouble()
            when {
                amount < 0 -> "Invalid balance"
                amount >= 10000000 -> "Balance too large"
                amount == 0.0 -> "Rs. 0.00"
                else -> "Rs. ${String.format("%.2f", amount)}"
            }
        } catch (e: Exception) {
            "Could not parse balance"
        }
    }

    fun updateState(newState: State) {
        currentState = newState
    }

    fun reset() {
        currentState = State.IDLE
        currentFlow = Flow.NONE
        currentPayment = null
        currentAttemptId = ""
        lastBalance = ""
        sessionStartTime = 0
        retryCount = 0
        lastCompletionTime = 0
        lastTransactionRef = ""
    }

    fun startSession() {
        sessionStartTime = System.currentTimeMillis()
        retryCount = 0
    }

    fun isSessionTimedOut(): Boolean {
        return sessionStartTime > 0 && (System.currentTimeMillis() - sessionStartTime) > SESSION_TIMEOUT
    }

    fun canRetry(): Boolean {
        return retryCount < MAX_RETRIES
    }

    fun incrementRetry() {
        retryCount++
    }

    fun currentDialString(): String {
        return DEFAULT_USSD_ENTRY
    }

    fun hasActiveSession(): Boolean {
        return sessionStartTime > 0 &&
            !isSessionTimedOut() &&
            currentFlow != Flow.NONE
    }

    fun getNextInput(ussdText: String): String? {
        val text = ussdText.lowercase().trim()

        // Once a session has completed, ignore any further content updates
        // from the same USSD dialog instead of replying again.
        if (currentState == State.SUCCESS ||
            currentState == State.FAILED ||
            currentState == State.BALANCE_RESULT
        ) {
            return null
        }

        // SIM selection must remain manual. Auto-picking SIM 1 causes silent
        // failures on dual-SIM devices when the bank-linked number is on SIM 2.
        if (text.contains("select sim") ||
            text.contains("choose sim") ||
            text.contains("select your sim") ||
            text.contains("dual sim")
        ) {
            updateState(State.SELECT_SIM)
            return null
        }

        return when (currentFlow) {
            Flow.NONE -> null
            Flow.PAYMENT -> handlePaymentFlow(text)
            Flow.BALANCE -> handleBalanceFlow(text)
        }
    }

    private fun handlePaymentFlow(text: String): String? {
        val payment = currentPayment ?: return null
        val isRecipientUpiPrompt = isRecipientUpiPrompt(text)
        val isPurePhonePrompt = isPurePhonePrompt(text)
        val isPinPrompt = isPinPrompt(text)
        val isTerminalSuccessPrompt = isTerminalSuccessPrompt(text)
        
        Log.d("USSDController", "PAYMENT FLOW: Analyzing prompt length=${text.length}")
        Log.d("USSDController", "PAYMENT FLOW: Current state: $currentState")
        Log.d("USSDController", "PAYMENT FLOW: Current flow: $currentFlow")
        Log.d(
            "USSDController",
            "PAYMENT FLOW: promptClassifier isRecipientUpiPrompt=$isRecipientUpiPrompt isPurePhonePrompt=$isPurePhonePrompt isPinPrompt=$isPinPrompt isTerminalSuccessPrompt=$isTerminalSuccessPrompt"
        )

        return when {
            // ── Terminal success screens — stop automation and finish cleanly ──
            isTerminalSuccessPrompt -> {
                captureSuccessMetadata(text)
                updateState(State.SUCCESS)
                null
            }

            // ── PIN entry — STOP automation, let user type manually ────────────
            isPinPrompt -> {
                updateState(State.CONFIRM)
                null
            }

            // ── UPI ID / Beneficiary prompt — MUST come BEFORE payment-type sub-menu ──
            // When *99*1*3# chaining is used, the phone lands directly on the
            // "Enter UPI ID" input screen.  That screen's text also contains
            // numbered options (e.g. "00.Back") which the sub-menu detector below
            // would wrongly match, causing it to type "1" instead of the UPI ID.
            // Checking isRecipientUpiPrompt first prevents that mis-classification.
            isRecipientUpiPrompt -> {
                if (payment.targetType == PHONE) {
                    Log.w(
                        "USSDController",
                        "PAYMENT FLOW: Phone-target payment reached a UPI-only prompt. Refusing to send phone number as UPI."
                    )
                    updateState(State.FAILED)
                    null
                } else if (payment.upiId.isNotEmpty()) {
                    updateState(State.ENTER_UPI)
                    payment.upiId
                } else {
                    Log.w("USSDController", "PAYMENT FLOW: Reached UPI prompt without a UPI ID")
                    updateState(State.FAILED)
                    null
                }
            }

            // ── Optional remark prompt — Airtel asks for a note before PIN ─────
            text.contains("enter a remark") ||
            text.contains("remark") && text.contains("skip") -> {
                updateState(State.ENTER_AMOUNT)
                "1"
            }

            // ── Payment Type Sub-menu ─────────────────────────────────────────
            // When menu shows options like: 1. Mobile 2. UPI 3. Bank Account
            // Choose based on what payment data we have.
            // NOTE: isRecipientUpiPrompt is already handled above — these patterns
            // are only reached when the screen is genuinely a type-selection menu.
            text.contains("1. mobile") ||
            text.contains("1.mobile") ||
            text.contains("1. upi") ||
            text.contains("1.upi") ||
            text.contains("2. upi") ||
            text.contains("2.upi") ||
            text.contains("3. upi") ||
            text.contains("3.upi") ||
            text.contains("3. upi id") ||
            text.contains("3.upi id") ||
            text.contains("to upi") && text.contains("2") ||
            text.contains("pay to upi") ||
            text.contains("select upi") ||
            text.contains("choose upi") ||
            (text.contains("mobile") && text.contains("upi") && text.contains("1.") && !isRecipientUpiPrompt) ||
            (text.contains("mobile") && text.contains("upi") && text.contains("2.") && !isRecipientUpiPrompt) ||
            (text.contains("mobile") && text.contains("upi") && text.contains("3.") && !isRecipientUpiPrompt) -> {
                val selectedOption = when (payment.targetType) {
                    PHONE -> findMenuOptionNumber(
                        text,
                        listOf("mobile", "mobile no", "mobile number", "mmid")
                    ) ?: "1"
                    UPI -> findMenuOptionNumber(
                        text,
                        listOf("upi id", "upi", "vpa", "virtual payment address")
                    ) ?: if (payment.upiId.isNotEmpty()) "2" else "1"
                }

                Log.d(
                    "USSDController",
                    "PAYMENT FLOW: Detected payment type menu, target=${payment.targetType}, selecting option $selectedOption"
                )
                updateState(State.MENU_MAIN)
                selectedOption
            }

            // ── Main menu — pick "Send Money" ─────────────────────────────────
            // This MUST be detected before other patterns
            text.contains("send money") ||
            text.contains("1. send") ||
            text.contains("1.send") ||
            text.contains("money transfer") ||
            text.contains("fund transfer") ||
            text.contains("transfer funds") ||
            text.contains("mobile transfer") ||
            text.contains("bank transfer") ||
            text.contains("1. transfer") ||
            text.contains("1.transfer") ||
            (text.contains("1") && text.contains("send")) ||
            (text.contains("1") && text.contains("transfer")) ||
            (text.contains("1") && text.contains("money")) ||
            text.contains("to mobile/upi") ||
            text.contains("to upi/mobile") ||
            text.contains("send to upi") ||
            // Fallback: if main menu shows numbered options and we're in payment flow
            (currentFlow == Flow.PAYMENT && 
             text.contains("1.") && 
             text.contains("2.") && 
             text.contains("3.") &&
             (text.contains("balance") || text.contains("history") || text.contains("settings"))) -> {
                Log.d("USSDController", "PAYMENT FLOW: Main menu detected, selecting 1 for Send Money")
                updateState(State.MENU_MAIN)
                "1"
            }

            // ── Mobile Number prompt — send phone number or UPI ID as fallback
            isPurePhonePrompt -> {
                if (payment.phoneNumber.isNotEmpty()) {
                    Log.d("USSDController", "PAYMENT FLOW: Detected mobile number prompt, sending phone number")
                    updateState(State.ENTER_UPI)
                    payment.phoneNumber
                } else {
                    // No phone number available — try sending UPI ID (many NPCI backends accept it)
                    Log.d("USSDController", "PAYMENT FLOW: Detected mobile number prompt, no phone — sending UPI ID as fallback")
                    updateState(State.ENTER_UPI)
                    payment.upiId
                }
            }

            // (isRecipientUpiPrompt is now handled earlier in the when block — see above)

            // ── Enter Amount ──────────────────────────────────────────────────
            text.contains("enter amount") ||
            text.contains("amount (in rs)") ||
            text.contains("amount in rs") ||
            text.contains("enter the amount") ||
            text.contains("enter payment amount") ||
            text.contains("pay amount") ||
            text.contains("transfer amount") ||
            text.contains("transaction amount") ||
            text.contains("amount:") ||
            text.contains("amount rs") ||
            text.contains("amount inr") ||
            text.contains("enter rs") ||
            text.contains("enter rupees") ||
            (text.contains("amount") && !text.contains("pin")) ||
            (text.contains("rs.") && !text.contains("pin")) -> {
                updateState(State.ENTER_AMOUNT)
                payment.amount
            }

            // ── Post-PIN confirmation prompts ───────────────────────────────────
            text.contains("confirm") && !text.contains("pin") ||
            text.contains("proceed") && !text.contains("pin") ||
            text.contains("1. confirm") ||
            text.contains("2. cancel") ||
            text.contains("press 1 to confirm") ||
            text.contains("press 2 to cancel") ||
            (text.contains("1") && text.contains("confirm") && !text.contains("pin")) -> {
                updateState(State.CONFIRM)
                "1"
            }

            // ── Post-balance confirmation (after showing account balance) ───────
            (text.contains("balance") || text.contains("rs.")) && 
            currentState == State.CONFIRM &&
            (text.contains("enter") || text.contains("input") || text.contains("type")) -> {
                Log.d("USSDController", "PAYMENT FLOW: Post-balance confirmation detected, stopping for manual input")
                updateState(State.CONFIRM)
                null
            }

            // ── Success ───────────────────────────────────────────────────────
            // Check flow type FIRST, then look for success keywords
            // Exclude balance-related text AND transaction/reference number prompts
            currentFlow == Flow.PAYMENT &&
            !text.contains("balance") &&
            !text.contains("available bal") &&
            !text.contains("avl bal") &&
            !text.contains("a/c balance") &&
            !text.contains("account balance") &&
            !text.contains("your balance") &&
            !text.contains("bank balance") &&
            !text.contains("current balance") &&
            !text.contains("enter mobile") &&
            !text.contains("enter phone") &&
            (
            text.contains("transaction successful") ||
            text.contains("payment successful") ||
            text.contains("transfer successful") ||
            text.contains("completed successfully") ||
            text.contains("txn successful") ||
            text.contains("sent successfully") ||
            text.contains("credit successful") ||
            text.contains("debit successful") ||
            text.contains("amount transferred") ||
            text.contains("money sent") ||
            text.contains("payment sent") ||
            text.contains("amount debited") ||
            text.contains("rs. debited") ||
            text.contains("rupees debited") ||
            text.contains("transferred to") ||
            text.contains("paid to") ||
            text.contains("payment to") ||
            text.contains("sent to") ||
            text.contains("transaction id") && !text.contains("enter") ||
            text.contains("txn id") && !text.contains("enter") ||
            text.contains("reference no") && !text.contains("enter") ||
            text.contains("ref no") && !text.contains("enter") ||
            text.contains("approved") ||
            text.contains("confirmed") && text.contains("transaction") ||
            text.contains("acknowledged") ||
            text.contains("ref:") ||
            text.contains("txn ref") ||
            text.contains("transaction reference") && !text.contains("enter") ||
            text.contains("payment ref") ||
            text.contains("upi ref") ||
            text.contains("upi transaction id") && !text.contains("enter") ||
            text.contains("payment id") && !text.contains("enter") ||
            text.contains("transaction no") && !text.contains("enter") ||
            text.contains("txn no") && !text.contains("enter") ||
            text.contains("done") && text.contains("transaction") ||
            text.contains("processed") && text.contains("payment")
            ) -> {
                captureSuccessMetadata(text)
                updateState(State.SUCCESS)
                null
            }

            // ── Failed ────────────────────────────────────────────────────────
            text.contains("failed") ||
            text.contains("failure") ||
            text.contains("error") ||
            text.contains("declined") ||
            text.contains("incorrect pin") ||
            text.contains("pin length is incorrect") ||
            text.contains("upi pin length is incorrect") ||
            text.contains("invalid pin length") ||
            text.contains("wrong pin") ||
            text.contains("invalid pin") ||
            text.contains("limit exceeded") ||
            text.contains("insufficient") ||
            // PSP-specific errors - these are now handled specifically
            text.contains("psp not registered") ||
            text.contains("psp is not registered") ||
            text.contains("psp not available") ||
            text.contains("psp unavailable") ||
            text.contains("transaction failed") ||
            text.contains("payment failed") ||
            text.contains("could not process") ||
            text.contains("unable to process") ||
            text.contains("service unavailable") ||
            text.contains("server error") ||
            text.contains("network error") ||
            text.contains("timeout") ||
            text.contains("invalid upi") ||
            text.contains("invalid vpa") ||
            text.contains("beneficiary not found") ||
            text.contains("account not found") ||
            text.contains("invalid amount") ||
            text.contains("amount too high") ||
            text.contains("daily limit") ||
            text.contains("transaction limit") ||
            text.contains("exceeded limit") -> {
                updateState(State.FAILED)
                null
            }

            else -> null
        }
    }

    private fun findMenuOptionNumber(text: String, labels: List<String>): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            val number = Regex("""^(\d+)\s*[\.)-]?\s*(.*)$""").find(line)?.groupValues?.get(1)
            if (number != null && labels.any { label -> line.contains(label) }) {
                return number
            }
        }

        val compactText = text.replace('\n', ' ')
        for (label in labels) {
            val match = Regex("""(\d+)\s*[\.)]?\s*$label\b""").find(compactText)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun handleBalanceFlow(text: String): String? {
        return when {

            // ── Main menu — pick "Check Balance" (usually option 3) ───────────
            text.contains("check balance") ||
            text.contains("balance enquiry") ||
            text.contains("3. check") ||
            text.contains("3.check") ||
            text.contains("3. balance") ||
            isBalanceMenuPrompt(text) -> {
                updateState(State.MENU_MAIN)
                "3"
            }

            // ── PIN entry for balance ─────────────────────────────────────────
            text.contains("enter pin") ||
            text.contains("enter upi pin") ||
            text.contains("upi pin") ||
            text.contains("mpin") ||
            text.contains("m-pin") ||
            text.contains("enter 4 digit") ||
            text.contains("enter 6 digit") ||
            (text.contains("pin") && text.contains("bank")) -> {
                updateState(State.CONFIRM)
                null  // human types PIN
            }

            // ── Balance result ────────────────────────────────────────────────
            text.contains("balance is") ||
            text.contains("avl bal") ||
            text.contains("available balance") ||
            text.contains("a/c balance") ||
            text.contains("account balance") ||
            text.contains("rs.") ||
            text.contains("inr") ||
            text.contains("balance:") ||
            text.contains("bal:") ||
            text.contains("current balance") ||
            text.contains("main balance") ||
            text.contains("wallet balance") ||
            text.contains("account balance is") ||
            text.contains("available balance is") ||
            text.contains("your balance is") -> {
                
                // Enhanced regex patterns for all operators (Airtel, Jio, Vi, BSNL)
                val patterns = listOf(
                    // Standard Rs. patterns
                    "rs\\.?\\s?([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    "rupees?\\s?([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    "₹\\s?([\\d,]+\\.?\\d*)".toRegex(),
                    
                    // INR patterns
                    "inr\\s?([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    
                    // Balance-specific patterns
                    "balance[:\\s]*([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    "bal[:\\s]*([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    "available balance[:\\s]*([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    "avl\\.?\\s*bal[:\\s]*([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    "a/c\\s*balance[:\\s]*([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    
                    // Operator-specific patterns
                    "main\\s*balance[:\\s]*([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    "wallet\\s*balance[:\\s]*([\\d,]+\\.?\\d*)".toRegex(RegexOption.IGNORE_CASE),
                    
                    // Generic amount patterns (fallback)
                    "([\\d,]+\\.\\d{2})".toRegex(),  // decimal with 2 places
                    "([\\d,]+)".toRegex()  // any number (last resort)
                )

                var extracted: String? = null
                for (pattern in patterns) {
                    val matches = pattern.findAll(text)
                    for (match in matches) {
                        val amount = match.groupValues[1].replace(",", "")
                        // Validate that it's a reasonable amount (0-9999999)
                        if (amount.toDoubleOrNull() != null && amount.toDouble() >= 0 && amount.toDouble() < 10000000) {
                            extracted = amount
                            break
                        }
                    }
                    if (extracted != null) break
                }

                lastBalance = extracted ?: "Could not extract balance"
                updateState(State.BALANCE_RESULT)
                null
            }

            else -> null
        }
    }

    private fun isBalanceMenuPrompt(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lineMatch = lines.any { line ->
            line.startsWith("3.") && line.contains("balance")
        } || lines.any { line ->
            line.startsWith("3 ") && line.contains("balance")
        }

        val compactText = text.replace('\n', ' ')
        val inlineMatch = Regex("""\b3\s*[\.)-]?\s*(check\s+balance|balance\s+enquiry|balance)\b""")
            .containsMatchIn(compactText)

        return lineMatch || inlineMatch
    }

    private fun isPurePhonePrompt(text: String): Boolean {
        val mentionsUpiRecipient =
            text.contains("upi") ||
            text.contains("vpa") ||
            text.contains("beneficiary") ||
            text.contains("payee") ||
            text.contains("virtual address") ||
            text.contains("payment address")

        if (mentionsUpiRecipient) return false

        return text.contains("enter mobile no") ||
            text.contains("enter mobile number") ||
            text.contains("enter your mobile") ||
            text.contains("enter phone") ||
            text.contains("phone number") && !text.contains("transaction") && !text.contains("reference") ||
            text.contains("mobile no") && !text.contains("transaction") && !text.contains("reference") ||
            text.contains("mobile number") && !text.contains("transaction") && !text.contains("reference") ||
            text.contains("mob no")
    }

    private fun isRecipientUpiPrompt(text: String): Boolean {
        return text.contains("enter upi") ||
            text.contains("pay using upi id or number") ||
            text.contains("upi id or number") ||
            text.contains("enter vpa") ||
            text.contains("vpa") ||
            text.contains("mobile/upi") ||
            text.contains("upi/mobile") ||
            text.contains("upi id") ||
            text.contains("payee vpa") ||
            text.contains("beneficiary") ||
            text.contains("enter beneficiary") ||
            text.contains("enter payee") ||
            text.contains("enter upi id") ||
            text.contains("enter virtual address") ||
            text.contains("enter payment address") ||
            text.contains("recipient upi") ||
            text.contains("receiver upi") ||
            text.contains("to upi id") ||
            text.contains("payee upi id") ||
            (text.contains("mobile") && text.contains("upi")) ||
            (text.contains("mobile") && text.contains("vpa"))
    }

    private fun isPinPrompt(text: String): Boolean {
        if (text.contains("select option") && text.contains("1. send money")) {
            return false
        }

        return text.contains("enter pin") ||
            text.contains("enter upi pin") ||
            text.contains("enter your pin") ||
            text.contains("enter 4 digit") ||
            text.contains("enter 6 digit") ||
            text.contains("upi pin to proceed") ||
            text.contains("mpin") ||
            text.contains("m-pin") ||
            text.contains("pin for") ||
            text.contains("atm pin") ||
            text.contains("confirm pin") ||
            (text.contains("pin") && text.contains("bank"))
    }

    private fun captureSuccessMetadata(text: String) {
        lastCompletionTime = System.currentTimeMillis()

        val refPatterns = listOf(
            "transaction id[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "txn id[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "reference no[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "reference number[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "ref no[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "transaction no[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "transaction number[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "upi transaction id[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "upi ref[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "txn ref[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE),
            "utr[:\\s-]*([a-zA-Z0-9-]+)".toRegex(RegexOption.IGNORE_CASE)
        )

        lastTransactionRef = refPatterns
            .firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            .orEmpty()
    }

    private fun isTerminalSuccessPrompt(text: String): Boolean {
        return text.contains("successfully added to your beneficiary") ||
            text.contains("has been successfully added to your beneficiary") ||
            text.contains("beneficiary added successfully") ||
            text.contains("transaction successful") ||
            text.contains("payment successful") ||
            text.contains("sent successfully") ||
            text.contains("amount transferred")
    }
}
