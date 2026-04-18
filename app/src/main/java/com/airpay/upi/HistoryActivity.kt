package com.airpay.upi

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.airpay.upi.history.TransactionHistoryStore
import com.airpay.upi.history.TransactionRecord
import com.airpay.upi.history.TransactionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        renderHistory()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_down_enter, R.anim.slide_down_exit)
    }

    private fun renderHistory() {
        val container = findViewById<LinearLayout>(R.id.historyContainer)
        val emptyState = findViewById<TextView>(R.id.tvEmptyState)
        val records = TransactionHistoryStore.getAll(this)

        container.removeAllViews()
        emptyState.visibility = if (records.isEmpty()) TextView.VISIBLE else TextView.GONE

        val inflater = LayoutInflater.from(this)
        records.forEachIndexed { index, record ->
            val view = inflater.inflate(R.layout.item_history_transaction, container, false)
            bindItem(view, record)
            // Staggered entry animation
            view.alpha = 0f
            view.translationY = 20f
            container.addView(view)
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay(80L + index * 50L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    private fun bindItem(view: android.view.View, record: TransactionRecord) {
        val title = view.findViewById<TextView>(R.id.tvHistoryTitle)
        val subtitle = view.findViewById<TextView>(R.id.tvHistorySubtitle)
        val amount = view.findViewById<TextView>(R.id.tvHistoryAmount)
        val status = view.findViewById<TextView>(R.id.tvHistoryStatus)
        val date = view.findViewById<TextView>(R.id.tvHistoryDate)

        title.text = record.recipientName.ifBlank {
            record.recipientUpiId.ifBlank { getString(R.string.history_fallback_payment) }
        }
        subtitle.text = record.recipientUpiId.ifBlank {
            record.recipientPhone.ifBlank { getString(R.string.result_method_bank_transfer) }
        }
        amount.text = getString(R.string.history_amount_format, record.amount)
        status.text = when (record.status) {
            TransactionStatus.PENDING -> getString(R.string.history_status_pending)
            else -> getString(R.string.history_status_confirmed)
        }
        if (record.status == TransactionStatus.PENDING) {
            status.setBackgroundResource(R.drawable.bg_flow_pending_badge)
            status.setTextColor(ContextCompat.getColor(this, R.color.warning))
        } else {
            status.setBackgroundResource(R.drawable.bg_flow_success_badge)
            status.setTextColor(ContextCompat.getColor(this, R.color.success))
        }
        date.text = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            .format(Date(record.completedAt))

        view.setOnClickListener {
            startActivity(
                Intent(this, ResultActivity::class.java).apply {
                    putExtra("is_success", record.status == TransactionStatus.CONFIRMED)
                    putExtra("payment_status", record.status)
                    putExtra("result_type", "payment")
                    putExtra("message", record.statusMessage)
                    putExtra("payment_amount", record.amount)
                    putExtra("payment_recipient", record.recipientName.ifBlank { record.recipientUpiId })
                    putExtra("payment_upi_id", record.recipientUpiId)
                    putExtra("payment_phone", record.recipientPhone)
                    putExtra("transaction_ref", record.transactionRef)
                    putExtra("attempt_id", record.attemptId)
                    putExtra("sender_name", record.senderName)
                    putExtra("sender_upi_id", record.senderUpiId)
                    putExtra("completed_at", record.completedAt)
                    putExtra("status_source", record.statusSource)
                }
            )
            overridePendingTransition(R.anim.slide_up_enter, R.anim.slide_up_exit)
        }
    }
}
