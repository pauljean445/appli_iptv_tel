package io.github.pauljean445.iptvtel.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Données JSON manipulées comme sur PC (dict / list Python) : MutableMap<String, Any?>, MutableList<Any?>,
 * String, Long, Double, Boolean, null. Les fichiers (settings.json, library.json…) ont le même format.
 */
typealias Obj = MutableMap<String, Any?>

val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

fun JsonElement.toAny(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> LinkedHashMap<String, Any?>().also { m -> forEach { (k, v) -> m[k] = v.toAny() } }
    is JsonArray -> mapTo(ArrayList()) { it.toAny() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        else -> doubleOrNull
    }
}

fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

fun parseObj(text: String): Obj = try {
    @Suppress("UNCHECKED_CAST")
    (JSON.parseToJsonElement(text).toAny() as? Obj) ?: LinkedHashMap()
} catch (e: Exception) {
    LinkedHashMap()
}

fun Any?.dumps(): String = toJson().toString()

/** Lit un fichier JSON ; dict vide s'il est absent ou illisible. */
fun readObj(file: File): Obj = if (file.exists()) parseObj(file.readText()) else LinkedHashMap()

/** Écrit un fichier JSON via un fichier temporaire (jamais de fichier à moitié écrit). */
fun writeObj(file: File, data: Any?) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(data.dumps())
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}

@Suppress("UNCHECKED_CAST")
fun Obj.obj(key: String): Obj = (this[key] as? Obj) ?: LinkedHashMap<String, Any?>().also { this[key] = it }

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Obj? = this as? Obj

fun Any?.num(): Double = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull() ?: 0.0
    else -> 0.0
}

fun Any?.str(): String = when (this) {
    null -> ""
    is String -> this
    is Double -> if (this == Math.floor(this) && !isInfinite()) toLong().toString() else toString()
    else -> toString()
}

fun Any?.int(default: Int = 0): Int = when (this) {
    is Number -> toInt()
    is String -> toIntOrNull() ?: toDoubleOrNull()?.toInt() ?: default
    else -> default
}

/** Heure actuelle en secondes (avec décimales), comme time.time() en Python. */
fun now(): Double = System.currentTimeMillis() / 1000.0
