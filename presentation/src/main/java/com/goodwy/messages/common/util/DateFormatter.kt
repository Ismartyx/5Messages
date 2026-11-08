/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.goodwy.messages.common.util

import android.content.Context
import android.text.format.DateFormat
import com.goodwy.messages.common.util.extensions.isSameDay
import com.goodwy.messages.common.util.extensions.isSameWeek
import com.goodwy.messages.common.util.extensions.isSameYear
import com.goodwy.messages.util.Preferences
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DateFormatter @Inject constructor(
    val context: Context,
    val prefs: Preferences
) {

    /**
     * Formats the [pattern] correctly for the current locale, and replaces 12 hour format with
     * 24 hour format if necessary. When Shamsi calendar is enabled, uses Locale.US so digits remain English.
     */
    private fun getFormatter(pattern: String): SimpleDateFormat {
        val locale = if (prefs.shamsiCalendar.get()) Locale.US else Locale.getDefault()
        var formattedPattern = DateFormat.getBestDateTimePattern(locale, pattern)

        if (DateFormat.is24HourFormat(context)) {
            formattedPattern = formattedPattern
                    .replace("h", "HH")
                    .replace("K", "HH")
                    .replace(" a".toRegex(), "")
        }

        return SimpleDateFormat(formattedPattern, locale)
    }

    fun getDetailedTimestamp(date: Long): String {
        if (!prefs.shamsiCalendar.get()) {
            return getFormatter("M/d/y, h:mm:ss a").format(date)
        }
        val j = JalaliDateHelper.fromMillis(date)
        val time = getFormatter("h:mm:ss a").format(date)
        return String.format(Locale.US, "%04d/%02d/%02d, %s", j.year, j.month, j.day, time)
    }

    fun getTimestamp(date: Long): String {
        return getFormatter("h:mm a").format(date)
    }

    fun getMessageTimestamp(date: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = date

        if (!prefs.shamsiCalendar.get()) {
            return when {
                now.isSameDay(then) -> getFormatter("h:mm a")
                now.isSameWeek(then) -> getFormatter("E h:mm a")
                now.isSameYear(then) -> getFormatter("MMM d, h:mm a")
                else -> getFormatter("MMM d yyyy, h:mm a")
            }.format(date)
        }

        val timeStr = getFormatter("h:mm a").format(date)
        val jThen = JalaliDateHelper.fromMillis(date)
        val jNow = JalaliDateHelper.fromMillis(now.timeInMillis)

        return when {
            now.isSameDay(then) -> timeStr
            now.isSameWeek(then) -> "${JalaliDateHelper.getWeekdayName(date)} $timeStr"
            jNow.year == jThen.year -> String.format(Locale.US, "%d %s، %s", jThen.day, JalaliDateHelper.getMonthName(jThen.month), timeStr)
            else -> String.format(Locale.US, "%d %s %04d، %s", jThen.day, JalaliDateHelper.getMonthName(jThen.month), jThen.year, timeStr)
        }
    }

    fun getConversationTimestamp(date: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = date

        if (!prefs.shamsiCalendar.get()) {
            return when {
                now.isSameDay(then) -> getFormatter("h:mm a")
                now.isSameWeek(then) -> getFormatter("E")
                now.isSameYear(then) -> getFormatter("MMM d")
                else -> getFormatter("MM/d/yy")
            }.format(date)
        }

        val jThen = JalaliDateHelper.fromMillis(date)
        val jNow = JalaliDateHelper.fromMillis(now.timeInMillis)

        return when {
            now.isSameDay(then) -> getFormatter("h:mm a").format(date)
            now.isSameWeek(then) -> JalaliDateHelper.getWeekdayName(date)
            jNow.year == jThen.year -> String.format(Locale.US, "%d %s", jThen.day, JalaliDateHelper.getMonthName(jThen.month))
            else -> String.format(Locale.US, "%04d/%02d/%02d", jThen.year, jThen.month, jThen.day)
        }
    }

    fun getScheduledTimestamp(date: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = date

        if (!prefs.shamsiCalendar.get()) {
            return when {
                now.isSameDay(then) -> getFormatter("h:mm a")
                now.isSameYear(then) -> getFormatter("MMM d h:mm a")
                else -> getFormatter("MMM d yyyy h:mm a")
            }.format(date)
        }

        val timeStr = getFormatter("h:mm a").format(date)
        val jThen = JalaliDateHelper.fromMillis(date)
        val jNow = JalaliDateHelper.fromMillis(now.timeInMillis)

        return when {
            now.isSameDay(then) -> timeStr
            jNow.year == jThen.year -> String.format(Locale.US, "%d %s %s", jThen.day, JalaliDateHelper.getMonthName(jThen.month), timeStr)
            else -> String.format(Locale.US, "%d %s %04d %s", jThen.day, JalaliDateHelper.getMonthName(jThen.month), jThen.year, timeStr)
        }
    }

}
