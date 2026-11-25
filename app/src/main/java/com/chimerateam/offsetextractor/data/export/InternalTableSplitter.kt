package com.chimerateam.offsetextractor.data.export

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedWriter
import java.io.File

/** Maximum rows in one `unnamed` part. */
const val UNNAMED_PART_SIZE = 100_000

/** Counts of one category file. */
data class CategoryCounts(
    val classes: Int = 0,
    val functions: Int = 0,
    val symbols: Int = 0
) {
    /** Total records in the file. */
    val total: Int get() = classes + functions + symbols
}

/**
 * One category file and the raw NDJSON records that belong to it.
 *
 * @property id file identifier, for example `actor_entity` or `unnamed_part_01`.
 * @property categoryId logical category, `unnamed` for every part.
 * @property label human-readable label.
 * @property part one-based part number, or zero when the category is not split.
 * @property classFile raw class records, or `null` when there are none.
 * @property functionFiles raw function records, split for `unnamed`.
 * @property symbolFile raw data-symbol records, or `null`.
 * @property counts how many records each file holds.
 */
data class CategoryPart(
    val id: String,
    val categoryId: String,
    val label: String,
    val part: Int = 0,
    val classFile: File? = null,
    val functionFiles: List<File> = emptyList(),
    val symbolFile: File? = null,
    val counts: CategoryCounts = CategoryCounts()
)

/**
 * Outcome of splitting the internal NDJSON table.
 *
 * @property parts one entry per category file, in rule order.
 * @property classToCategory every class name mapped to its category.
 * @property totalClasses classes read from the table.
 * @property totalFunctions functions read from the table.
 * @property totalSymbols data symbols read from the table.
 */
data class SplitResult(
    val parts: List<CategoryPart>,
    val classToCategory: Map<String, String>,
    val totalClasses: Int,
    val totalFunctions: Int,
    val totalSymbols: Int
) {
    /** Functions assigned to a category file. */
    val assignedFunctions: Int get() = parts.sumOf { it.counts.functions }

    /** Whether every function was assigned exactly once. */
    val consistent: Boolean get() = assignedFunctions == totalFunctions
}

/**
 * Splits the internal NDJSON table into one raw file per category.
 *
 * The table is read twice: once for classes, which are few and define the
 * category of their vtable slots, and once for functions and data symbols. Only
 * writers are held in memory, so a table of hundreds of thousands of rows never
 * becomes a list of objects.
 *
 * @param classifier assigns each record to a category.
 * @param workDir directory that receives the raw files.
 */
