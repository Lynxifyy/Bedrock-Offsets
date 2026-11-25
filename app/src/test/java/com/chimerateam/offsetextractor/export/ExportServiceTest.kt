package com.chimerateam.offsetextractor.export

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.data.export.CategoryRule
import com.chimerateam.offsetextractor.data.export.ExportJobRepository
import com.chimerateam.offsetextractor.data.export.ExportState
import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies that the export survives the activity that started it.
 *
 * Robolectric does not execute a service from `startService`, so the recorded
 * intent is handed to a real service controller. The activity is destroyed
 * immediately afterwards; only the service's own scope is left to finish the
 * job.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExportServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val rules = listOf(
        CategoryRule(
            id = "actor_entity",
            label = "Actors",
            classes = listOf(Regex("^Player$")),
            functions = emptyList(),
            namespaces = emptyList(),
            includeBases = true
        ),
        CategoryRule(
            id = "other_named",
            label = "Other",
            classes = emptyList(),
            functions = emptyList(),
            namespaces = emptyList(),
            includeBases = false
        ),
        CategoryRule(
            id = "unnamed",
            label = "Unnamed",
            classes = emptyList(),
            functions = emptyList(),
            namespaces = emptyList(),
            includeBases = false
        )
    )

    @Before
    @After
    fun reset() {
        ExportJobRepository.clear()
    }

    private fun table(): File {
        val file = File(context.cacheDir, "service-table.ndjson")
        file.writeText(
            """{"type":"class","class":{"name":"Player","mangled":"Player",""" +
                """"bases":[],"vtable":"0x3000","vtableOffset":"0x3000",""" +
                """"typeInfo":"0x2f00","slotCount":1,"slots":[]}}""" + "\n" +
                """{"type":"function","function":{"address":"0x1000",""" +
                """"fileOffset":"0x0","size":16,"name":"Player::getPos",""" +
                """"nameSource":"symbol","aliases":[],"kind":"function",""" +
                """"status":"verified","prologueKind":"frame_push"}}"""
        )
        return file
    }

    private fun summary(file: File) = ScanResultSummary(
        displayName = "minecraft-26-60-arm64-v8a-xbox.apk",
        sha256 = "abc",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        buildId = "deadbeef",
        apkVersion = "26.60.3",
        internal = InternalSummary(
            classes = 1,
            vtableSlots = 1,
            functions = 1,
            named = 1,
            path = file.absolutePath
        ),
        internalResolved = true
    )

    private fun waitFor(state: ExportState, timeoutMillis: Long = 30_000): ExportState {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val current = ExportJobRepository.progress.value.state
            if (current == state || current == ExportState.Failed) return current
            Thread.sleep(50)
        }
        return ExportJobRepository.progress.value.state
    }

    @Test
    fun theJobFinishesAfterTheActivityIsDestroyed() {
        val destination = File(context.cacheDir, "destination.zip")
        destination.delete()
        val uri = Uri.fromFile(destination)

        ExportService.start(
            context = context,
            summary = summary(table()),
            categories = rules,
            includeCombined = false,
            destination = uri,
            fileName = "BEDROCK_OFFSETS_26.60.3.zip"
        )
        val started = shadowOf(context as Application).nextStartedService
        val controller = Robolectric.buildService(ExportService::class.java, started)
            .create()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.startCommand(0, 1)
        activity.destroy()

        val finished = waitFor(ExportState.Done)
        assertEquals(
            "error=${ExportJobRepository.progress.value.error}",
            ExportState.Done,
            finished
        )
        assertTrue("the destination was not written", destination.length() > 0)
        assertTrue(ExportJobRepository.progress.value.zipPath?.endsWith(".zip") == true)
        controller.destroy()
    }

    @Test
    fun aFailedCopyDeletesThePartialDestination() {
        val destination = File(context.cacheDir, "rejected.zip")
        destination.writeText("partial")
        // A read-only file cannot be opened for writing, so the copy fails and
        // the service must remove the partial destination.
        destination.setWritable(false)
        val uri = Uri.fromFile(destination)

        ExportService.start(
            context = context,
            summary = summary(table()),
            categories = rules,
            includeCombined = false,
            destination = uri,
            fileName = "BEDROCK_OFFSETS_26.60.3.zip"
        )
        val started = shadowOf(context as Application).nextStartedService
        val controller = Robolectric.buildService(ExportService::class.java, started)
            .create()
        controller.startCommand(0, 1)

        assertEquals(ExportState.Interrupted, waitFor(ExportState.Interrupted))
        assertFalse("the partial destination must be removed", destination.exists())
        controller.destroy()
    }
}
