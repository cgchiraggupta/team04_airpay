package com.airpay.upi

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.airpay.upi.data.BalanceStore
import com.airpay.upi.history.TransactionHistoryStore
import com.airpay.upi.history.TransactionRecord
import com.airpay.upi.history.TransactionStatus
import com.airpay.upi.ussd.TargetType
import com.airpay.upi.ussd.USSDChainBuilder
import com.airpay.upi.ussd.USSDController
import com.airpay.upi.ussd.USSDDialer
import com.airpay.upi.util.NotificationHelper
import com.airpay.upi.util.RecipientBranding

class ProcessingActivity : BaseActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPaymentDetails: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var paymentRecipient: String = ""
    private var paymentAmount: String = ""
    private var didLaunchUSSD = false
    private var lastStatusText: String = ""
    private var manualPendingRecorded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_processing)

        tvStatus = findViewById(R.id.tvStatus)
        tvPaymentDetails = findViewById(R.id.tvPaymentDetails)
        paymentRecipient = intent.getStringExtra("payment_recipient").orEmpty()
        paymentAmount = intent.getStringExtra("payment_amount").orEmpty()

        updatePaymentDetails()
        val btnManualReceipt = findViewById<View>(R.id.btnManualReceipt)
        if (USSDController.currentFlow == USSDController.Flow.BALANCE) {
            btnManualReceipt.visibility = View.GONE
        }
        btnManualReceipt.setOnClickListener {
            recordPendingAttempt()
            manualPendingRecorded = true
            btnManualReceipt.isEnabled = false
            crossFadeStatus(getString(R.string.processing_waiting_confirmation))
            tvPaymentDetails.text = getString(R.string.processing_still_checking_payment)
            handler.postDelayed({
                btnManualReceipt.isEnabled = true
            }, 1500)
        }

        animatePulse()
        animateEntry()
        observeUSSDStatus()
        launchUssdIfNeeded()
    }

    // ── Animations ────────────────────────────────────────────────────────

    private fun animatePulse() {
        val pulseRing = findViewById<View>(R.id.pulseRing)
        val pulseDot = findViewById<View>(R.id.pulseDot)

        val ringScale = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(pulseRing, "scaleX", 1f, 1.6f).apply { repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE },
                ObjectAnimator.ofFloat(pulseRing, "scaleY", 1f, 1.6f).apply { repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE },
                ObjectAnimator.ofFloat(pulseRing, "alpha", 1f, 0.2f).apply { repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE }
            )
            duration = 1400
        }

        val dotPulse = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(pulseDot, "scaleX", 1f, 1.25f).apply { repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE },
                ObjectAnimator.ofFloat(pulseDot, "scaleY", 1f, 1.25f).apply { repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE }
            )
            duration = 1400
            startDelay = 200
        }

        ringScale.start()
        dotPulse.start()
    }

    private fun animateEntry() {
        val views = listOf(
            findViewById<View>(R.id.tvStatus),
            findViewById<View>(R.id.tvPaymentDetails),
            findViewById<View>(R.id.cardInfo)
        )
        views.forEach { v -> v.alpha = 0f; v.translationY = 30f }
        views.forEachIndexed { i, v ->
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setStartDelay(200L + i * 100L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    private fun crossFadeStatus(newText: String) {
        if (newText == lastStatusText) return
        lastStatusText = newText
        tvStatus.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                tvStatus.text = newText
                tvStatus.animate().alpha(1f).setDuration(200).start()
            }
            .start()
    }

    private fun updatePaymentDetails() {
        val payment = USSDController.currentPayment
        if (USSDController.currentFlow == USSDController.Flow.BALANCE) {
            tvPaymentDetails.text = getString(R.string.processing_checking_balance)
        } else if (payment != null) {
            val recipient = RecipientBranding.resolveDisplayName(
                name = payment.name,
                upiId = payment.upiId
            )
            tvPaymentDetails.text = getString(R.string.processing_paying_to_recipient, payment.amount, recipient)
        } else if (paymentAmount.isNotBlank() && paymentRecipient.isNotBlank()) {
            tvPaymentDetails.text = getString(R.string.processing_paying_to_recipient, paymentAmount, paymentRecipient)
        }
    }

    private fun observeUSSDStatus() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                // Check for session timeout
                if (USSDController.isSessionTimedOut()) {
                    removePendingAttempt()
                    crossFadeStatus(getString(R.string.processing_timeout_status))
                    handler.postDelayed({
                        navigateToPaymentResult(
                            paymentStatus = TransactionStatus.FAILED,
                            message = getString(R.string.processing_timeout_message)
                        )
                    }, 2000)
                    return
                }

                when (USSDController.currentState) {

                    USSDController.State.IDLE -> {
                        crossFadeStatus(getString(R.string.processing_status_getting_ready))
                    }

                    USSDController.State.SELECT_SIM -> {
                        crossFadeStatus(getString(R.string.processing_status_choose_sim))
                    }

                    USSDController.State.MENU_MAIN -> {
                        if (USSDController.currentFlow == USSDController.Flow.BALANCE) {
                            crossFadeStatus(getString(R.string.processing_status_opening_balance))
                        } else {
                            crossFadeStatus(getString(R.string.processing_status_opening_bank))
                        }
                    }

                    USSDController.State.ENTER_UPI -> {
                        crossFadeStatus(getString(R.string.processing_status_adding_target))
                    }

                    USSDController.State.ENTER_AMOUNT -> {
                        crossFadeStatus(getString(R.string.processing_status_adding_amount))
                    }

                    USSDController.State.CONFIRM -> {
                        if (USSDController.currentFlow == USSDController.Flow.BALANCE) {
                            crossFadeStatus(getString(R.string.processing_status_enter_pin_balance))
                            findViewById<View>(R.id.layoutPinEntry).visibility = View.GONE
                        } else {
                            crossFadeStatus(getString(R.string.processing_status_enter_pin))
                            val pinLayout = findViewById<View>(R.id.layoutPinEntry)
                            if (pinLayout.visibility != View.VISIBLE) {
                                pinLayout.alpha = 0f
                                pinLayout.visibility = View.VISIBLE
                                pinLayout.animate().alpha(1f).setDuration(300).start()
                            }
                        }
                    }

                    USSDController.State.SUCCESS -> {
                        persistAttemptRecord(
                            status = TransactionStatus.CONFIRMED,
                            statusSource = "ussd_auto",
                            completedAt = USSDController.lastCompletionTime.takeIf { it > 0 }
                                ?: System.currentTimeMillis(),
                            statusMessage = buildConfirmedStatusMessage()
                        )
                        navigateToPaymentResult(paymentStatus = TransactionStatus.CONFIRMED)
                        return
                    }

                    USSDController.State.BALANCE_RESULT -> {
                        // Use formatted balance for better display
                        val formattedBalance = USSDController.formatBalanceAmount(USSDController.lastBalance)
                        BalanceStore.saveBalance(this@ProcessingActivity, formattedBalance.removePrefix("Rs. ").trim())
                        val intent = Intent(this@ProcessingActivity, ResultActivity::class.java).apply {
                            putExtra("is_success", true)
                            putExtra("result_type", "balance")
                            putExtra("message", getString(R.string.result_balance_message_with_amount, formattedBalance))
                        }
                        startActivity(intent)
                        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                        finish()
                        return
                    }

                    USSDController.State.FAILED -> {
                        removePendingAttempt()
                        navigateToPaymentResult(
                            paymentStatus = TransactionStatus.FAILED,
                            message = getString(R.string.processing_failed_message)
                        )
                        return
                    }
                }
                handler.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun launchUssdIfNeeded() {
        if (didLaunchUSSD) return

        val shouldLaunchPaymentUSSD = intent.getBooleanExtra("launch_payment_ussd", false)
        val shouldLaunchBalanceUSSD = USSDController.currentFlow == USSDController.Flow.BALANCE &&
            intent.getBooleanExtra("launch_balance_ussd", false)

        if (!shouldLaunchPaymentUSSD && !shouldLaunchBalanceUSSD) {
            return
        }

        didLaunchUSSD = true

        val ussdCode = when {
            shouldLaunchBalanceUSSD -> "*99*3#"
            shouldLaunchPaymentUSSD -> {
                val payment = USSDController.currentPayment
                when {
                    payment == null -> USSDChainBuilder.build(USSDChainBuilder.ChainLevel.NONE)
                    payment.targetType == TargetType.UPI ->
                        USSDChainBuilder.build(USSDChainBuilder.ChainLevel.TO_UPI_ENTRY, payment)
                    else ->
                        USSDChainBuilder.build(USSDChainBuilder.ChainLevel.TO_PAY_TYPE, payment)
                }
            }
            else -> USSDChainBuilder.build(USSDChainBuilder.ChainLevel.NONE)
        }

        android.util.Log.d("ProcessingActivity", "Dialing chained USSD: $ussdCode")

        handler.postDelayed({
            USSDDialer.dialActionCall(this, ussdCode)
        }, 350)
    }

    private fun navigateToPaymentResult(paymentStatus: String, message: String? = null) {
        val payment = USSDController.currentPayment
        val intent = Intent(this, ResultActivity::class.java).apply {
            putExtra("is_success", paymentStatus == TransactionStatus.CONFIRMED)
            putExtra("payment_status", paymentStatus)
            putExtra("result_type", "payment")
            putExtra("message", message)
            putExtra("payment_amount", payment?.amount ?: paymentAmount)
            putExtra("attempt_id", USSDController.currentAttemptId)
            putExtra("retry_count", intent.getIntExtra("retry_count", 0))
            val recipient = if (payment == null) {
                paymentRecipient
            } else {
                RecipientBranding.resolveDisplayName(
                    name = payment.name,
                    upiId = payment.upiId,
                    phone = if (payment.targetType == TargetType.PHONE) payment.phoneNumber else null
                )
            }
            putExtra("payment_recipient", recipient)
            putExtra("payment_upi_id", payment?.upiId.orEmpty())
            putExtra("payment_phone", payment?.phoneNumber.orEmpty())
            putExtra("transaction_ref", USSDController.lastTransactionRef)
        }
        maybeShowResultNotification(paymentStatus, message, intent)
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    private fun maybeShowResultNotification(
        paymentStatus: String,
        message: String?,
        resultIntent: Intent
    ) {
        if (USSDController.currentFlow != USSDController.Flow.PAYMENT) return

        val payment = USSDController.currentPayment
        val recipient = resolveCurrentRecipient(getString(R.string.result_generic_recipient))
        val amount = payment?.amount.orEmpty().ifBlank { paymentAmount }

        val title = when {
            paymentStatus == TransactionStatus.CONFIRMED -> getString(R.string.notification_success_title)
            (message ?: "").contains(getString(R.string.processing_timeout_message), ignoreCase = true) ->
                getString(R.string.notification_timeout_title)
            else -> getString(R.string.notification_failed_title)
        }

        val body = when {
            paymentStatus == TransactionStatus.CONFIRMED ->
                getString(R.string.notification_success_body, amount, recipient)
            !message.isNullOrBlank() -> message
            else -> getString(R.string.notification_failed_body, recipient)
        }

        val notificationId = USSDController.currentAttemptId.hashCode().takeIf { it != 0 }
            ?: System.currentTimeMillis().toInt()
        NotificationHelper.showPaymentResult(this, notificationId, title, body, resultIntent)
    }

    private fun recordPendingAttempt() {
        if (USSDController.currentFlow != USSDController.Flow.PAYMENT) return
        persistAttemptRecord(
            status = TransactionStatus.PENDING,
            statusSource = "manual_marked_pending",
            completedAt = System.currentTimeMillis(),
            statusMessage = "We have not received final confirmation from the bank yet."
        )
    }

    private fun removePendingAttempt() {
        if (!manualPendingRecorded && USSDController.currentAttemptId.isBlank()) return
        TransactionHistoryStore.removeByAttemptId(this, USSDController.currentAttemptId)
    }

    private fun persistAttemptRecord(
        status: String,
        statusSource: String,
        completedAt: Long,
        statusMessage: String
    ) {
        val payment = USSDController.currentPayment
        val attemptId = USSDController.currentAttemptId
        if (attemptId.isBlank()) return

        val record = TransactionRecord(
            id = attemptId,
            attemptId = attemptId,
            amount = payment?.amount.orEmpty().ifBlank { paymentAmount },
            recipientName = resolveCurrentRecipient(getString(R.string.result_generic_recipient)),
            recipientUpiId = payment?.upiId.orEmpty(),
            recipientPhone = payment?.phoneNumber.orEmpty(),
            senderName = "",
            senderUpiId = "",
            transactionRef = if (status == TransactionStatus.CONFIRMED) {
                USSDController.lastTransactionRef
            } else {
                ""
            },
            completedAt = completedAt,
            status = status,
            statusSource = statusSource,
            statusMessage = statusMessage
        )
        TransactionHistoryStore.upsert(this, record)
    }

    private fun buildConfirmedStatusMessage(): String {
        val recipient = resolveCurrentRecipient(getString(R.string.result_generic_recipient))
        return getString(R.string.result_sent_to_recipient, recipient)
    }

    private fun resolveCurrentRecipient(fallback: String): String {
        val payment = USSDController.currentPayment
        return RecipientBranding.resolveDisplayName(
            name = payment?.name ?: paymentRecipient,
            upiId = payment?.upiId,
            phone = if (payment?.targetType == TargetType.PHONE) payment.phoneNumber else null,
            fallback = fallback
        )
    }

    override fun onStop() {
        super.onStop()
        // ACCESSIBILITY LEAK FIX: If the user navigates away from this screen
        // mid-payment (e.g. opens the dialer), we must immediately kill the
        // active USSD session so the accessibility service stops intercepting
        // keyboard/dialog events in the dialer or any other app.
        //
        // We only reset if the payment hasn't already reached a terminal state
        // (SUCCESS / FAILED / BALANCE_RESULT), because in those cases the
        // activity is navigating away of its own volition (to ResultActivity)
        // and a reset would race with the navigation.
        val state = USSDController.currentState
        val isTerminal = state == USSDController.State.SUCCESS ||
            state == USSDController.State.FAILED ||
            state == USSDController.State.BALANCE_RESULT

        if (!isTerminal) {
            android.util.Log.d("ProcessingActivity", "onStop: resetting USSDController to prevent accessibility leak")
            USSDController.reset()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // Final safety reset — ensures no session lingers if onStop was skipped
        // (e.g. process kill in low-memory situations).
        USSDController.reset()
    }
}
