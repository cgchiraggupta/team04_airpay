package com.airpay.upi.history

import android.content.Context
import com.airpay.upi.data.EncryptedPrefsFactory
import org.json.JSONArray
import org.json.JSONObject

object TransactionHistoryStore {

    private const val PREFS_NAME = "airpay_secure_history"
    private const val KEY_TRANSACTIONS = "transactions"
    private const val HISTORY_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    fun upsert(context: Context, record: TransactionRecord) {
        val now = System.currentTimeMillis()
        val records = getAll(context)
            .filter { now - it.completedAt <= HISTORY_RETENTION_MS }
            .toMutableList()

        val existingIndex = records.indexOfFirst { existing ->
            when {
                record.attemptId.isNotBlank() && existing.attemptId.isNotBlank() ->
                    existing.attemptId == record.attemptId
                record.status == TransactionStatus.CONFIRMED &&
                record.transactionRef.isNotBlank() && existing.transactionRef.isNotBlank() ->
                    existing.transactionRef == record.transactionRef
                else ->
                    existing.amount == record.amount &&
                        existing.recipientUpiId == record.recipientUpiId &&
                        kotlin.math.abs(existing.completedAt - record.completedAt) < 120000L
            }
        }

        if (existingIndex >= 0) {
            records[existingIndex] = record
        } else {
            records.add(0, record)
        }

        saveAll(context, records.sortedByDescending { it.completedAt })
    }

    fun removeByAttemptId(context: Context, attemptId: String) {
        if (attemptId.isBlank()) return

        val records = getAll(context)
            .filterNot { it.attemptId == attemptId }

        saveAll(context, records.sortedByDescending { it.completedAt })
    }

    fun getAll(context: Context): List<TransactionRecord> {
        val prefs = EncryptedPrefsFactory.create(context, PREFS_NAME)
        val raw = prefs.getString(KEY_TRANSACTIONS, "[]") ?: "[]"
        val jsonArray = JSONArray(raw)
        return buildList {
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                add(
                    TransactionRecord(
                        id = item.optString("id"),
                        attemptId = item.optString("attemptId").ifBlank {
                            item.optString("id").ifBlank { item.optString("transactionRef") }
                        },
                        amount = item.optString("amount"),
                        recipientName = item.optString("recipientName"),
                        recipientUpiId = item.optString("recipientUpiId"),
                        recipientPhone = item.optString("recipientPhone"),
                        senderName = item.optString("senderName"),
                        senderUpiId = item.optString("senderUpiId"),
                        transactionRef = item.optString("transactionRef"),
                        completedAt = item.optLong("completedAt"),
                        status = item.optString("status").ifBlank { TransactionStatus.CONFIRMED },
                        statusSource = item.optString("statusSource"),
                        statusMessage = item.optString("statusMessage")
                    )
                )
            }
        }.sortedByDescending { it.completedAt }
    }

    private fun saveAll(context: Context, records: List<TransactionRecord>) {
        val jsonArray = JSONArray()
        records.forEach { record ->
            jsonArray.put(
                JSONObject().apply {
                    put("id", record.id)
                    put("attemptId", record.attemptId)
                    put("amount", record.amount)
                    put("recipientName", record.recipientName)
                    put("recipientUpiId", record.recipientUpiId)
                    put("recipientPhone", record.recipientPhone)
                    put("senderName", record.senderName)
                    put("senderUpiId", record.senderUpiId)
                    put("transactionRef", record.transactionRef)
                    put("completedAt", record.completedAt)
                    put("status", record.status)
                    put("statusSource", record.statusSource)
                    put("statusMessage", record.statusMessage)
                }
            )
        }

        EncryptedPrefsFactory.create(context, PREFS_NAME)
            .edit()
            .putString(KEY_TRANSACTIONS, jsonArray.toString())
            .apply()
    }
}
