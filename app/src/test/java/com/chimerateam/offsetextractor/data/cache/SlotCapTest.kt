package com.chimerateam.offsetextractor.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCapTest {

    @Test
    fun everyCapTheScreenOffersSurvivesClamping() {
        // A chip the user can press must reach the engine unchanged: 16384 used
        // to be clamped to 4096, so the choice silently did nothing.
        assertEquals(1024, clampSlotsPerClass(1024))
        assertEquals(4096, clampSlotsPerClass(4096))
        assertEquals(16_384, clampSlotsPerClass(16_384))
    }

    @Test
    fun theRangeStillBoundsAbsurdValues() {
        assertEquals(MIN_SLOTS_PER_CLASS, clampSlotsPerClass(0))
        assertEquals(MIN_SLOTS_PER_CLASS, clampSlotsPerClass(-5))
        assertEquals(MAX_SLOTS_PER_CLASS, clampSlotsPerClass(Int.MAX_VALUE))
    }

    @Test
    fun theEngineCeilingCoversTheLargestChoice() {
        assertTrue(
            "the engine's hard limit must accept every offered cap",
            MAX_SLOTS_PER_CLASS > 16_384
        )
    }
}
