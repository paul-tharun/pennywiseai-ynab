package com.pennywiseai.ynab.data.local

import androidx.room.TypeConverter
import java.math.BigDecimal

/**
 * Room converters. Amounts persist as their exact plain-string form (never a
 * lossy double); status persists as its enum name. Applied DB-wide via
 * @TypeConverters on PennyWiseDatabase, and to query parameters too.
 */
class Converters {

    @TypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)

    @TypeConverter
    fun statusToString(value: MessageStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}
