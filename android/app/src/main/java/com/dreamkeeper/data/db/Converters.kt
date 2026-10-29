package com.dreamkeeper.data.db

import androidx.room.TypeConverter
import java.time.LocalDate

/**
 * `LocalDate` ⇄ `yyyy-MM-dd` 字符串。
 *
 * 选 ISO 字符串而不是 epochDay：SQL 里直接按字典序比较就等价于按日期比较，
 * 所以 DAO 的 `BETWEEN :fromIso AND :toIso` 是**闭区间**，与 `DreamCalendar.monthRange`
 * 返回的「月首、月末」两个端点语义天然一致，不需要调用方自己 +1。
 */
class Converters {
    @TypeConverter fun fromDate(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun toDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}
