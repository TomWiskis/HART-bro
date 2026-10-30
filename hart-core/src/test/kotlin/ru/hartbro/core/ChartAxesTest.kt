package ru.hartbro.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class ChartAxesTest {
    @Test fun realNumericTicksEncloseNegativePressureWithoutChangingValues() {
        val a=ChartAxes.numeric(listOf(-0.01330386f,-0.014f))
        assertTrue(a.low<=-0.014f);assertTrue(a.high>=-0.01330386f);assertTrue(a.ticks.size in 2..11)
        assertTrue(a.ticks.zipWithNext().all{(x,y)->y>x});assertTrue(a.label(a.ticks.first()).contains("-"))
    }
    @Test fun coincidentSeriesHaveFiniteAxisAndCompatibleUnitsCanShareIt() {
        val a=ChartAxes.numeric(List(4){1.25f});assertTrue(a.high>a.low)
        assertEquals(a,ChartAxes.numeric(listOf(1.25f)))
    }
    @Test fun timeTickStepAdaptsFromSecondsToLongHistory() {
        val short=ChartAxes.timeTicks(0,120000,4);val long=ChartAxes.timeTicks(0,86400000,4)
        assertEquals(30000L,short[1]-short[0]);assertTrue(long[1]-long[0]>=3600000)
        assertEquals("00:01:00",ChartAxes.timeLabel(60000,120000,ZoneOffset.UTC))
        assertTrue(ChartAxes.timeLabel(0,86400000,ZoneOffset.UTC).contains("01.01"))
    }
}
