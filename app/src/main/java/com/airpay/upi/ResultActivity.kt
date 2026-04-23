package com.airpay.upi

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ContentValues
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.airpay.upi.history.TransactionStatus
import com.airpay.upi.ussd.USSDController
import com.airpay.upi.util.RecipientBranding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ResultActivity : BaseActivity() {
    private var successPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        val isSuccess = intent.getBooleanExtra("is_success", false)
        val paymentStatus = intent.getStringExtra("payment_status").orEmpty().ifBlank {
            if (isSuccess) TransactionStatus.CONFIRMED else TransactionStatus.FAILED
        }
        val resultType = intent.getStringExtra("result_type") ?: "payment"
        val customMsg = intent.getStringExtra("message")
        val paymentAmount = intent.getStringExtra("payment_amount")
        val paymentRecipient = intent.getStringExtra("payment_recipient")
        val paymentUpiId = intent.getStringExtra("payment_upi_id").orEmpty()
        val paymentPhone = intent.getStringExtra("payment_phone").orEmpty()
        val transactionRef = intent.getStringExtra("transaction_ref")
            ?: USSDController.lastTransactionRef
        val retryCount = intent.getIntExtra("retry_count", 0)

        val payment = USSDController.currentPayment

        val ivStatus = findViewById<ImageView>(R.id.ivStatus)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvMessage = findViewById<TextView>(R.id.tvMessage)
        val tvAmount = findViewById<TextView>(R.id.tvAmount)
        val btnDone = findViewById<MaterialButton>(R.id.btnDone)
        val btnSaveReceipt = findViewById<MaterialButton>(R.id.btnSaveReceipt)
        val btnShareReceipt = findViewById<MaterialButton>(R.id.btnShareReceipt)
        val btnRetry = findViewById<MaterialButton>(R.id.btnRetry)
        val receiptContent = findViewById<View>(R.id.receiptContent)
        val cardDetails = findViewById<MaterialCardView>(R.id.cardDetails)
        val tvCardBadge = findViewById<TextView>(R.id.tvCardBadge)
        val tvRecipient = findViewById<TextView>(R.id.tvRecipient)
        val tvSender = findViewById<TextView>(R.id.tvSender)
        val tvUpiId = findViewById<TextView>(R.id.tvUpiId)
        val tvPhone = findViewById<TextView>(R.id.tvPhone)
        val tvDateTime = findViewById<TextView>(R.id.tvDateTime)
        val tvTransactionRefLabel = findViewById<TextView>(R.id.tvTransactionRefLabel)
        val tvTransactionRef = findViewById<TextView>(R.id.tvTransactionRef)
        val rowUpiId = findViewById<LinearLayout>(R.id.rowUpiId)
        val rowPhone = findViewById<LinearLayout>(R.id.rowPhone)
        val rowTransactionRef = findViewById<LinearLayout>(R.id.rowTransactionRef)
        val dividerAfterUpi = findViewById<View>(R.id.dividerAfterUpi)
        val dividerAfterPhone = findViewById<View>(R.id.dividerAfterPhone)
        val providedSenderName = intent.getStringExtra("sender_name").orEmpty().trim()
        val senderName = if (RecipientBranding.isAirpaySender(providedSenderName)) {
            RecipientBranding.AIRPAY_DISPLAY_NAME
        } else {
            providedSenderName
        }

        val completionTime = intent.getLongExtra("completed_at", 0L)
            .takeIf { it > 0 }
            ?: USSDController.lastCompletionTime.takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        tvDateTime.text = dateFormat.format(Date(completionTime))
        tvSender.text = senderName

        when {
            // ── Balance result ──────────────────────────────────────
            resultType == "balance" -> {
                ivStatus.setImageResource(R.drawable.ic_success)
                ivStatus.setColorFilter(ContextCompat.getColor(this, R.color.success))
                tvTitle.text = getString(R.string.result_balance_title)
                val balanceValue = extractBalanceAmount(customMsg.orEmpty())
                if (balanceValue.isNotBlank()) {
                    tvAmount.text = balanceValue
                    tvAmount.visibility = View.VISIBLE
                    tvMessage.text = getString(R.string.result_balance_message)
                } else {
                    tvAmount.visibility = View.GONE
                    tvMessage.text = customMsg ?: getString(R.string.result_balance_complete)
                }
                cardDetails.visibility = View.GONE
                btnShareReceipt.visibility = View.GONE
                btnSaveReceipt.visibility = View.GONE
                btnRetry.visibility = View.GONE
            }

            // ── Payment pending ────────────────────────────────────
            paymentStatus == TransactionStatus.PENDING -> {
                ivStatus.setImageResource(R.drawable.ic_check_circle)
                ivStatus.setColorFilter(ContextCompat.getColor(this, R.color.warning))
                tvTitle.text = getString(R.string.result_pending_title)
                tvCardBadge.text = getString(R.string.result_pending_badge)
                tvCardBadge.setBackgroundResource(R.drawable.bg_flow_pending_badge)
                tvCardBadge.setTextColor(ContextCompat.getColor(this, R.color.warning))
                tvMessage.text = customMsg ?: getString(R.string.result_pending_message)
                bindTransactionDetails(
                    payment = payment,
                    paymentRecipient = paymentRecipient,
                    paymentAmount = paymentAmount,
                    paymentUpiId = paymentUpiId,
                    paymentPhone = paymentPhone,
                    transactionRef = "",
                    cardDetails = cardDetails,
                    tvRecipient = tvRecipient,
                    rowUpiId = rowUpiId,
                    dividerAfterUpi = dividerAfterUpi,
                    tvUpiId = tvUpiId,
                    rowPhone = rowPhone,
                    dividerAfterPhone = dividerAfterPhone,
                    tvPhone = tvPhone,
                    rowTransactionRef = rowTransactionRef,
                    tvTransactionRefLabel = tvTransactionRefLabel,
                    tvTransactionRef = tvTransactionRef,
                    tvAmount = tvAmount
                )
                btnShareReceipt.visibility = View.GONE
                btnSaveReceipt.visibility = View.GONE
                btnRetry.visibility = View.GONE
            }

            // ── Payment confirmed ──────────────────────────────────
            paymentStatus == TransactionStatus.CONFIRMED -> {
                ivStatus.setImageResource(R.drawable.ic_success)
                ivStatus.setColorFilter(ContextCompat.getColor(this, R.color.success))
                tvTitle.text = getString(R.string.result_success_title)
                tvCardBadge.text = getString(R.string.result_receipt_badge)
                tvCardBadge.setBackgroundResource(R.drawable.bg_flow_success_badge)
                tvCardBadge.setTextColor(ContextCompat.getColor(this, R.color.success))

                val recipient = resolveRecipient(paymentRecipient, payment, paymentUpiId)
                tvMessage.text = customMsg ?: getString(R.string.result_sent_to_recipient, recipient)
                bindTransactionDetails(
                    payment = payment,
                    paymentRecipient = paymentRecipient,
                    paymentAmount = paymentAmount,
                    paymentUpiId = paymentUpiId,
                    paymentPhone = paymentPhone,
                    transactionRef = transactionRef,
                    cardDetails = cardDetails,
                    tvRecipient = tvRecipient,
                    rowUpiId = rowUpiId,
                    dividerAfterUpi = dividerAfterUpi,
                    tvUpiId = tvUpiId,
                    rowPhone = rowPhone,
                    dividerAfterPhone = dividerAfterPhone,
                    tvPhone = tvPhone,
                    rowTransactionRef = rowTransactionRef,
                    tvTransactionRefLabel = tvTransactionRefLabel,
                    tvTransactionRef = tvTransactionRef,
                    tvAmount = tvAmount
                )
                btnRetry.visibility = View.GONE
                receiptContent.post { playSuccessSound() }
            }

            // ── Payment failed ─────────────────────────────────────
            else -> {
                ivStatus.setImageResource(R.drawable.ic_error)
                ivStatus.setColorFilter(ContextCompat.getColor(this, R.color.error))
                tvTitle.text = getString(R.string.result_failed_title)
                tvMessage.text = customMsg ?: getString(R.string.result_failed_message)
                tvAmount.visibility = View.GONE
                cardDetails.visibility = View.GONE
                btnShareReceipt.visibility = View.GONE
                btnSaveReceipt.visibility = View.GONE
                val canRetry = retryCount < 2 &&
                    (paymentUpiId.isNotBlank() || paymentPhone.isNotBlank() || payment != null)
                if (canRetry) {
                    btnRetry.visibility = View.VISIBLE
                    btnRetry.text = getString(R.string.result_retry_attempt, retryCount + 2, 3)
                } else {
                    btnRetry.visibility = View.GONE
                }
            }
        }

        btnSaveReceipt.setOnClickListener {
            saveReceiptToGallery(receiptContent)
        }

        btnShareReceipt.setOnClickListener {
            shareReceiptImage(
                receiptContent = receiptContent,
                title = tvTitle.text.toString(),
                amount = tvAmount.text?.toString().orEmpty(),
                message = tvMessage.text.toString(),
                recipient = tvRecipient.text.toString(),
                upiId = tvUpiId.text.toString(),
                dateTime = tvDateTime.text.toString(),
                transactionRef = tvTransactionRef.text.toString()
            )
        }

        btnRetry.setOnClickListener {
            retryPayment(
                retryCount = retryCount + 1,
                payment = payment,
                paymentAmount = paymentAmount,
                paymentRecipient = paymentRecipient,
                paymentUpiId = paymentUpiId,
                paymentPhone = paymentPhone
            )
        }

        btnDone.setOnClickListener {
            USSDController.reset()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        }

        animateEntry(ivStatus, tvTitle, tvAmount, tvMessage, cardDetails, btnDone, btnSaveReceipt, btnShareReceipt)
    }

    private fun animateEntry(
        ivStatus: ImageView,
        tvTitle: TextView,
        tvAmount: TextView,
        tvMessage: TextView,
        cardDetails: MaterialCardView,
        btnDone: MaterialButton,
        btnSaveReceipt: MaterialButton,
        btnShareReceipt: MaterialButton
    ) {
        // Bounce-scale the status icon
        ivStatus.scaleX = 0f
        ivStatus.scaleY = 0f
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ivStatus, "scaleX", 0f, 1f),
                ObjectAnimator.ofFloat(ivStatus, "scaleY", 0f, 1f)
            )
            duration = 500
            startDelay = 100
            interpolator = OvershootInterpolator(2.2f)
            start()
        }

        // Staggered fade+slide for text and card
        val staggerViews = listOf(tvTitle, tvAmount, tvMessage, cardDetails, btnSaveReceipt, btnDone)
            .filter { it.visibility == View.VISIBLE }
        staggerViews.forEach { v -> v.alpha = 0f; v.translationY = 24f }
        staggerViews.forEachIndexed { i, v ->
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(380)
                .setStartDelay(350L + i * 80L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }

        // Share button follows save button timing
        if (btnShareReceipt.visibility == View.VISIBLE) {
            btnShareReceipt.alpha = 0f
            btnShareReceipt.translationY = 24f
            btnShareReceipt.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(380)
                .setStartDelay(350L + 4 * 80L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    private fun resolveRecipient(
        paymentRecipient: String?,
        payment: com.airpay.upi.ussd.UPIData?,
        paymentUpiId: String
    ): String {
        return RecipientBranding.resolveDisplayName(
            name = paymentRecipient ?: payment?.name,
            upiId = paymentUpiId.ifBlank { payment?.upiId.orEmpty() },
            fallback = getString(R.string.result_generic_recipient)
        )
    }

    private fun bindTransactionDetails(
        payment: com.airpay.upi.ussd.UPIData?,
        paymentRecipient: String?,
        paymentAmount: String?,
        paymentUpiId: String,
        paymentPhone: String,
        transactionRef: String,
        cardDetails: MaterialCardView,
        tvRecipient: TextView,
        rowUpiId: LinearLayout,
        dividerAfterUpi: View,
        tvUpiId: TextView,
        rowPhone: LinearLayout,
        dividerAfterPhone: View,
        tvPhone: TextView,
        rowTransactionRef: LinearLayout,
        tvTransactionRefLabel: TextView,
        tvTransactionRef: TextView,
        tvAmount: TextView
    ) {
        val recipient = resolveRecipient(paymentRecipient, payment, paymentUpiId)
        val amount = (paymentAmount ?: payment?.amount)
            ?.takeIf { it.isNotBlank() && it != "0" }
        val upi = paymentUpiId.ifEmpty { payment?.upiId.orEmpty() }
        val phone = paymentPhone.ifEmpty { payment?.phoneNumber.orEmpty() }

        cardDetails.visibility = View.VISIBLE
        tvRecipient.text = RecipientBranding.resolveDisplayName(
            name = recipient,
            upiId = upi,
            phone = if (phone.isNotBlank()) phone else null,
            fallback = getString(R.string.result_generic_recipient)
        )

        if (amount != null) {
            tvAmount.text = getString(R.string.history_amount_format, amount)
            tvAmount.visibility = View.VISIBLE
        } else {
            tvAmount.visibility = View.GONE
        }

        if (upi.isNotEmpty()) {
            rowUpiId.visibility = View.VISIBLE
            dividerAfterUpi.visibility = View.VISIBLE
            tvUpiId.text = upi
        } else {
            rowUpiId.visibility = View.GONE
            dividerAfterUpi.visibility = View.GONE
        }

        if (phone.isNotEmpty()) {
            rowPhone.visibility = View.VISIBLE
            dividerAfterPhone.visibility = View.VISIBLE
            tvPhone.text = phone
        } else {
            rowPhone.visibility = View.GONE
            dividerAfterPhone.visibility = View.GONE
        }

        if (transactionRef.isNotEmpty()) {
            rowTransactionRef.visibility = View.VISIBLE
            tvTransactionRefLabel.text = getString(R.string.result_transaction_id_label)
            tvTransactionRef.text = transactionRef
        } else {
            rowTransactionRef.visibility = View.GONE
        }
    }

    private fun retryPayment(
        retryCount: Int,
        payment: com.airpay.upi.ussd.UPIData?,
        paymentAmount: String?,
        paymentRecipient: String?,
        paymentUpiId: String,
        paymentPhone: String
    ) {
        if (retryCount > 2) {
            android.widget.Toast.makeText(this, R.string.result_retry_limit_reached, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val amount = (paymentAmount ?: payment?.amount).orEmpty()
        val upiId = paymentUpiId.ifEmpty { payment?.upiId.orEmpty() }
        val phone = paymentPhone.ifEmpty { payment?.phoneNumber.orEmpty() }
        val name = paymentRecipient.orEmpty().ifBlank { payment?.name.orEmpty() }

        if (amount.isBlank() || upiId.isBlank()) {
            android.widget.Toast.makeText(this, R.string.result_retry_missing_data, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        USSDController.reset()
        USSDController.startSession()
        USSDController.currentPayment = com.airpay.upi.ussd.UPIData(
            upiId = upiId,
            name = name,
            amount = amount,
            phoneNumber = phone,
        )
        USSDController.currentAttemptId = UUID.randomUUID().toString()
        USSDController.currentFlow = USSDController.Flow.PAYMENT

        val intent = Intent(this, ProcessingActivity::class.java).apply {
            putExtra("payment_amount", amount)
            putExtra("payment_recipient", paymentRecipient.orEmpty().ifBlank { upiId })
            putExtra("payment_upi_id", upiId)
            putExtra("payment_phone", phone)
            putExtra("retry_count", retryCount)
            putExtra("launch_payment_ussd", true)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_up_enter, R.anim.slide_up_exit)
        finish()
    }

    private fun extractBalanceAmount(message: String): String {
        if (message.isBlank()) return ""
        val regex = Regex("""(?:rs\.?\s*|₹\s*)([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
        val match = regex.find(message) ?: return ""
        return "₹${match.groupValues[1]}"
    }

    private fun saveReceiptToGallery(receiptContent: View) {
        val bitmap = renderReceiptBitmap(receiptContent)
        val fileName = "airpay-receipt-${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/airpay")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            android.widget.Toast.makeText(this, R.string.result_receipt_saved, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(this, R.string.result_receipt_save_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareReceiptImage(
        receiptContent: View,
        title: String,
        amount: String,
        message: String,
        recipient: String,
        upiId: String,
        dateTime: String,
        transactionRef: String
    ) {
        try {
            val bitmap = renderReceiptBitmap(receiptContent)
            if (bitmap.width <= 1 || bitmap.height <= 1) {
                android.widget.Toast.makeText(this, R.string.result_receipt_not_ready, android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val receiptsDir = File(cacheDir, "shared_receipts").apply { mkdirs() }
            val receiptFile = File(receiptsDir, "receipt-${System.currentTimeMillis()}.png")
            FileOutputStream(receiptFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }

            val receiptUri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                receiptFile
            )

            val shareText = buildString {
                appendLine(title)
                if (amount.isNotBlank()) appendLine(amount)
                appendLine(message)
                if (recipient.isNotBlank() && recipient != getString(R.string.result_placeholder_dash)) {
                    appendLine(getString(R.string.result_share_paid_to, recipient))
                }
                if (upiId.isNotBlank() && upiId != getString(R.string.result_placeholder_dash)) {
                    appendLine(getString(R.string.result_share_upi_id, upiId))
                }
                appendLine(getString(R.string.result_share_date_time, dateTime))
                if (transactionRef.isNotBlank() && transactionRef != getString(R.string.result_placeholder_dash)) {
                    appendLine(getString(R.string.result_share_transaction_ref, transactionRef))
                }
                append(getString(R.string.result_method_share_line))
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_STREAM, receiptUri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                clipData = ClipData.newRawUri("receipt", receiptUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            grantUriPermissionToShareTargets(receiptUri, shareIntent)

            val chooser = Intent.createChooser(shareIntent, getString(R.string.result_share_chooser)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("airpay_share", "Share failed", e)
            android.widget.Toast.makeText(this, getString(R.string.result_share_error, e.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun renderReceiptBitmap(receiptContent: View): Bitmap {
        if (receiptContent.width == 0 || receiptContent.height == 0) {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            receiptContent.measure(widthSpec, heightSpec)
            receiptContent.layout(0, 0, receiptContent.measuredWidth, receiptContent.measuredHeight)
        }
        val bitmap = Bitmap.createBitmap(
            receiptContent.width.coerceAtLeast(1),
            receiptContent.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        receiptContent.draw(canvas)
        return bitmap
    }

    private fun grantUriPermissionToShareTargets(uri: Uri, shareIntent: Intent) {
        val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val resolvedActivities = packageManager.queryIntentActivities(
            shareIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )

        resolvedActivities.forEach { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@forEach
            grantUriPermission(packageName, uri, permissionFlags)
        }
    }

    private fun playSuccessSound() {
        successPlayer?.release()
        successPlayer = null

        runCatching {
            val audioFile = resources.openRawResourceFd(R.raw.payment_success) ?: return
            successPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(audioFile.fileDescriptor, audioFile.startOffset, audioFile.length)
                audioFile.close()
                isLooping = false
                setVolume(1f, 1f)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { player ->
                    player.release()
                    if (successPlayer === player) {
                        successPlayer = null
                    }
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    if (successPlayer === player) {
                        successPlayer = null
                    }
                    true
                }
                prepareAsync()
            }
        }.onFailure {
            successPlayer?.release()
            successPlayer = null
        }
    }

    override fun onDestroy() {
        successPlayer?.release()
        successPlayer = null
        super.onDestroy()
    }
}