class InternalTableSplitter(
    private val classifier: Classifier,
    private val workDir: File,
    private val partSize: Int = UNNAMED_PART_SIZE
) {

    /**
     * Splits [table].
     *
     * @param table NDJSON file written by the engine.
     * @param onProgress receives `(percent, message)` updates.
     * @return the files and the class-to-category map.
     */
    fun split(table: File, onProgress: (Int, String) -> Unit = { _, _ -> }): SplitResult {
        workDir.mkdirs()
        if (!table.isFile) {
            return SplitResult(emptyList(), emptyMap(), 0, 0, 0)
        }

        val classWriters = mutableMapOf<String, BufferedWriter>()
        val functionWriters = mutableMapOf<String, BufferedWriter>()
        val symbolWriters = mutableMapOf<String, BufferedWriter>()
        val functionCounts = mutableMapOf<String, Int>()
        val classCounts = mutableMapOf<String, Int>()
        val symbolCounts = mutableMapOf<String, Int>()
        val functionParts = mutableMapOf<String, MutableList<File>>()
        val classToCategory = mutableMapOf<String, String>()

        fun classFile(id: String) = File(workDir, "$id.classes.ndjson")
        fun functionFile(id: String) = File(workDir, "$id.functions.ndjson")
        fun symbolFile(id: String) = File(workDir, "$id.symbols.ndjson")

        fun classWriter(id: String): BufferedWriter = classWriters.getOrPut(id) {
            classFile(id).bufferedWriter()
        }

        fun functionWriter(id: String): BufferedWriter = functionWriters.getOrPut(id) {
            functionParts.getOrPut(id) { mutableListOf() }.add(functionFile(id))
            functionFile(id).bufferedWriter()
        }

        fun symbolWriter(id: String): BufferedWriter = symbolWriters.getOrPut(id) {
            symbolFile(id).bufferedWriter()
        }

        try {
            var totalClasses = 0
            table.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val root = parse(line) ?: continue
                    if (root.get("type")?.asString != "class") continue
                    val info = root.getAsJsonObject("class") ?: continue
                    val name = info.get("name")?.asString ?: continue
                    val bases = info.get("bases")?.takeIf { it.isJsonArray }
                        ?.asJsonArray?.mapNotNull { it.asString } ?: emptyList()
                    val category = classifier.categoryForClass(name, bases)
                        ?: CategoryCatalog.OTHER_NAMED
                    classToCategory[name] = category
                    classWriter(category).appendLine(line)
                    classCounts[category] = (classCounts[category] ?: 0) + 1
                    totalClasses += 1
                }
            }
            onProgress(40, "Classified $totalClasses classes")

            var totalFunctions = 0
            var totalSymbols = 0
            var unnamedCount = 0
            var part = 1
            var lineNumber = 0
            table.bufferedReader().useLines { lines ->
                for (line in lines) {
                    lineNumber += 1
                    if (lineNumber % 50_000 == 0) {
                        onProgress(
                            40 + (lineNumber % 40),
                            "Classified $lineNumber rows"
                        )
                    }
                    val root = parse(line) ?: continue
                    when (root.get("type")?.asString) {
                        "function" -> {
                            val function = root.getAsJsonObject("function") ?: continue
                            val name = function.get("name")?.asString ?: continue
                            val category = categoryOf(name, classToCategory)
                            if (category == CategoryCatalog.UNNAMED) {
                                if (unnamedCount > 0 && unnamedCount % partSize == 0) {
                                    functionWriters.remove(
                                        "unnamed_part_${part.toString().padStart(2, '0')}"
                                    )
                                        ?.close()
                                    part += 1
                                }
                                val id = "unnamed_part_${part.toString().padStart(2, '0')}"
                                functionWriter(id).appendLine(line)
                                functionCounts[id] = (functionCounts[id] ?: 0) + 1
                                unnamedCount += 1
                            } else {
                                functionWriter(category).appendLine(line)
                                functionCounts[category] =
                                    (functionCounts[category] ?: 0) + 1
                            }
                            totalFunctions += 1
                        }

                        "symbol" -> {
                            val symbol = root.getAsJsonObject("symbol") ?: continue
                            val name = symbol.get("name")?.asString ?: continue
                            val category = categoryOf(name, classToCategory)
                                .takeUnless { it == CategoryCatalog.UNNAMED }
                                ?: CategoryCatalog.OTHER_NAMED
                            symbolWriter(category).appendLine(line)
                            symbolCounts[category] = (symbolCounts[category] ?: 0) + 1
                            totalSymbols += 1
                        }
                    }
                }
            }
            onProgress(80, "Classified $totalFunctions functions")

            classWriters.values.forEach { it.close() }
            functionWriters.values.forEach { it.close() }
            symbolWriters.values.forEach { it.close() }

            val parts = buildParts(
                classToCategory,
                classCounts,
                functionCounts,
                symbolCounts,
                functionParts,
                ::classFile,
                ::symbolFile
            )
            return SplitResult(
                parts = parts,
                classToCategory = classToCategory,
                totalClasses = totalClasses,
                totalFunctions = totalFunctions,
                totalSymbols = totalSymbols
            )
        } finally {
            classWriters.values.forEach { runCatching { it.close() } }
            functionWriters.values.forEach { runCatching { it.close() } }
            symbolWriters.values.forEach { runCatching { it.close() } }
        }
    }

    private fun categoryOf(name: String, classToCategory: Map<String, String>): String {
        val className = if (name.contains("::")) name.substringBefore("::") else null
        val classCategory = className?.let {
            classToCategory[it] ?: classifier.categoryForClassName(it)
        }
        return classifier.categoryForFunction(name, classCategory)
    }

    private fun buildParts(
        classToCategory: Map<String, String>,
        classCounts: Map<String, Int>,
        functionCounts: Map<String, Int>,
        symbolCounts: Map<String, Int>,
        functionParts: Map<String, MutableList<File>>,
        classFile: (String) -> File,
        symbolFile: (String) -> File
    ): List<CategoryPart> {
        val ids = LinkedHashSet<String>()
        ids.addAll(classifier.categoryIds)
        ids.addAll(classToCategory.values)
        ids.addAll(functionCounts.keys.filterNot { it.startsWith("unnamed_part_") })
        ids.add(CategoryCatalog.OTHER_NAMED)
        ids.add(CategoryCatalog.UNNAMED)

        val parts = mutableListOf<CategoryPart>()
        for (id in ids) {
            val classCount = classCounts[id] ?: 0
            val functionCount = functionCounts[id] ?: 0
            val symbolCount = symbolCounts[id] ?: 0
            val classes = classFile(id).takeIf { classCount > 0 }
            val symbols = symbolFile(id).takeIf { symbolCount > 0 }
            if (id == CategoryCatalog.UNNAMED) {
                val unnamedIds = functionParts.keys
                    .filter { it.startsWith("unnamed_part_") }
                    .sorted()
                if (unnamedIds.isEmpty()) {
                    parts += CategoryPart(
                        id = id,
                        categoryId = id,
                        label = classifier.labelOf(id)
                    )
                } else {
                    for ((index, partId) in unnamedIds.withIndex()) {
                        parts += CategoryPart(
                            id = partId,
                            categoryId = id,
                            label = "${classifier.labelOf(id)} (part ${index + 1})",
                            part = index + 1,
                            functionFiles = listOf(File(workDir, "$partId.functions.ndjson")),
                            counts = CategoryCounts(
                                functions = functionCounts[partId] ?: 0
                            )
                        )
                    }
                }
                continue
            }
            parts += CategoryPart(
                id = id,
                categoryId = id,
                label = classifier.labelOf(id),
                classFile = classes,
                functionFiles = functionParts[id].orEmpty(),
                symbolFile = symbols,
                counts = CategoryCounts(
                    classes = classCount,
                    functions = functionCount,
                    symbols = symbolCount
                )
            )
        }
        return parts
    }

    private fun parse(line: String): JsonObject? =
        runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
}
