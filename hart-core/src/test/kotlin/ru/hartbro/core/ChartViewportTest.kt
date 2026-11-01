package ru.hartbro.core

import org.junit.Assert.*
import org.junit.Test

class ChartViewportTest {
    @Test fun liveWindowKeepsSixtySecondsEvenWithShortHistory() {
        val v=ChartViewport()
        assertEquals(-50_000L..10_000L,v.bounds(0,10_000))
        assertEquals(140_000L..200_000L,v.bounds(0,200_000))
        assertEquals(60_000L,v.spanMillis)
    }
    @Test fun panDisablesFollowAndPreservesWidthThenReturningToEdgeResumes() {
        val back=ChartViewport().transform(0,300_000,1f,0.5f)
        assertFalse(back.following);assertEquals(210_000L..270_000L,back.bounds(0,300_000))
        val forward=back.transform(0,300_000,1f,-0.5f)
        assertTrue(forward.following);assertEquals(250_000L..310_000L,forward.bounds(0,310_000))
    }
    @Test fun historicalZoomKeepsCentreAndLiveZoomKeepsRightEdge() {
        val history=ChartViewport(60_000,200_000,false).transform(0,400_000,2f,0f)
        assertEquals(155_000L..185_000L,history.bounds(0,400_000));assertFalse(history.following)
        val live=ChartViewport().transform(0,400_000,2f,0f)
        assertEquals(370_000L..400_000L,live.bounds(0,400_000));assertTrue(live.following)
    }
    @Test fun nearEdgeToleranceIsExplicitAndScaleSurvivesSeekAndFollow() {
        val v=ChartViewport(30_000)
        val back=v.seek(0,300_000,0.5f,2000)
        assertFalse(back.following);assertEquals(120_000L..150_000L,back.bounds(0,300_000))
        assertTrue(back.seek(0,300_000,0.995f,2000).following)
        assertEquals(30_000L,back.follow().spanMillis)
        assertFalse(back.seek(0,300_000,0.99f,2000).following)
    }
    @Test fun historyRetentionAndArchiveWholeSessionAreIndependentOfViewport() {
        val point=ChartPoint(500,sample02(500,12f).values[0],0)
        val points=listOf(point,point.copy(time=200_000))
        assertEquals(1,ChartViewport().select(points,emptySet(),0,200_000).size)
        assertEquals(2,points.size)
        assertEquals(0L..400_000L,ChartViewport().all(false).bounds(0,400_000))
        assertEquals(0,ChartViewport().select(points,setOf("PV"),0,200_000).size)
    }
    @Test fun earlyZoomPreservesConfiguredWidth() {
        assertEquals(30_000L,ChartViewport().transform(0,2000,2f,0f).spanMillis)
    }
}
