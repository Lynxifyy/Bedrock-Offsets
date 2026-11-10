package com.chimerateam.offsetextractor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chimerateam.offsetextractor.data.native.JniOffsetEngine
import com.chimerateam.offsetextractor.data.saf.AndroidSafApkAccess
import com.chimerateam.offsetextractor.ui.BedrockOffsetExtractorApp
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.data.patterns.SignaturePack
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
                MainViewModel.create(
                    safAccess = AndroidSafApkAccess(applicationContext),
                    engine = JniOffsetEngine(),
                    cacheDir = File(cacheDir, "engine").apply { mkdirs() },
                    patterns = SignaturePack.load(applicationContext),
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
}



@Preview(showBackground = true)
@Composable
private fun ThemePreview() {
    BedrockOffsetExtractorTheme {
        // Previews render the palette only; the interactive shell needs a
        // ViewModel and a real content resolver.
    }
}
