package com.chimerateam.offsetextractor.data.export

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Serialises the loaded category rules for the engine.
 *
 * The engine classifies every record as it writes it, so it needs the same
 * rules the UI uses. The document shape matches `assets/categories.json`, which
 * is what [CategoryCatalog] parses, so the two implementations see identical
 * input.
 */
object CategoryRulesJson {

    /**
     * Encodes [rules] as `{"categories":[...]}`.
     *
     * @param rules ordered classification rules.
     * @return the JSON document the engine parses.
     */
    fun encode(rules: List<CategoryRule>): String {
        val document = JsonObject()
        val categories = JsonArray()
        for (rule in rules) {
            val entry = JsonObject()
            entry.addProperty("category", rule.id)
            entry.addProperty("label", rule.label)
            val classes = JsonArray()
            rule.classes.forEach { classes.add(it.pattern) }
            entry.add("classes", classes)
            val functions = JsonArray()
            rule.functions.forEach { functions.add(it.pattern) }
            entry.add("functions", functions)
            val namespaces = JsonArray()
            rule.namespaces.forEach { namespaces.add(it) }
            entry.add("namespaces", namespaces)
            entry.addProperty("includeBases", rule.includeBases)
            categories.add(entry)
        }
        document.add("categories", categories)
        return document.toString()
    }

    /**
     * Builds the top-level fields every category document carries.
     *
     * @param version single version label, dots kept.
     * @param apkName name of the scanned APK.
     * @param sha256 hash of the extracted library.
     * @param architecture ABI the library was built for.
     * @param buildId build identifier from the `.note.gnu.build-id` section.
     * @return a serialised JSON fragment without the surrounding braces.
     */
    fun sourceHeader(
        version: String,
        apkName: String,
        sha256: String,
        architecture: String,
        buildId: String
    ): String {
        val source = JsonObject()
        source.addProperty("apk", apkName)
        source.addProperty("sha256", sha256)
        source.addProperty("architecture", architecture)
        source.addProperty("buildId", buildId)
        val header = JsonObject()
        header.addProperty("version", version)
        header.add("source", source)
        // Strip the braces so the engine can append the fields to its own
        // object literal.
        return header.toString().removePrefix("{").removeSuffix("}")
    }
}
