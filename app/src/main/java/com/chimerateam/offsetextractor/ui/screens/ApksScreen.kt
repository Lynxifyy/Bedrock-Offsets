package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.ui.components.SectionPanel
import com.chimerateam.offsetextractor.ui.theme.MonoText
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tag of the catalogue list. */
internal const val APK_CATALOG_TAG = "apk-catalog"

/**
 * One downloadable Bedrock APK offered by the catalogue.
 *
 * @property version dotted version label, used for sorting.
 * @property label human-readable title from the page.
 * @property url direct or landing URL.
 */
data class ApkOffer(
    val version: String,
    val label: String,
    val url: String
)

/**
 * Fetches and downloads Bedrock APKs from `mcpedl.org`.
 *
 * The site is a normal HTML page, so the catalogue is built from anchor tags
 * and sorted by the version in their text. A download that lands on a landing
 * page is resolved to the first `.apk` link on that page.
 */
object ApkCatalog {

    /** Page that lists downloadable Bedrock builds. */
    const val CATALOG_URL = "https://mcpedl.org/downloading/"

    private val anchorPattern =
        Regex("""<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val versionPattern = Regex("""(\d+)[-._](\d+)(?:[-._](\d+))?""")
    private val tagPattern = Regex("<[^>]+>")
    private val formPattern =
        Regex("""<form[^>]+action="([^"]*show_file[^"]*)"[^>]*>(.*?)</form>""", RegexOption.DOT_MATCHES_ALL)
    private val hiddenInputPattern =
        Regex("""<input[^>]+name="([^"]+)"[^>]+value="([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
    private val apkUrlPattern =
        Regex("""https?://[^"'\s<>]+\.apk[^"'\s<>]*""", RegexOption.IGNORE_CASE)

    /**
     * Reads the catalogue page and returns the offers, newest first.
     *
     * @return offers with a version and an http(s) link.
     * @throws java.io.IOException when the page cannot be fetched.
     */
    fun fetch(): List<ApkOffer> {
        val html = readText(CATALOG_URL)
        val offers = anchorPattern.findAll(html).mapNotNull { match ->
            val href = match.groupValues[1].trim()
            val label = tagPattern.replace(match.groupValues[2], " ").trim()
            val version = versionPattern.find(label) ?: versionPattern.find(href)
            if (version == null) return@mapNotNull null
            if (!href.startsWith("http")) return@mapNotNull null
            val dotted = version.groupValues.drop(1)
                .filter { it.isNotEmpty() }
                .joinToString(".")
            ApkOffer(version = dotted, label = label, url = href)
        }.distinctBy { it.url }.toList()
        return offers.sortedWith { left, right ->
            compareVersions(right.version, left.version)
        }
    }

    /**
     * Downloads [url] into [directory], resolving a landing page first.
     *
     * @param url direct `.apk` URL or a landing page.
     * @param directory destination directory.
     * @param onProgress receives a percentage, or `-1` when the size is unknown.
     * @return the downloaded APK.
     * @throws java.io.IOException when the download fails.
     */
    fun download(
        url: String,
        directory: File,
        fileName: String = "",
        onProgress: (Int) -> Unit = {}
    ): File {
        val direct = if (url.endsWith(".apk", ignoreCase = true)) url else resolveApkLink(url)
        directory.mkdirs()
        val name = fileName.ifBlank {
            direct.substringAfterLast('/').substringBefore('?')
                .ifEmpty { "minecraft.apk" }
                .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        }
        val target = File(directory, name)
        // A finished download is immutable, so a second Extract on the same
        // version reuses it instead of spending another 380 MB of transfer.
        if (target.isFile && target.length() > 0L) return target
        val part = File(directory, "$name.part")
        val connection = open(direct)
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(if (total > 0) ((copied * 100) / total).toInt() else -1)
                    }
                    output.flush()
                }
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return target
        } catch (error: Throwable) {
            part.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveApkLink(url: String): String {
        val html = readText(url)
        directApk(html)?.let { return it }
        val form = formPattern.find(html)
            ?: throw java.io.IOException("No APK link was found on the download page.")
        val action = form.groupValues[1].trim().let { href ->
            if (href.startsWith("http")) href else "https://mcpedl.org$href"
        }
        val fields = hiddenInputPattern.findAll(form.value).associate { input ->
            input.groupValues[1] to input.groupValues[2]
        }
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val response = readText(action, body)
        return directApk(response)
            ?: throw java.io.IOException("The download page did not expose an APK link.")
    }

    private fun directApk(html: String): String? =
        apkUrlPattern.find(html)?.value
            ?: anchorPattern.findAll(html)
                .map { it.groupValues[1].trim() }
                .firstOrNull { it.startsWith("http") && it.contains(".apk", ignoreCase = true) }

    private fun readText(url: String, postBody: String? = null): String {
        val connection = open(url)
        return try {
            if (postBody != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded"
                )
                connection.outputStream.use { output ->
                    output.write(postBody.toByteArray(Charsets.UTF_8))
                }
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
        connection.setRequestProperty("Accept", "text/html,application/octet-stream,*/*")
        return connection
    }

    /** Compares dotted versions numerically, padding the shorter one with zeros. */
    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val difference = (leftParts.getOrNull(index) ?: 0) -
                (rightParts.getOrNull(index) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }
}

/**
 * APK catalogue destination.
 *
 * Lists builds from [ApkCatalog.CATALOG_URL] newest first and offers an Extract
 * action per row. Extraction downloads the APK into the app cache and hands the
 * file to [onExtract], which runs the same scan as a picker-selected APK.
 *
 * @param onExtract called with the downloaded APK.
 * @param modifier layout modifier for the screen.
 */
@Composable
fun ApksScreen(
    onExtract: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offers by remember { mutableStateOf<List<ApkOffer>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var catalogueError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busyUrl by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    var manualUrl by rememberSaveable { mutableStateOf("") }

    fun refresh() {
        loading = true
        catalogueError = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ApkCatalog.fetch() }
            }
            loading = false
            outcome.onSuccess { offers = it }
                .onFailure {
                    catalogueError = it.message ?: "Could not read the catalogue."
                }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        SectionPanel(title = "Minecraft Bedrock APKs") {
            Text(
                text = "Newest first, from mcpedl.org. Extraction runs the same " +
                    "engine as a file you pick yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Direct APK URL") }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refresh() }, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("Refresh", modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = {
                        val url = manualUrl.trim()
                        if (url.isEmpty()) return@Button
                        busyUrl = url
                        progress = 0
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    ApkCatalog.download(
                                        url,
                                        File(context.cacheDir, "apks")
                                    ) {
                                        progress = it
                                    }
                                }
                            }
                            busyUrl = null
                            outcome.onSuccess(onExtract)
                                .onFailure { actionError = it.message ?: "Download failed." }
                        }
                    },
                    enabled = manualUrl.trim().isNotEmpty() && busyUrl == null
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text("Download & extract", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        actionError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Reading the catalogue...", modifier = Modifier.padding(start = 12.dp))
            }

            catalogueError != null -> Text(
                text = "Catalogue unavailable: $catalogueError\n" +
                    "Paste a direct APK URL above to extract anyway.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )

            offers.isEmpty() -> Text(
                text = "No downloadable builds were found on the page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag(APK_CATALOG_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(offers, key = { it.url }) { offer ->
                    ApkOfferRow(
                        offer = offer,
                        busy = busyUrl == offer.url,
                        progress = if (busyUrl == offer.url) progress else 0,
                        onExtract = {
                            busyUrl = offer.url
                            progress = 0
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    runCatching {
                                        ApkCatalog.download(
                                            offer.url,
                                            File(context.cacheDir, "apks"),
                                            "minecraft-${offer.version.replace('.', '-')}.apk"
                                        ) { progress = it }
                                    }
                                }
                                busyUrl = null
                                outcome.onSuccess(onExtract)
                                    .onFailure {
                                        actionError = it.message ?: "Download failed."
                                    }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApkOfferRow(
    offer: ApkOffer,
    busy: Boolean,
    progress: Int,
    onExtract: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Minecraft ${offer.version}", style = MonoText.headline)
                Text(
                    text = offer.label.take(96),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                if (busy) {
                    Spacer(Modifier.height(6.dp))
                    if (progress >= 0) {
                        Text("Downloading $progress%", style = MonoText.body)
                    } else {
                        Text("Downloading...", style = MonoText.body)
                    }
                }
            }
            Button(onClick = onExtract, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null)
                }
                Text("Extract", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
