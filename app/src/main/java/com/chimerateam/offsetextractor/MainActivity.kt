package com.chimerateam.offsetextractor

import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chimerateam.offsetextractor.data.cache.ScanCache
import com.chimerateam.offsetextractor.data.cache.scanPreferences
import com.chimerateam.offsetextractor.data.export.CategoryCatalog
import com.chimerateam.offsetextractor.data.export.ExportJobRepository
import com.chimerateam.offsetextractor.data.export.ExportStarter
import com.chimerateam.offsetextractor.data.export.ExportState
import com.chimerateam.offsetextractor.data.export.OffsetExporter
import com.chimerateam.offsetextractor.data.storage.StorageManager
import com.chimerateam.offsetextractor.data.native.JniOffsetEngine
import com.chimerateam.offsetextractor.data.patterns.SignaturePack
import com.chimerateam.offsetextractor.data.saf.AndroidDocumentWriter
import com.chimerateam.offsetextractor.data.saf.AndroidSafApkAccess
import com.chimerateam.offsetextractor.export.ExportService
import com.chimerateam.offsetextractor.ui.BedrockOffsetExtractorApp
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.viewmodel.MainViewModel
import java.io.File

/**
 * Single-activity host for the offset extractor UI.
 *
 * The window draws behind the system bars; content padding is applied by the
 * scaffold inside [BedrockOffsetExtractorApp]. The [MainViewModel] is retained
 * across configuration changes, so a rotation does not cancel a running scan.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                // The extracted library and the internal table are the only
                // copies of what a scan produced, and Android deletes cacheDir
                // under storage pressure, so they live in filesDir instead.
                // Auto-backup excludes this directory (res/xml/backup_rules.xml).
                val scanCache = ScanCache(
                    File(filesDir, ScanCache.DIRECTORY_NAME).apply { mkdirs() }
                )
                val pack = SignaturePack.loadDocument(applicationContext)
                val storage = StorageManager.create(
                    cacheDir = cacheDir,
                    cache = scanCache,
                    exportDir = File(cacheDir, "exports").apply { mkdirs() },
                    logDir = File(cacheDir, "logs").apply { mkdirs() }
                )
                MainViewModel.create(
                    safAccess = AndroidSafApkAccess(applicationContext),
                    engine = JniOffsetEngine(),
                    cacheDir = scanCache.libraryDir.apply { mkdirs() },
                    writer = AndroidDocumentWriter(applicationContext),
                    cache = scanCache,
                    preferences = scanPreferences(this@MainActivity),
                    patterns = pack.patterns,
                    internalPatterns = pack.internalPatterns,
                    stringAnchors = pack.stringAnchors,
                    categories = CategoryCatalog.load(applicationContext),
                    storage = storage,
                    exportStarter = ExportStarter { destination, summary, categories, combined ->
                        // The write grant must be taken before the service
                        // starts; the activity may be gone by the time it runs.
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                destination,
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        }
                        ExportService.start(
                            applicationContext,
                            summary,
                            categories,
                            combined,
                            destination,
                            OffsetExporter.zipFileName(summary)
                        )
                    }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyFullScreen()
        ExportService.loadJob(applicationContext)?.let { job ->
            if (job.state == ExportState.Running.name ||
                job.state == ExportState.Interrupted.name
            ) {
                ExportJobRepository.restore(job)
            }
        }
        setContent {
            BedrockOffsetExtractorApp(viewModel = viewModel)
        }
    }

    /**
     * Hides the system bars so the scanner owns the whole display.
     *
     * The bars come back on a swipe, which is the behaviour a tablet user
     * expects from a full-screen tool.
     */
    private fun applyFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    /**
     * Reports system memory pressure to the ViewModel.
     *
     * Android calls this before it starts killing processes. The scan itself
     * never allocates with the file size, so the useful response is to tell the
     * user and let them free the cache rather than to abort the scan.
     *
     * `TRIM_MEMORY_UI_HIDDEN` is excluded: it fires whenever the app leaves the
     * foreground -- opening the file picker, for example -- and is not memory
     * pressure.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && level < TRIM_MEMORY_UI_HIDDEN) {
            viewModel.onMemoryPressure()
        }
    }

    /**
     * Reports a whole-system memory shortage to the ViewModel.
     *
     * `onLowMemory` is the older, coarser sibling of [onTrimMemory] and is
     * still delivered on some devices; both paths raise the same notice.
     */
    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        viewModel.onMemoryPressure()
    }
}

@Preview(showBackground = true)
@Composable
private fun ThemePreview() {
    BedrockOffsetExtractorTheme {
        // Previews render the palette only; the interactive shell needs a
        // ViewModel and a real content resolver.
    }
}
