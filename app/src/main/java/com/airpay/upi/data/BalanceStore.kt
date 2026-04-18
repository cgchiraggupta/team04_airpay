package com.airpay.upi.data

import android.content.Context

object BalanceStore {
    private const val PREFS_NAME = "airpay_balance"
    private const val KEY_BALANCE = "last_balance"

    fun saveBalance(context: Context, balance: String) {
        EncryptedPrefsFactory.create(context, PREFS_NAME)
            .edit()
            .putString(KEY_BALANCE, balance)
            .apply()
    }

    fun getBalance(context: Context): String {
        return EncryptedPrefsFactory.create(context, PREFS_NAME)
            .getString(KEY_BALANCE, "-") ?: "-"
    }
}
