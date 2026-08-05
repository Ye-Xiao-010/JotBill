package com.jotbill.app.data

import androidx.room.TypeConverter

/**
 * Converters for values that are represented as structured values in Kotlin
 * but stored as SQLite-compatible text.
 */
class DatabaseConverters {
    @TypeConverter
    fun fromStringList(value: List<String>?): String? = value?.joinToString(",")

    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList()
}
