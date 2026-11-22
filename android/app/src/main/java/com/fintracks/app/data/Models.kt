package com.fintracks.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Rows are kept as JSON objects so every module can use the same generic data layer. */
fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.num(key: String): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
fun JsonObject.id(): String = str("id")

/** A transaction detected from an SMS, waiting for the user to confirm it. */
@Serializable
data class SmsSuggestion(
    val id: String = java.util.UUID.randomUUID().toString(),
    val amount: Double,
    val type: String,
    val provider: String,
    val note: String,
    val raw: String,
    /** Date found in the SMS as yyyy-MM-dd; empty when the SMS has no readable date (then today is used). */
    val date: String = ""
)

/** Helper to build a JsonObject from pairs, skipping nulls. */
fun row(vararg pairs: Pair<String, Any?>): JsonObject {
    val map = LinkedHashMap<String, JsonElement>()
    for ((key, value) in pairs) {
        if (value == null) continue
        map[key] = when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JsonElement -> value
            else -> JsonPrimitive(value.toString())
        }
    }
    return JsonObject(map)
}
