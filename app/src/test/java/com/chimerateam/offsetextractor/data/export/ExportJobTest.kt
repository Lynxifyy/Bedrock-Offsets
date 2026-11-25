package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Verifies the export state machine and the persisted job record. */
class ExportJobTest {

    private val summary = ScanResultSummary(
        displayName = "minecraft-26.60.apk",
        sha256 = "abc",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        apkVersion = "26.60.3"
    )

    @Before
    @After
    fun reset() {
        ExportJobRepository.clear()
    }

    @Test
    fun buildingThenCopyingThenDoneIsTheHappyPath() {
        ExportJobRepository.prepare(
            summary = summary,
            categories = emptyList(),
            includeCombined = false,
            jobId = "job-1",
            fileName = "BEDROCK_OFFSETS_26.60.3.zip",
            destinationUri = "content://test/out.zip"
        )
        assertEquals(ExportState.Running, ExportJobRepository.progress.value.state)
        assertEquals(ExportStage.Building, ExportJobRepository.progress.value.stage)

        ExportJobRepository.publish(
            ExportJobRepository.progress.value.copy(
                stage = ExportStage.Saving,
                bytesDone = 50,
                bytesTotal = 100,
                fraction = 0.9f
            )
        )
        assertEquals(ExportStage.Saving, ExportJobRepository.progress.value.stage)
        assertEquals(0.9f, ExportJobRepository.progress.value.fraction)

        ExportJobRepository.complete("/tmp/out.zip")
        assertEquals(ExportState.Done, ExportJobRepository.progress.value.state)
        assertEquals(1f, ExportJobRepository.progress.value.fraction)
    }

    @Test
    fun cancellingClearsTheRunningState() {
        ExportJobRepository.prepare(
            summary = summary,
            categories = emptyList(),
            includeCombined = false,
            jobId = "job-2",
            fileName = "BEDROCK_OFFSETS_26.60.3.zip",
            destinationUri = "content://test/out.zip"
        )

        ExportJobRepository.cancel()

        assertEquals(ExportState.Cancelled, ExportJobRepository.progress.value.state)
    }

    @Test
    fun aFailedCopyIsInterruptedAndRetryable() {
        ExportJobRepository.prepare(
            summary = summary,
            categories = emptyList(),
            includeCombined = false,
            jobId = "job-3",
            fileName = "BEDROCK_OFFSETS_26.60.3.zip",
            destinationUri = "content://test/out.zip"
        )

        ExportJobRepository.fail("storage full", interrupted = true)

        assertEquals(ExportState.Interrupted, ExportJobRepository.progress.value.state)
        assertEquals("storage full", ExportJobRepository.progress.value.error)
    }

    @Test
    fun aRestoredRunningJobBecomesInterrupted() {
        ExportJobRepository.restore(
            PersistedExportJob(
                jobId = "job-4",
                zipName = "BEDROCK_OFFSETS_26.60.3.zip",
                destinationUri = "content://test/out.zip",
                stage = ExportStage.Saving.name,
                bytesDone = 10,
                bytesTotal = 100,
                state = ExportState.Running.name,
                zipPath = "/tmp/out.zip"
            )
        )

        assertEquals(ExportState.Interrupted, ExportJobRepository.progress.value.state)
        assertEquals(ExportStage.Saving, ExportJobRepository.progress.value.stage)
        assertEquals(0.1f, ExportJobRepository.progress.value.fraction)
    }

    @Test
    fun theJobRecordRoundTripsThroughDisk() {
        val file = File(createTempDirectory().toFile(), "export-job.json")
        val store = ExportJobStore(file)
        val job = PersistedExportJob(
            jobId = "job-5",
            zipName = "BEDROCK_OFFSETS_26.60.3.zip",
            destinationUri = "content://test/out.zip",
            stage = ExportStage.Saving.name,
            bytesDone = 25,
            bytesTotal = 100,
            state = ExportState.Interrupted.name,
            zipPath = "/tmp/out.zip",
            version = "26.60.3"
        )

        store.save(job)
        assertEquals(job, store.load())

        store.clear()
        assertNull(store.load())
        assertTrue(!file.exists())
    }
}
