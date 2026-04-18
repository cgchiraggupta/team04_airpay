package com.airpay.upi.ussd

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Handles dialing USSD codes with multiple strategies to bypass OEM restrictions.
 */
object USSDDialer {

    private const val TAG = "USSDDialer"

    /** Globally tracks if the device/carrier actively rejects programmatic USSD API */
    @Volatile
    var isSendUssdBroken = false

    interface Listener {
        fun onSendUssdResponse(response: String)
        fun onSendUssdFailed(failureCode: Int)
    }

    /**
     * Dial a USSD code. Tries sendUssdRequest first (bypasses dialer validation),
     * then falls back to ACTION_CALL if dispatch fails.
     */
    fun dial(
        context: Context,
        ussdCode: String,
        listener: Listener? = null
    ): Boolean {
        Log.d(TAG, "dial() len=${ussdCode.length}")

        if (!hasCallPermission(context)) {
            Log.w(TAG, "Missing CALL_PHONE permission")
            return false
        }

        if (!isSendUssdBroken) {
            val sent = dialSendUssd(context, ussdCode, listener)
            if (sent) {
                return true
            }
        }
        
        return dialActionCall(context, ussdCode)
    }

    fun dialActionCall(context: Context, ussdCode: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:" + Uri.encode(ussdCode))
            }
            context.startActivity(intent)
            Log.d(TAG, "ACTION_CALL launched")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_CALL failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun dialSendUssd(
        context: Context,
        ussdCode: String,
        listener: Listener?
    ): Boolean {
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
            tm.sendUssdRequest(
                ussdCode,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager,
                        request: String,
                        response: CharSequence
                    ) {
                        listener?.onSendUssdResponse(response.toString())
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager,
                        request: String,
                        failureCode: Int
                    ) {
                        Log.e(TAG, "sendUssd failed: code=$failureCode")
                        // code -1 is USSD_RETURN_FAILURE. If we get this, the API is 
                        // likely unsupported by this carrier/SIM combo.
                        if (failureCode == -1 || failureCode == TelephonyManager.USSD_RETURN_FAILURE) {
                            isSendUssdBroken = true
                        }
                        listener?.onSendUssdFailed(failureCode)
                    }
                },
                Handler(Looper.getMainLooper())
            )
            Log.d(TAG, "sendUssdRequest dispatched")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendUssdRequest exception", e)
            isSendUssdBroken = true
            false
        }
    }

    private fun hasCallPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
    }
}
