package com.fintracks.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileRow(
    val id: String,
    val name: String = "",
    val currency: String = "BDT",
    val theme: String = "dark",
    @SerialName("theme_color") val themeColor: String = "sky",
    @SerialName("is_privacy_mode") val isPrivacyMode: Boolean = false
)

@Serializable
data class TxRow(
    val id: String,
    val type: String,
    val amount: Double,
    val category: String = "Others",
    val note: String = "",
    @SerialName("tx_date") val txDate: String,
    @SerialName("payment_mode") val paymentMode: String = "Cash"
)

@Serializable
data class ShopRow(
    val id: String,
    val name: String,
    val phone: String = "",
    val note: String = ""
)

@Serializable
data class EntryRow(
    val id: String,
    @SerialName("shop_id") val shopId: String,
    @SerialName("item_name") val itemName: String,
    val quantity: Double = 1.0,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    val total: Double = 0.0,
    @SerialName("purchase_date") val purchaseDate: String,
    @SerialName("due_date") val dueDate: String? = null,
    val note: String = ""
)

@Serializable
data class PaymentRow(
    val id: String,
    @SerialName("shop_id") val shopId: String,
    val amount: Double,
    @SerialName("paid_on") val paidOn: String,
    val note: String = ""
)

/** A transaction detected from an SMS, waiting for the user to confirm it. */
@Serializable
data class SmsSuggestion(
    val id: String = java.util.UUID.randomUUID().toString(),
    val amount: Double,
    val type: String,
    val provider: String,
    val note: String,
    val raw: String
)
