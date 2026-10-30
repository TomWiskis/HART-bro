package ru.hartbro.core

import org.junit.Assert.*
import org.junit.Test

class ChartViewportTest {
    @Test fun liveWindowFollowsNewDataAndClampsAtSessionStart() {
        val v=ChartViewport();assertEquals(0L..10_000L,v.bounds(0,10_000));assertEquals(80_000L..200_000L,v.bounds(0,200_000))
    }
    @Test fun zoomPreservesFollowingAndPanStopsIt() {
        val zoomed=ChartViewport().transform(0,200_000,2f,0f)
        assertTrue(zoomed.following);assertEquals(240_000L..300_000L,zoomed.bounds(0,300_000))
        val panned=zoomed.transform(0,300_000,1f,0.5f);assertFalse(panned.following);assertEquals(210_000L..270_000L,panned.bounds(0,300_000))
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

    @Test fun returningToFollowPreservesChosenScale() {
        val v=ChartViewport(30_000,120_000,false).follow()
        assertEquals(30_000L,v.spanMillis);assertEquals(270_000L..300_000L,v.bounds(0,300_000))
    }
    @Test fun wholeSessionKeepsGrowingAndSliderPreservesScale() {
        val all=ChartViewport().all(true);assertEquals(0L..400_000L,all.bounds(0,400_000))
        val seek=ChartViewport(10_000).seek(0,100_000,0.5f)
        assertEquals(40_000L..50_000L,seek.bounds(0,100_000));assertEquals(10_000L,seek.spanMillis)
    }
    @Test fun earlyZoomPreservesConfiguredWindowInsteadOfTruncatingToShortHistory() {
        assertEquals(60_000L,ChartViewport().transform(0,2000,2f,0f).spanMillis)
    }
}
