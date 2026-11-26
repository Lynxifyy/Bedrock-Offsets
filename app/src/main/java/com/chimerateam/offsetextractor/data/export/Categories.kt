package com.chimerateam.offsetextractor.data.export

import android.content.Context
import android.net.Uri
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One ordered classification rule.
 *
 * Rules are evaluated in file order; the first one that matches wins. A class
 * rule also matches a subclass when [includeBases] is set, which is what makes
 * `Player` land in the actor category through `Mob` and `Actor`.
 *
 * @property id stable category identifier used in file names.
 * @property label human-readable category name.
 * @property classes regular expressions matched against the class name and its
 *   bases.
 * @property functions regular expressions searched in a demangled function
 *   name.
 * @property namespaces namespace prefixes for third-party code.
 * @property includeBases whether a base class may satisfy a class rule.
 */
data class CategoryRule(
    val id: String,
    val label: String,
    val classes: List<Regex>,
    val functions: List<Regex>,
    val namespaces: List<String>,
    val includeBases: Boolean
)

/**
 * Loads and parses `assets/categories.json`.
 *
 * The file is data, not code, so a user can import a corrected rule set from
 * Settings without a rebuild. A malformed rule is skipped rather than failing
 * the whole export.
 */
object CategoryCatalog {

    /** Asset that ships the default rules. */
    const val ASSET_NAME = "categories.json"

    /** Name of the user-imported override in `filesDir`. */
    const val OVERRIDE_NAME = "categories.json"

    /** Category a named entry falls back to when nothing matched. */
    const val OTHER_NAMED = "other_named"

    /** Category for `sub_*` entries. */
    const val UNNAMED = "unnamed"

    /**
     * Reads the bundled rule set.
     *
     * @param context any context; only the asset manager is used.
     * @return the rules, or an empty list when the asset is unreadable.
     */
    fun load(context: Context): List<CategoryRule> {
        val override = overrideFile(context)
        if (override.isFile) {
            val parsed = runCatching { parse(override.readText()) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
        }.getOrDefault(emptyList())
    }

    /** Whether a user override is installed. */
    fun hasOverride(context: Context): Boolean = overrideFile(context).isFile

    /**
     * Copies an imported rule document into the app's private storage.
     *
     * @param context any context.
     * @param uri the document the user picked.
     * @return whether the document parsed into at least one rule.
     */
    fun import(context: Context, uri: Uri): Boolean = runCatching {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return false
        if (parse(text).isEmpty()) return false
        val target = overrideFile(context)
        target.parentFile?.mkdirs()
        target.writeText(text)
        true
    }.getOrDefault(false)

    /** Removes the override so the bundled rules apply again. */
    fun reset(context: Context) {
        runCatching { overrideFile(context).delete() }
    }

    private fun overrideFile(context: Context): java.io.File =
        java.io.File(context.filesDir, OVERRIDE_NAME)

    /** The bundled rule file as text, used by the "Reset to default" action. */
    fun defaultText(context: Context): String = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    /**
     * Parses a rule document.
     *
     * @param json the document text.
     * @return the rules in file order.
     */
    fun parse(json: String): List<CategoryRule> {
        val parsed = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        val array = when {
            parsed.isJsonArray -> parsed.asJsonArray
            parsed.isJsonObject -> parsed.asJsonObject.getAsJsonArray("categories")
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val rule = element.asJsonObject
                val id = rule.string("category") ?: rule.string("id")
                    ?: return@runCatching null
                CategoryRule(
                    id = id,
                    label = rule.string("label") ?: id,
                    classes = rule.patterns("classes"),
                    functions = rule.patterns("functions"),
                    namespaces = rule.strings("namespaces"),
                    includeBases = rule.get("includeBases")?.asBoolean ?: true
                )
            }.getOrNull()
        }
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.strings(name: String): List<String> =
        getAsJsonArray(name)?.mapNotNull { it.asString } ?: emptyList()

    private fun JsonObject.patterns(name: String): List<Regex> =
        strings(name).mapNotNull { pattern ->
            runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        }
}

/**
 * Assigns every class and function to exactly one category.
 *
 * @param rules ordered rules, first match wins.
 */
class Classifier(private val rules: List<CategoryRule>) {

    /** Category ids in rule order, used for stable file ordering. */
    val categoryIds: List<String> = rules.map { it.id }

    /** Every rule as `(id, label)`, in rule order, including empty categories. */
    fun categories(): List<Pair<String, String>> = rules.map { it.id to it.label }

    /** Label for [id], falling back to the id itself. */
    fun labelOf(id: String): String = rules.firstOrNull { it.id == id }?.label ?: id

    /**
     * Category of a class, matched against its own name and its bases.
     *
     * @param name demangled class name.
     * @param bases base class names, most-derived first.
     * @return the first matching category, or `null` when no rule matched.
     */
    fun categoryForClass(name: String, bases: List<String>): String? {
        val candidates = buildList {
            add(name)
            addAll(bases)
        }
        for (rule in rules) {
            if (rule.classes.isEmpty()) continue
            for (pattern in rule.classes) {
                val target = if (rule.includeBases) candidates else listOf(name)
                if (target.any { pattern.matches(it) }) return rule.id
            }
        }
        return null
    }

    /** Category of a class name alone, without a base list. */
    fun categoryForClassName(name: String): String? = categoryForClass(name, emptyList())

    /** Category of a demangled function name by its own rule. */
    fun categoryForFunctionName(name: String): String? {
        for (rule in rules) {
            if (rule.functions.any { it.containsMatchIn(name) }) return rule.id
        }
        return null
    }

    /** Category of a demangled name by its namespace. */
    fun categoryForNamespace(name: String): String? {
        val namespace = name.substringBefore("::")
        for (rule in rules) {
            if (rule.namespaces.any { namespace == it || namespace.startsWith(it) }) {
                return rule.id
            }
        }
        return null
    }

    /**
     * Category of a function.
     *
     * @param name demangled function name.
     * @param classCategory category already resolved for the owning class, or
     *   `null` when the function has no class.
     * @return exactly one category id.
     */
    fun categoryForFunction(name: String, classCategory: String?): String {
        if (classCategory != null) return classCategory
        categoryForFunctionName(name)?.let { return it }
        categoryForNamespace(name)?.let { return it }
        return if (isUnnamed(name)) CategoryCatalog.UNNAMED else CategoryCatalog.OTHER_NAMED
    }

    /** Whether a name is the engine's `sub_<hex>` placeholder. */
    fun isUnnamed(name: String): Boolean = name.startsWith("sub_")
}
