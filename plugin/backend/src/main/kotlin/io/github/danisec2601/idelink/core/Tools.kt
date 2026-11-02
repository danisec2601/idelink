package io.github.danisec2601.idelink.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** A tool the plugin serves, with the schema it advertises (via list_tools) and enforces before the handler runs. */
internal class ToolDef(
    val name: String, val description: String, val schema: Schema.Obj, val handler: (JsonObject) -> JsonElement,
) {
    fun spec() = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", schema.json())
    }
}

/** The subset of JSON Schema the tools use. Validation is strict: unknown keys and wrong types are errors. */
internal sealed class Schema(val description: String?) {
    abstract fun json(): JsonObject
    abstract fun check(v: JsonElement, at: String)

    protected fun base(type: String) = JsonObject().apply {
        addProperty("type", type); description?.let { addProperty("description", it) }
    }

    class Str(description: String? = null, val maxLength: Int = 4096) : Schema(description) {
        override fun json() = base("string").apply { addProperty("maxLength", maxLength) }
        override fun check(v: JsonElement, at: String) {
            if (!(v.isJsonPrimitive && v.asJsonPrimitive.isString)) throw BadArgs("$at must be a string")
            if (v.asString.length > maxLength) throw BadArgs("$at is longer than $maxLength characters")
        }
    }

    class Integer(description: String? = null, val min: Long = 1, val max: Long = 10_000_000) : Schema(description) {
        override fun json() = base("integer").apply { addProperty("minimum", min); addProperty("maximum", max) }
        override fun check(v: JsonElement, at: String) {
            val n = (v as? JsonPrimitive)?.takeIf { it.isNumber }?.asBigDecimal
            if (n == null || n.stripTrailingZeros().scale() > 0) throw BadArgs("$at must be an integer")
            if (n < min.toBigDecimal() || n > max.toBigDecimal()) throw BadArgs("$at must be in $min..$max")
        }
    }

    class Bool(description: String? = null) : Schema(description) {
        override fun json() = base("boolean")
        override fun check(v: JsonElement, at: String) {
            if (!(v.isJsonPrimitive && v.asJsonPrimitive.isBoolean)) throw BadArgs("$at must be a boolean")
        }
    }

    class Arr(val items: Schema, description: String? = null, val maxItems: Int = 200) : Schema(description) {
        override fun json() = base("array").apply { add("items", items.json()); addProperty("maxItems", maxItems) }
        override fun check(v: JsonElement, at: String) {
            if (!v.isJsonArray) throw BadArgs("$at must be an array")
            val a = v.asJsonArray
            if (a.size() == 0) throw BadArgs("$at must not be empty")
            if (a.size() > maxItems) throw BadArgs("$at has more than $maxItems items")
            a.forEachIndexed { i, e -> items.check(e, "$at[$i]") }
        }
    }

    class Obj(val props: Map<String, Schema>, val required: Set<String> = emptySet(), description: String? = null) : Schema(description) {
        override fun json() = base("object").apply {
            add("properties", JsonObject().apply { props.forEach { (k, s) -> add(k, s.json()) } })
            if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(it) } })
            addProperty("additionalProperties", false)
        }
        override fun check(v: JsonElement, at: String) {
            if (!v.isJsonObject) throw BadArgs("$at must be an object")
            val o = v.asJsonObject
            for (k in o.keySet()) if (k !in props) throw BadArgs("$at has unknown key '${k.take(64)}'")
            for (k in required) if (!o.has(k) || o.get(k).isJsonNull) throw BadArgs("$at.$k is required")
            for ((k, e) in o.entrySet()) if (!e.isJsonNull) props.getValue(k).check(e, "$at.$k")
        }
    }
}

internal class BadArgs(msg: String, cause: Throwable? = null) : Exception(msg, cause)

internal fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString
internal fun JsonObject.int(k: String): Int? = get(k)?.takeIf { !it.isJsonNull }?.asInt
internal fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { !it.isJsonNull }?.asBoolean
