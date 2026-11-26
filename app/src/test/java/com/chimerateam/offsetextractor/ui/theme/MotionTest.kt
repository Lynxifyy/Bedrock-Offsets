package com.chimerateam.offsetextractor.ui.theme

import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the motion tokens and the reduce-motion collapse. */
class MotionTest {

    @Test
    fun theDurationScaleHasThreeSteps() {
        assertEquals(120, MotionDuration.SHORT)
        assertEquals(240, MotionDuration.MEDIUM)
        assertEquals(400, MotionDuration.LONG)
    }

    @Test
    fun aTweenKeepsItsDurationWhenMotionIsAllowed() {
        val spec = motionTween<Float>(MotionDuration.MEDIUM)
        assertEquals(MotionDuration.MEDIUM, (spec as TweenSpec<Float>).durationMillis)
    }

    @Test
    fun aTweenBecomesASnapWhenMotionIsReduced() {
        val spec = motionTween<Float>(MotionDuration.LONG, reduceMotion = true)
        assertEquals(0, (spec as TweenSpec<Float>).durationMillis)
    }

    @Test
    fun aSpringBecomesASnapWhenMotionIsReduced() {
        val spec = motionSpring(MotionSpring.Confirmation, reduceMotion = true)
        assertEquals(0, (spec as TweenSpec<Float>).durationMillis)
    }

    @Test
    fun theSpacingScaleFollowsTheFourDpGrid() {
        listOf(Spacing.Xs, Spacing.Sm, Spacing.Md, Spacing.Lg, Spacing.Xl, Spacing.Xxl)
            .forEach { assertEquals(0f, it.value % 4f, 0.001f) }
    }
}
