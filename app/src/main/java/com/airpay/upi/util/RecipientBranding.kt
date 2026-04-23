package com.airpay.upi.util

object RecipientBranding {
    private const val AIRPAY_KEYWORD = "airpay"
    const val AIRPAY_DISPLAY_NAME = "Chirag Gupta"
    private val airpayAliases = setOf(
        "airpay",
        "airpay user",
        "airpayuser",
        "you",
        "self"
    )

    private fun normalize(value: String?): String {
        return value.orEmpty().trim().lowercase()
    }

    fun isAirpayRecipient(name: String?, upiId: String?): Boolean {
        return listOf(name, upiId).any { value ->
            val normalized = normalize(value)
            normalized.contains(AIRPAY_KEYWORD) || normalized in airpayAliases
        }
    }

    fun isAirpaySender(name: String?): Boolean {
        val normalized = normalize(name)
        return normalized.isBlank() || normalized in airpayAliases || normalized.contains(AIRPAY_KEYWORD)
    }

    fun resolveDisplayName(
        name: String?,
        upiId: String?,
        phone: String? = null,
        fallback: String? = null
    ): String {
        return when {
            isAirpayRecipient(name, upiId) -> AIRPAY_DISPLAY_NAME
            !name.isNullOrBlank() -> name
            !phone.isNullOrBlank() -> phone
            !upiId.isNullOrBlank() -> upiId
            !fallback.isNullOrBlank() -> fallback
            else -> ""
        }
    }
}
