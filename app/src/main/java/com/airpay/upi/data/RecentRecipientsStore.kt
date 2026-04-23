package com.airpay.upi.data

import android.content.Context
import com.airpay.upi.util.RecipientBranding
import com.airpay.upi.ussd.UPIData

object RecentRecipientsStore {
    private const val PREFS_NAME = "airpay_recent_recipients"
    private const val KEY_RECIPIENTS = "recent_recipients_list"

    fun saveRecipient(context: Context, payment: UPIData) {
        val prefs = EncryptedPrefsFactory.create(context, PREFS_NAME)
        val currentList = prefs.getString(KEY_RECIPIENTS, "") ?: ""
        val newList = currentList.split(";;").filter { it.isNotBlank() }.toMutableList()

        val displayId = payment.upiId.ifBlank { payment.phoneNumber }
        val displayName = RecipientBranding.resolveDisplayName(
            name = payment.name,
            upiId = payment.upiId,
            phone = payment.phoneNumber,
            fallback = displayId
        )
        if (displayId.isBlank()) return

        val entry = "$displayName||$displayId"
        newList.remove(entry)
        newList.add(0, entry)
        if (newList.size > 5) {
            newList.subList(5, newList.size).clear()
        }

        prefs.edit().putString(KEY_RECIPIENTS, newList.joinToString(";;")).apply()
    }

    fun getRecentRecipients(context: Context): List<Pair<String, String>> {
        val prefs = EncryptedPrefsFactory.create(context, PREFS_NAME)
        return (prefs.getString(KEY_RECIPIENTS, "") ?: "")
            .split(";;")
            .filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split("||")
                if (parts.size == 2) {
                    RecipientBranding.resolveDisplayName(
                        name = parts[0],
                        upiId = parts[1],
                        fallback = parts[1]
                    ) to parts[1]
                } else null
            }
    }
}
