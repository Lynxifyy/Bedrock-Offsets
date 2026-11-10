package com.chimerateam.offsetextractor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.chimerateam.offsetextractor.ui.BedrockOffsetExtractorApp
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme

/**
 * Single-activity host for the offset extractor UI.
 *
 * The window draws behind the system bars; content padding is applied by the
 * scaffold inside [BedrockOffsetExtractorApp].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BedrockOffsetExtractorApp()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RootPreview() {
    BedrockOffsetExtractorTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            BedrockOffsetExtractorApp()
        }
    }
}
