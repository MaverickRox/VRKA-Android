package com.mvrk.vrka

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class NavPillMotionTest {
    // Never runs the frame loop: the tests step the physics themselves.
    private val idleScope = CoroutineScope(
        object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        },
    )

    private fun motion(index: Int = 0) = NavPillMotion(idleScope, index).apply { stepDp = 80f }

    private var clock = 0.0

    /** Steps at 60 fps until settled, returning the largest height lift seen. */
    private fun NavPillMotion.runToRest(limitSeconds: Double = 5.0): Float {
        var peak = 0f
        val end = clock + limitSeconds
        while (clock < end) {
            clock += FRAME
            val active = tick(FRAME, clock)
            peak = maxOf(peak, liftY)
            if (!active) return peak
        }
        throw AssertionError("pill did not settle within $limitSeconds s")
    }

    private fun NavPillMotion.runFor(seconds: Double) {
        val end = clock + seconds
        while (clock < end) {
            clock += FRAME
            tick(FRAME, clock)
        }
    }

    private fun NavPillMotion.assertAtRestOn(tab: Float) {
        assertEquals(tab, position, 0.001f)
        assertEquals(0f, liftX, 0.001f)
        assertEquals(0f, liftY, 0.001f)
        assertEquals(0f, rim, 0.001f)
        assertEquals(0f, deviation, 0.001f)
    }

    @Test
    fun tapLiftsThePillCarriesItOverAndSetsItDown() {
        val pill = motion()
        pill.animateTo(2)
        val peakLift = pill.runToRest()
        assertTrue("the pill must lift on the way, peaked at $peakLift", peakLift > 0.9f)
        pill.assertAtRestOn(2f)
    }

    @Test
    fun theRimIsOnlyDrawnInFlight() {
        val pill = motion()
        pill.assertAtRestOn(0f)
        pill.animateTo(1)
        pill.runFor(0.1)
        assertTrue("rim mid-flight was ${pill.rim}", pill.rim > 0f)
        pill.runToRest()
        assertEquals(0f, pill.rim, 0.001f)
    }

    @Test
    fun rechoosingTheCurrentTabDoesNothing() {
        val pill = motion(1)
        pill.animateTo(1)
        pill.runFor(0.1)
        pill.assertAtRestOn(1f)
    }

    @Test
    fun deformationStaysWithinItsClamp() {
        val pill = motion()
        pill.animateTo(3)
        repeat(120) {
            clock += FRAME
            pill.tick(FRAME, clock)
            assertTrue(kotlin.math.abs(pill.deviation) <= 0.12001f)
        }
    }

    @Test
    fun aPageSwipeCarriesThePillOneToOneWithoutLifting() {
        val pill = motion()
        for (step in 1..20) {
            val page = step / 20f
            pill.followPager(page, pagerScrolling = true)
            assertEquals(page, pill.position, 0.0001f)
            clock += FRAME
            pill.tick(FRAME, clock)
            assertEquals(0f, pill.liftY, 0.0001f)
            assertEquals(0f, pill.rim, 0.0001f)
        }
        pill.followPager(1f, pagerScrolling = false)
        pill.runToRest()
        pill.assertAtRestOn(1f)
    }

    @Test
    fun aPageSwipeDeformsThePillFromItsAcceleration() {
        val pill = motion()
        var peak = 0f
        for (step in 1..20) {
            pill.followPager(step * step / 400f, pagerScrolling = true)
            clock += FRAME
            pill.tick(FRAME, clock)
            peak = maxOf(peak, kotlin.math.abs(pill.deviation))
        }
        assertTrue("squash while swiping peaked at $peak", peak > 0f)
    }

    @Test
    fun theFrameLoopKeepsRunningBetweenPagerSamples() {
        val pill = motion()
        pill.followPager(0.1f, pagerScrolling = true)
        clock += FRAME
        assertTrue(pill.tick(FRAME, clock))
        clock += FRAME
        assertTrue(pill.tick(FRAME, clock))
    }

    @Test
    fun pagerSamplesCannotDragThePillBackDuringATap() {
        val pill = motion()
        pill.animateTo(3)
        pill.runFor(0.1)
        val before = pill.position
        pill.followPager(0.05f, pagerScrolling = true)
        assertEquals(before, pill.position, 0.0001f)
    }

    @Test
    fun aPillThatLandsBeforeThePagerWaitsForIt() {
        val pill = motion()
        pill.animateTo(1)
        pill.runToRest()
        pill.followPager(0.6f, pagerScrolling = true)
        pill.assertAtRestOn(1f)
        pill.followPager(1f, pagerScrolling = false)
        pill.assertAtRestOn(1f)
    }

    @Test
    fun aPagerThatStopsElsewhereTakesThePillWithIt() {
        val pill = motion()
        pill.animateTo(3)
        pill.runFor(0.05)
        pill.followPager(1f, pagerScrolling = false)
        pill.runToRest()
        pill.assertAtRestOn(1f)
    }

    @Test
    fun grabbingThePageHandsThePillBackToThePager() {
        val pill = motion()
        pill.animateTo(3)
        pill.runFor(0.05)
        pill.pagerGrabbed()
        pill.followPager(0.4f, pagerScrolling = true)
        assertEquals(0.4f, pill.position, 0.0001f)
        pill.followPager(0f, pagerScrolling = false)
        pill.runToRest()
        pill.assertAtRestOn(0f)
    }

    @Test
    fun aDragOnTheBarHoldsTheLiftAndIgnoresThePager() {
        val pill = motion()
        pill.startDrag()
        pill.dragTo(2f)
        pill.runFor(0.5)
        assertEquals(2f, pill.position, 0.01f)
        assertTrue("held lift was ${pill.liftY}", pill.liftY > 0.9f)
        val held = pill.position
        pill.followPager(0f, pagerScrolling = true)
        assertEquals(held, pill.position, 0.0001f)
        pill.release(2)
        pill.runToRest()
        pill.assertAtRestOn(2f)
    }

    @Test
    fun aDragLetGoOnItsOwnTabDoesNotWaitForThePager() {
        val pill = motion(1)
        pill.startDrag()
        pill.dragTo(1.2f)
        pill.runFor(0.1)
        pill.release(1)
        pill.runToRest()
        pill.assertAtRestOn(1f)
        pill.followPager(1.5f, pagerScrolling = true)
        assertEquals(1.5f, pill.position, 0.0001f)
    }

    @Test
    fun reducedMotionJumpsWithNoLift() {
        val pill = motion().apply { reduceMotion = true }
        pill.animateTo(2)
        pill.assertAtRestOn(2f)
        pill.followPager(1f, pagerScrolling = true)
        pill.assertAtRestOn(2f)
        pill.followPager(2f, pagerScrolling = false)
        pill.followPager(2.5f, pagerScrolling = true)
        assertEquals(2.5f, pill.position, 0.0001f)
        assertEquals(0f, pill.liftY, 0.0001f)
    }

    @Test
    fun aLongFrameCannotBlowTheSpringsUp() {
        val pill = motion()
        pill.animateTo(3)
        clock += 2.0
        pill.tick(2.0, clock)
        assertFalse(pill.position.isNaN())
        assertTrue("position after a 2s frame was ${pill.position}", pill.position in 0f..3.5f)
    }

    private companion object {
        const val FRAME = 1 / 60.0
    }
}
