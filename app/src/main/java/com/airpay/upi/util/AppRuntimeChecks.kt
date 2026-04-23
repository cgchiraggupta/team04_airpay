package com.airpay.upi.util

import android.content.ContentResolver
import android.provider.Settings

object AppRuntimeChecks {

    private const val USSD_SERVICE_ID = "com.airpay.upi.ussd.USSDService"

    fun isUssdAccessibilityServiceEnabled(contentResolver: ContentResolver): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(USSD_SERVICE_ID)
    }
}
