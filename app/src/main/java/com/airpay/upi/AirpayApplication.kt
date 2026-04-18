package com.airpay.upi

import android.app.Application
import android.content.Context
import com.airpay.upi.data.MigrationHelper
import com.airpay.upi.util.LocaleHelper
import com.airpay.upi.util.NotificationHelper

class AirpayApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyStoredLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        MigrationHelper.migrateLegacyHistory(this)
        NotificationHelper.createChannel(this)
    }
}
