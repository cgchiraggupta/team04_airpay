package com.airpay.upi.ussd

data class UPIData(
    val upiId: String,
    val name: String = "",
    val amount: String,
    val phoneNumber: String = "",
    val targetType: TargetType = TargetType.UPI
)

enum class TargetType {
    UPI,
    PHONE
}
