package com.chimerateam.offsetextractor.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.export.CoverageBucket
import com.chimerateam.offsetextractor.data.export.CoverageCalculator
import com.chimerateam.offsetextractor.data.export.CoverageReport
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.MonoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The coverage figures of a finished scan, per category and overall.
 *
 * Enumeration completeness ("4,000 of 4,000 functions") says nothing about
 * whether the engine could name anything, so this reports the two numbers that
 * do: how many functions carry a real name, and how many carry a signature that
 * matches exactly one place in `.text`. The counting rules are
 * [CoverageCalculator]'s, which are the ones `scripts/coverage_report.py` uses,
 * so a figure read here matches the figure in a report of the same export.
 *
 * The walk streams the category documents off the main thread, so opening the
 * section never blocks the UI and never loads a hundred-megabyte file.
 *
 * @param result the scan whose documents are measured.
 */
@Composable
fun CoverageSection(result: ScanResultSummary) {
    var expanded by remember(result) { mutableStateOf(false) }
    var report by remember(result) { mutableStateOf<CoverageReport?>(null) }
    var running by remember(result) { mutableStateOf(false) }
    var failed by remember(result) { mutableStateOf<String?>(null) }

    LaunchedEffect(result, expanded) {
        if (!expanded || report != null || running) return@LaunchedEffect
        running = true
        val measured = withContext(Dispatchers.IO) {
            runCatching { CoverageCalculator.compute(result) }
        }
        running = false
        failed = measured.exceptionOrNull()?.message
        report = measured.getOrNull()
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Coverage",
            style = MonoText.headline,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (expanded) "hide" else "show",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    if (!expanded) return
    Spacer(Modifier.height(4.dp))
    val measured = report
    when {
        running -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Text(
                    text = "Counting names and signatures...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        failed != null -> Text(
            text = "Coverage could not be measured: $failed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        measured == null -> Text(
            text = "This scan left no category documents to measure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> CoverageTable(measured)
    }
}

/**
 * Renders the aggregate rows and then one row per category.
 *
 * @param report the measured coverage.
 */
@Composable
private fun CoverageTable(report: CoverageReport) {
    CoverageLine(report.gameplay, emphasis = true)
    CoverageLine(report.overall, emphasis = true)
    Spacer(Modifier.height(6.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        for (bucket in report.categories) {
            CoverageLine(bucket, emphasis = false)
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "A real name is neither sub_<rva> nor Class::vfunc_N. Gameplay " +
            "leaves out the thirdparty category. Unique means the signature " +
            "matched exactly one place in .text.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * One bucket: name, function count, real-name share, unique-signature share.
 *
 * @param bucket the measured bucket.
 * @param emphasis whether this is an aggregate row.
 */
@Composable
private fun CoverageLine(bucket: CoverageBucket, emphasis: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = bucket.label,
            style = if (emphasis) MonoText.body else MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(
            text = "${bucket.functions} fn",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "%.1f%% named".format(bucket.realNamedPercent),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "%.1f%% sig".format(bucket.uniqueSignaturePercent),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
