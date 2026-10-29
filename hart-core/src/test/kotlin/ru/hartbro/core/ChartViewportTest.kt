package ru.hartbro.core

import org.junit.Assert.*
import org.junit.Test

class ChartViewportTest {
    @Test fun liveWindowFollowsNewDataAndClampsAtSessionStart() {
        val v=ChartViewport();assertEquals(0L..10_000L,v.bounds(0,10_000));assertEquals(80_000L..200_000L,v.bounds(0,200_000))
    }
    @Test fun zoomAndPanStopFollowingAndMoveOnSingleTimeAxis() {
        val zoomed=ChartViewport().transform(0,200_000,2f,0f)
        assertFalse(zoomed.following);assertEquals(140_000L..200_000L,zoomed.bounds(0,300_000))
        val panned=zoomed.transform(0,300_000,1f,0.5f);assertEquals(110_000L..170_000L,panned.bounds(0,300_000))
    }
    @Test fun navigationRemainsWithinHistoryAndWholeSessionIsAvailable() {
        assertEquals(10L..1000L,ChartViewport(Long.MAX_VALUE,following=false).bounds(10,1000))
        assertEquals(0L..0L,ChartViewport().transform(0,1000,1f,100f).bounds(0,1000))
    }
    @Test fun hidingSeriesAndChangingScaleNeverConvertsDigitalValuesOrUnits() {
        val a=ChartPoint(500,sample02(500,12f).values[0],0)
        val b=a.copy(measurement=a.measurement.copy(slot="SV",value=250f,unitCode=32))
        val selected=ChartViewport().select(listOf(a,b),setOf("PV"),0,1000)
        assertEquals(listOf(b),selected);assertEquals(250f,selected[0].measurement.value,0f);assertEquals(32,selected[0].measurement.unitCode)
    }
}
