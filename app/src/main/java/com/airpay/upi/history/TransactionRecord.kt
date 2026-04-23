package com.airpay.upi.history

object TransactionStatus {
    const val PENDING = "pending"
    const val CONFIRMED = "confirmed"
    const val FAILED = "failed"
}

data class TransactionRecord(
    val id: String,
    val attemptId: String,
    val amount: String,
    val recipientName: String,
    val recipientUpiId: String,
    val recipientPhone: String,
    val senderName: String,
    val senderUpiId: String,
    val transactionRef: String,
    val completedAt: Long,
    val status: String,
    val statusSource: String,
    val statusMessage: String
)
