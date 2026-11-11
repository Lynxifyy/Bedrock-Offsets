package com.chimerateam.offsetextractor

import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chimerateam.offsetextractor.data.cache.ScanCache
import com.chimerateam.offsetextractor.data.cache.scanPreferences
import com.chimerateam.offsetextractor.data.native.JniOffsetEngine
import com.chimerateam.offsetextractor.data.patterns.SignaturePack
import com.chimerateam.offsetextractor.data.saf.AndroidDocumentWriter
import com.chimerateam.offsetextractor.data.saf.AndroidSafApkAccess
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
                // The extracted library must live inside the managed cache, or
                // "Clear cache" never frees the largest file the app creates.
                val scanCache = ScanCache(File(cacheDir, "scan-cache").apply { mkdirs() })
                MainViewModel.create(
                    safAccess = AndroidSafApkAccess(applicationContext),
                    engine = JniOffsetEngine(),
                    cacheDir = scanCache.libraryDir.apply { mkdirs() },
                    writer = AndroidDocumentWriter(applicationContext),
                    cache = scanCache,
                    preferences = scanPreferences(this@MainActivity),
                    patterns = SignaturePack.load(applicationContext)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BedrockOffsetExtractorApp(viewModel = viewModel)
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
}

@Preview(showBackground = true)
@Composable
private fun ThemePreview() {
    BedrockOffsetExtractorTheme {
        // Previews render the palette only; the interactive shell needs a
        // ViewModel and a real content resolver.
    }
}
