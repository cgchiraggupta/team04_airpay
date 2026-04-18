package com.airpay.upi

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.airpay.upi.util.LocaleHelper

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyStoredLocale(newBase))
    }
}
