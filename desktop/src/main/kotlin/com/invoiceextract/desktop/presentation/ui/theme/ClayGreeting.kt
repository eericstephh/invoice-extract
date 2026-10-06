package com.invoiceextract.desktop.presentation.ui.theme

import java.time.LocalDate
import java.time.LocalTime

private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

fun String.toPersianDigits(): String = map { c ->
    if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c
}.joinToString("")

fun Int.toPersianDigits(): String = toString().toPersianDigits()

/** Time-aware Persian greeting. */
fun greetingFor(time: LocalTime = LocalTime.now()): String = when (time.hour) {
    in 5..11 -> "صبح بخیر"
    in 12..16 -> "روز بخیر"
    in 17..19 -> "عصر بخیر"
    else -> "شب بخیر"
}

/**
 * Current Jalali date label, e.g. "دوشنبه ۶ مهر ۱۴۰۴".
 * Self-contained conversion (no extra dependency).
 */
fun jalaliDateLabel(today: LocalDate = LocalDate.now()): String {
    val (jy, jm, jd) = gregorianToJalali(today.year, today.monthValue, today.dayOfMonth)
    val monthName = JALALI_MONTHS[jm - 1]
    val weekDay = JALALI_WEEKDAYS[today.dayOfWeek.value % 7]
    return "$weekDay $jd $monthName $jy".toPersianDigits()
}

private val JALALI_MONTHS = listOf(
    "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
    "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
)

// Index 0 = Sunday per DayOfWeek.value % 7 mapping.
private val JALALI_WEEKDAYS = listOf(
    "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه",
)

private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
    val gDays = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    val jDays = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)
    var y = gy - 1600
    var m = gm - 1
    var d = gd - 1
    var gDayNo = 365 * y + (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400
    for (i in 0 until m) gDayNo += gDays[i]
    if (m > 1 && ((gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0))) gDayNo++
    gDayNo += d
    var jDayNo = gDayNo - 79
    var jNp = jDayNo / 12053
    jDayNo %= 12053
    y = 979 + 33 * jNp + 4 * (jDayNo / 1461)
    jDayNo %= 1461
    if (jDayNo >= 366) {
        y += (jDayNo - 1) / 365
        jDayNo = (jDayNo - 1) % 365
    }
    m = 0
    while (m < 11 && jDayNo >= jDays[m]) {
        jDayNo -= jDays[m]
        m++
    }
    return Triple(y, m + 1, jDayNo + 1)
}
