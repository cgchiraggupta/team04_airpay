package com.airpay.upi.data

import android.content.Context

object MigrationHelper {

    private const val LEGACY_PREFS_NAME = "airpay_history"
    private const val LEGACY_KEY_TRANSACTIONS = "transactions"
    private const val SECURE_PREFS_NAME = "airpay_secure_history"
    private const val SECURE_KEY_TRANSACTIONS = "transactions"
    private const val META_PREFS_NAME = "airpay_migrations"
    private const val KEY_HISTORY_MIGRATED = "history_migrated"

    fun migrateLegacyHistory(context: Context) {
        val migrationPrefs = context.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)
        if (migrationPrefs.getBoolean(KEY_HISTORY_MIGRATED, false)) return

        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyValue = legacyPrefs.getString(LEGACY_KEY_TRANSACTIONS, null)

        if (!legacyValue.isNullOrBlank() && legacyValue != "[]") {
            val securePrefs = EncryptedPrefsFactory.create(context, SECURE_PREFS_NAME)
            securePrefs.edit().putString(SECURE_KEY_TRANSACTIONS, legacyValue).apply()
            legacyPrefs.edit().remove(LEGACY_KEY_TRANSACTIONS).apply()
        }

        migrationPrefs.edit().putBoolean(KEY_HISTORY_MIGRATED, true).apply()
    }
}
