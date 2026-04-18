package com.airpay.upi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.airpay.upi.data.BalanceStore
import com.airpay.upi.data.RecentRecipientsStore
import com.airpay.upi.ui.home.HomeScreen
import com.airpay.upi.ussd.USSDController
import com.airpay.upi.util.AppRuntimeChecks
import com.airpay.upi.util.LocaleHelper

class MainActivity : BaseActivity() {

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val PHONE_PERMISSION_CODE = 101
    }

    private var balance by mutableStateOf("-")
    private var serviceEnabled by mutableStateOf(false)
    private var languageLabel by mutableStateOf("हिंदी")
    private var recentRecipients by mutableStateOf(emptyList<Pair<String, String>>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        refreshHomeState()

        setContent {
            HomeScreen(
                balance = balance,
                serviceEnabled = serviceEnabled,
                currentLanguageLabel = languageLabel,
                recentRecipients = recentRecipients,
                onScanQR = {
                    if (checkCameraPermission()) {
                        startActivity(Intent(this, ScannerActivity::class.java))
                        overridePendingTransition(R.anim.slide_up_enter, R.anim.slide_up_exit)
                    } else {
                        requestCameraPermission()
                    }
                },
                onManualPay = {
                    startActivity(Intent(this, PaymentActivity::class.java))
                    overridePendingTransition(R.anim.slide_up_enter, R.anim.slide_up_exit)
                },
                onVoicePay = { dialVoicePay() },
                onCheckBalance = { checkBalance() },
                onOpenHistory = {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    overridePendingTransition(R.anim.slide_up_enter, R.anim.slide_up_exit)
                },
                onRecipientClick = { name, upiId ->
                    startActivity(
                        Intent(this, PaymentActivity::class.java).apply {
                            putExtra("recipient_name", name)
                            putExtra("recipient_upi_id", upiId)
                        }
                    )
                    overridePendingTransition(R.anim.slide_up_enter, R.anim.slide_up_exit)
                },
                onOpenAccessibility = { openAccessibilitySettings() },
                onToggleLanguage = { toggleLanguage() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHomeState()
    }

    private fun refreshHomeState() {
        balance = BalanceStore.getBalance(this)
        serviceEnabled = isAccessibilityServiceEnabled()
        recentRecipients = RecentRecipientsStore.getRecentRecipients(this)
        val currentLanguage = LocaleHelper.getStoredLanguage(this)
            ?: resources.configuration.locales[0]?.language
            ?: "en"
        languageLabel = if (currentLanguage == "hi") {
            getString(R.string.language_switch_to_english)
        } else {
            getString(R.string.language_switch_to_hindi)
        }
    }

    private fun checkBalance() {
        maybeWarnAboutCarrier()

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, getString(R.string.main_balance_helper_required), Toast.LENGTH_LONG).show()
            return
        }

        USSDController.reset()
        USSDController.startSession()
        USSDController.currentFlow = USSDController.Flow.BALANCE

        if (!checkPhonePermission()) {
            requestPhonePermission()
            return
        }

        startActivity(
            Intent(this, ProcessingActivity::class.java).apply {
                putExtra("launch_balance_ussd", true)
            }
        )
        overridePendingTransition(R.anim.slide_up_enter, R.anim.slide_up_exit)
    }

    private fun dialVoicePay() {
        if (!checkPhonePermission()) {
            Toast.makeText(this, R.string.main_phone_permission_voice_pay, Toast.LENGTH_SHORT).show()
            requestPhonePermission()
            return
        }

        try {
            startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:08045163666")
            })
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.main_call_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeWarnAboutCarrier() {
        val telephonyManager = getSystemService(TelephonyManager::class.java) ?: return
        val carrierName = telephonyManager.networkOperatorName?.trim().orEmpty()
        if (carrierName.contains("jio", ignoreCase = true)) {
            Toast.makeText(this, getString(R.string.main_jio_warning), Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, getString(R.string.main_accessibility_hint), Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleLanguage() {
        val currentLanguage = LocaleHelper.getStoredLanguage(this)
            ?: resources.configuration.locales[0]?.language
            ?: "en"
        LocaleHelper.setLocale(this, if (currentLanguage == "hi") "en" else "hi")
        recreate()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return AppRuntimeChecks.isUssdAccessibilityServiceEnabled(contentResolver)
    }

    private fun checkPermissions() {
        if (!checkCameraPermission()) requestCameraPermission()
        if (!checkPhonePermission()) requestPhonePermission()
    }

    private fun checkCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun checkPhonePermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() =
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )

    private fun requestPhonePermission() =
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CALL_PHONE),
            PHONE_PERMISSION_CODE
        )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.main_camera_ready, Toast.LENGTH_SHORT).show()
                }
            }

            PHONE_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.main_phone_permission_granted, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
