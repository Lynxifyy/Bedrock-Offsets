package com.chimerateam.offsetextractor.data.native

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the verification model, including its wire names and confidence. */
class VerificationStatusTest {

    private val gson = Gson()

    @Test
    fun theWireNamesMatchTheEngineSnakeCase() {
        val summary = VerificationSummary(
            verified = 3,
            conflicts = 1,
            unverified = 2,
            symbolsAvailable = true
        )

        val json = gson.toJson(summary)

        assertTrue("expected a snake_case key: $json", json.contains("\"symbols_available\""))
        assertEquals(summary, gson.fromJson(json, VerificationSummary::class.java))
    }

    @Test
    fun aStatusRoundTripsThroughJson() {
        assertEquals(
            VerificationStatus.Verified,
            gson.fromJson("\"verified\"", VerificationStatus::class.java)
        )
        assertEquals(
            VerificationStatus.Conflict,
            gson.fromJson("\"conflict\"", VerificationStatus::class.java)
        )
        assertEquals(
            VerificationStatus.Unverified,
            gson.fromJson("\"unverified\"", VerificationStatus::class.java)
        )
    }

    @Test
    fun anUnknownWireValueIsUnverified() {
        assertEquals(VerificationStatus.Unverified, VerificationStatus.fromWire("something_new"))
        assertEquals(VerificationStatus.Unverified, VerificationStatus.fromWire(null))
    }

    @Test
    fun onlyAVerifiedStatusIsTrusted() {
        assertTrue(VerificationStatus.Verified.isTrusted)
        assertFalse(VerificationStatus.Conflict.isTrusted)
        assertFalse(VerificationStatus.Unverified.isTrusted)
        assertTrue(VerificationStatus.Conflict.isConflict)
    }

    @Test
    fun confidenceIsTheVerifiedFraction() {
        val summary = VerificationSummary(verified = 3, conflicts = 1, unverified = 0)

        assertEquals(4, summary.total)
        assertEquals(75, summary.confidencePercent)
    }

    @Test
    fun anEmptySummaryHasNoConfidence() {
        assertEquals(0, VerificationSummary.NONE.total)
        assertEquals(0, VerificationSummary.NONE.confidencePercent)
        assertEquals(0.0, VerificationSummary.NONE.confidence, 0.0)
    }

    @Test
    fun aStrippedLibraryReportsNoConfidenceEvenWithUnverifiedHits() {
        val summary = VerificationSummary(verified = 0, conflicts = 0, unverified = 5)

        assertEquals(0, summary.confidencePercent)
        assertFalse(summary.symbolsAvailable)
    }
}
