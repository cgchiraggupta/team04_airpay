package com.airpay.upi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.widget.doAfterTextChanged
import android.widget.Toast
import com.airpay.upi.data.RecentRecipientsStore
import com.airpay.upi.util.AppRuntimeChecks
import com.airpay.upi.util.RecipientBranding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class PaymentActivity : BaseActivity() {

    private lateinit var etUpiId: TextInputEditText
    private lateinit var etName: TextInputEditText
    private lateinit var etAmount: TextInputEditText
    private lateinit var btnPay: MaterialButton
    private lateinit var tilUpiId: TextInputLayout

    // UPI ID format: localpart@bankhandle
    // localpart: alphanumeric, dots, hyphens, underscores (3–256 chars)
    // bankhandle: letters only, 2–64 chars
    private val upiIdRegex = Regex("^[a-zA-Z0-9._\\-]{3,256}@[a-zA-Z][a-zA-Z0-9]{1,64}$")

    // Amount: positive number, max 2 decimal places, no more than 1,00,000
    private val amountRegex = Regex("^\\d+(\\.\\d{1,2})?$")
    private val maxTransactionAmount = 100000.0

    // Prevent double-triggering of payment
    private var paymentInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        initViews()
        parseIntentData()
        setupClickListeners()
        setupRecipientBranding()
        animateEntry()
    }

    private fun animateEntry() {
        val content = findViewById<View>(android.R.id.content)
        val formContainer = content.findViewById<View>(R.id.tilUpiId)?.parent as? View ?: return
        val views = mutableListOf<View>()
        if (formContainer is android.view.ViewGroup) {
            for (i in 0 until formContainer.childCount) {
                views.add(formContainer.getChildAt(i))
            }
        }
        views.forEach { v -> v.alpha = 0f; v.translationY = 20f }
        views.forEachIndexed { i, v ->
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(380)
                .setStartDelay(150L + i * 60L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_down_enter, R.anim.slide_down_exit)
    }

    private fun initViews() {
        etUpiId = findViewById(R.id.etUpiId)
        etName = findViewById(R.id.etName)
        etAmount = findViewById(R.id.etAmount)
        btnPay = findViewById(R.id.btnPay)
        tilUpiId = findViewById(R.id.tilUpiId)
    }

    private fun parseIntentData() {
        intent.getStringExtra("recipient_upi_id")?.trim()?.takeIf { it.isNotBlank() }?.let {
            etUpiId.setText(it)
        }
        intent.getStringExtra("recipient_name")?.trim()?.takeIf { it.isNotBlank() }?.let {
            etName.setText(
                RecipientBranding.resolveDisplayName(
                    name = it,
                    upiId = etUpiId.text?.toString(),
                    fallback = it
                )
            )
        }

        val upiString = intent.getStringExtra("upi_string") ?: return

        val uri = Uri.parse(upiString)
        if (uri.scheme?.lowercase() != "upi" || uri.host?.lowercase() != "pay") {
            Toast.makeText(this, R.string.payment_invalid_qr_not_upi, Toast.LENGTH_SHORT).show()
            return
        }

        val payeeAddress = uri.getQueryParameter("pa")
        val payeeName = uri.getQueryParameter("pn")
        val amount = uri.getQueryParameter("am")

        if (payeeAddress.isNullOrBlank()) {
            Toast.makeText(this, R.string.payment_qr_missing_upi_id, Toast.LENGTH_SHORT).show()
            return
        }

        if (!upiIdRegex.matches(payeeAddress)) {
            Toast.makeText(this, R.string.payment_qr_invalid_upi_id, Toast.LENGTH_SHORT).show()
            return
        }

        etUpiId.setText(payeeAddress)

        payeeName?.take(100)?.let { displayName ->
            etName.setText(
                RecipientBranding.resolveDisplayName(
                    name = displayName,
                    upiId = payeeAddress,
                    fallback = displayName
                )
            )
        }
        amount?.let {
            val parsedAmount = it.toDoubleOrNull()
            if (parsedAmount != null && parsedAmount > 0 && parsedAmount <= maxTransactionAmount) {
                etAmount.setText(it)
            } else {
                Toast.makeText(this, R.string.payment_qr_invalid_amount, Toast.LENGTH_SHORT).show()
            }
        }

        Toast.makeText(this, R.string.payment_qr_scanned, Toast.LENGTH_SHORT).show()

        applyRecipientBranding()
    }

    private fun setupClickListeners() {
        btnPay.setOnClickListener {
            validateAndPay()
        }

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    private fun setupRecipientBranding() {
        etUpiId.doAfterTextChanged { applyRecipientBranding() }
        etName.doAfterTextChanged {
            if (RecipientBranding.isAirpayRecipient(it?.toString(), etUpiId.text?.toString())) {
                applyRecipientBranding()
            }
        }
    }

    private fun applyRecipientBranding() {
        val upiId = etUpiId.text?.toString()?.trim().orEmpty()
        val currentName = etName.text?.toString()?.trim().orEmpty()
        if (!RecipientBranding.isAirpayRecipient(currentName, upiId)) return

        if (currentName != RecipientBranding.AIRPAY_DISPLAY_NAME) {
            etName.setText(RecipientBranding.AIRPAY_DISPLAY_NAME)
            etName.setSelection(etName.text?.length ?: 0)
        }
    }

    private fun validateAndPay(): Boolean {
        // Prevent double-triggering
        if (paymentInProgress) {
            return false
        }

        val upiId = etUpiId.text?.toString()?.trim() ?: ""
        val name = RecipientBranding.resolveDisplayName(
            name = etName.text?.toString()?.trim(),
            upiId = upiId
        )
        val phoneNumber = ""
        val amount = etAmount.text?.toString()?.trim() ?: ""

        if (upiId.isEmpty()) {
            etUpiId.error = getString(R.string.payment_error_upi_required)
            etUpiId.requestFocus()
            return false
        }

        if (upiId.isNotEmpty() && !upiIdRegex.matches(upiId)) {
            etUpiId.error = getString(R.string.payment_error_invalid_upi_format)
            etUpiId.requestFocus()
            return false
        }

        if (amount.isEmpty()) {
            etAmount.error = getString(R.string.payment_error_enter_amount)
            etAmount.requestFocus()
            return false
        }

        if (!amountRegex.matches(amount)) {
            etAmount.error = getString(R.string.payment_error_invalid_amount_format)
            etAmount.requestFocus()
            return false
        }

        val amountDouble = amount.toDoubleOrNull()
        if (amountDouble == null || amountDouble <= 0) {
            etAmount.error = getString(R.string.payment_error_positive_amount)
            etAmount.requestFocus()
            return false
        }

        if (amountDouble > maxTransactionAmount) {
            etAmount.error = getString(R.string.payment_error_max_amount)
            etAmount.requestFocus()
            return false
        }

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                getString(R.string.payment_helper_required),
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }

        maybeWarnAboutCarrier()

        paymentInProgress = true
        val upiData = UPIData(
            upiId = upiId,
            name = name,
            amount = amount,
            phoneNumber = phoneNumber,
        )

        USSDController.reset()
        USSDController.startSession()
        USSDController.currentPayment = upiData
        USSDController.currentAttemptId = UUID.randomUUID().toString()
        USSDController.currentFlow = USSDController.Flow.PAYMENT
        RecentRecipientsStore.saveRecipient(this, upiData)
        initiateUSSDPayment()
        return true
    }

    private fun initiateUSSDPayment() {
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) != 
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(
                this, 
                getString(R.string.payment_phone_permission_required), 
                Toast.LENGTH_LONG
            ).show()
            paymentInProgress = false
            return
        }

        try {
            val processingIntent = Intent(this, ProcessingActivity::class.java)
            processingIntent.putExtra("payment_amount", currentPaymentAmount())
            processingIntent.putExtra("payment_recipient", currentRecipientLabel())
            processingIntent.putExtra("launch_payment_ussd", true)
            startActivity(processingIntent)
            overridePendingTransition(R.anim.slide_up_enter, R.anim.slide_up_exit)
            finish()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.payment_phone_permission_denied, Toast.LENGTH_LONG).show()
            paymentInProgress = false
        }
    }

    private fun maybeWarnAboutCarrier() {
        val telephonyManager = getSystemService(TelephonyManager::class.java) ?: return
        val carrierName = telephonyManager.networkOperatorName?.trim().orEmpty()
        if (carrierName.contains("jio", ignoreCase = true)) {
            Toast.makeText(
                this,
                getString(R.string.payment_jio_warning),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun currentRecipientLabel(): String {
        val name = etName.text?.toString()?.trim().orEmpty()
        val upiId = etUpiId.text?.toString()?.trim().orEmpty()
        return RecipientBranding.resolveDisplayName(
            name = name,
            upiId = upiId,
            fallback = upiId
        )
    }

    private fun currentPaymentAmount(): String {
        return etAmount.text?.toString()?.trim().orEmpty()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
    }
}
