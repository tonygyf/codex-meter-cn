package io.codexmeter.codex_meter_cn

import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class WaterMotionTest {
    @Test fun fiftyFpsCycleFitsSmallDeviceBitmapBudget() {
        assertEquals(50,1000/WaterMotion.INTERVAL_MILLIS)
        assertEquals(3000,WaterMotion.FRAMES*WaterMotion.INTERVAL_MILLIS)
        for(pixels in listOf(480*800,720*1280,1280*2772)) {
            val width=WaterMotion.frameWidth(pixels)
            assertTrue(width*(width*3/2)*4*WaterMotion.FRAMES <= pixels*2)
        }
    }
    @Test fun emptyAndFullRemainExactWhilePartialWaterMoves() {
        for(i in 0..64) {
            assertEquals(1.0,WaterMotion.surface(0.0,i/64.0,i.toDouble()),1e-9)
            assertEquals(0.0,WaterMotion.surface(100.0,i/64.0,i.toDouble()),1e-9)
        }
        assertNotEquals(WaterMotion.surface(50.0,.2,0.0),WaterMotion.surface(50.0,.2,1.0),1e-4)
    }
    @Test fun wavesStayInsideContainerAndLoopWithoutJumping() {
        for(remaining in listOf(.1,1.0,37.0,99.0,99.9))for(i in 0..64) {
            val y=WaterMotion.surface(remaining,i/64.0,i.toDouble())
            assertTrue(y in 0.0..1.0)
            assertEquals(WaterMotion.surface(remaining,i/64.0,0.0),WaterMotion.surface(remaining,i/64.0,2*PI),1e-9)
        }
    }
}
