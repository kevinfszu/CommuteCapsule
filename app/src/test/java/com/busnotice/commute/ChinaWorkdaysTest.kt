package com.busnotice.commute

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChinaWorkdaysTest {
    private val sample = """
        {"code":0,"holiday":{
          "02-14":{"holiday":false,"name":"春节前调休","date":"2026-02-14"},
          "02-16":{"holiday":true,"name":"春节","date":"2026-02-16"},
          "10-01":{"holiday":true,"name":"国庆节","date":"2026-10-01"},
          "10-10":{"holiday":false,"name":"国庆后调休","date":"2026-10-10"}
        }}
    """.trimIndent()

    @Test
    fun `parse reads off days and makeup workdays`() {
        val year = ChinaWorkdays.parse(2026, sample)!!
        assertTrue(year.offDays.contains(LocalDate.parse("2026-02-16")))
        assertTrue(year.workDays.contains(LocalDate.parse("2026-02-14")))
        assertFalse(year.isWorkday(LocalDate.parse("2026-02-16")))
        assertTrue(year.isWorkday(LocalDate.parse("2026-02-14")))
        assertTrue(year.isWorkday(LocalDate.parse("2026-10-08")))   // 普通周四
        assertTrue(year.isWorkday(LocalDate.parse("2026-10-10")))   // 调休上班的周六
    }

    @Test
    fun `invalid payload returns null`() {
        assertNull(ChinaWorkdays.parse(2026, "not json"))
        assertNull(ChinaWorkdays.parse(2026, """{"code":1,"holiday":{}}"""))
        assertNull(ChinaWorkdays.parse(2026, """{"code":0}"""))
    }

    @Test
    fun `default workday is monday to friday`() {
        assertTrue(ChinaWorkdays.defaultIsWorkday(LocalDate.parse("2026-10-08")))
        assertFalse(ChinaWorkdays.defaultIsWorkday(LocalDate.parse("2026-10-10")))
        assertFalse(ChinaWorkdays.defaultIsWorkday(LocalDate.parse("2026-10-11")))
    }

    @Test
    fun `trip uses statutory calendar only in china workday mode`() {
        val year = ChinaWorkdays.parse(2026, sample)!!
        val makeupSaturday = LocalDate.parse("2026-02-14")
        val holiday = LocalDate.parse("2026-02-16")

        val statutory = Trip(chinaWorkday = true)
        assertTrue(statutory.runsOn(makeupSaturday, year::isWorkday))
        assertFalse(statutory.runsOn(holiday, year::isWorkday))

        val custom = Trip(dayMask = 0b0011111)
        assertTrue(custom.runsOn(holiday, year::isWorkday))
        assertFalse(custom.runsOn(makeupSaturday, year::isWorkday))
    }

    @Test
    fun `json keeps china workday flag`() {
        val trip = Trip(name = "上班", chinaWorkday = true)
        assertTrue(Trip.fromJson(trip.toJson()).chinaWorkday)
        assertFalse(Trip.fromJson(Trip(name = "下班").toJson()).chinaWorkday)
    }
}
