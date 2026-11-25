package com.chimerateam.offsetextractor.data.export

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the NDJSON split into one file per category. */
class InternalTableSplitterTest {

    private val rules = listOf(
        CategoryRule(
            id = "actor_entity",
            label = "Actors",
            classes = listOf(Regex("^(Actor|Mob|Player)$")),
            functions = emptyList(),
            namespaces = emptyList(),
            includeBases = true
        ),
        CategoryRule(
            id = "client_game",
            label = "Client",
            classes = listOf(Regex("^(ClientInstance)$")),
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

    private fun table(lines: List<String>): File {
        val file = File(createTempDirectory().toFile(), "table.ndjson")
        file.writeText(lines.joinToString("\n"))
        return file
    }

    private fun classLine(name: String, bases: List<String>): String {
        val baseJson = bases.joinToString(",") { "\"$it\"" }
        return """{"type":"class","class":{"name":"$name","mangled":"$name",""" +
            """"bases":[$baseJson],"vtable":"0x0","vtableOffset":"0x0",""" +
            """"typeInfo":"0x0","slotCount":0,"slots":[]}}"""
    }

    private fun functionLine(name: String): String =
        """{"type":"function","function":{"address":"0x1000","fileOffset":"0x0",""" +
            """"size":4,"name":"$name","nameSource":"vtable","aliases":[],""" +
            """"kind":"function","status":"verified","prologueKind":"none"}}"""

    private fun symbolLine(name: String): String =
        """{"type":"symbol","symbol":{"address":"0x2000","size":8,"name":"$name",""" +
            """"mangled":"$name","kind":"data","reason":"object_symbol"}}"""

    @Test
    fun everyFunctionIsAssignedToExactlyOneFile() {
        val table = table(
            listOf(
                """{"type":"meta","schemaVersion":1,"kind":"internal_cpp","stats":{}}""",
                classLine("Player", listOf("Mob", "Actor")),
                classLine("ClientInstance", emptyList()),
                functionLine("Player::getPos"),
                functionLine("ClientInstance::tick"),
                functionLine("sub_1000"),
                symbolLine("_binary_classes_dex_size")
            )
        )
        val work = createTempDirectory().toFile()

        val result = InternalTableSplitter(Classifier(rules), work).split(table)

        assertEquals(3, result.totalFunctions)
        assertTrue(result.consistent)
        assertEquals(3, result.assignedFunctions)
        val actor = result.parts.first { it.id == "actor_entity" }
        assertEquals(1, actor.counts.classes)
        assertEquals(1, actor.counts.functions)
        val client = result.parts.first { it.id == "client_game" }
        assertEquals(1, client.counts.functions)
        val unnamed = result.parts.first { it.id == "unnamed_part_01" }
        assertEquals(1, unnamed.counts.functions)
        val other = result.parts.first { it.id == "other_named" }
        assertEquals(1, other.counts.symbols)

        // No function row is written twice.
        val functionLines = result.parts
            .flatMap { it.functionFiles }
            .sumOf { file -> file.readLines().size }
        assertEquals(3, functionLines)
    }

    @Test
    fun unnamedFunctionsAreChunkedIntoParts() {
        val table = table(
            (0 until 5).map { functionLine("sub_${1000 + it}") }
        )
        val work = createTempDirectory().toFile()

        val result = InternalTableSplitter(
            classifier = Classifier(rules),
            workDir = work,
            partSize = 2
        ).split(table)

        val parts = result.parts.filter { it.categoryId == "unnamed" }
        assertEquals(3, parts.size)
        assertEquals(listOf(2, 2, 1), parts.map { it.counts.functions })
        assertTrue(parts.all { it.part > 0 })
        assertEquals(5, result.assignedFunctions)
    }

    @Test
    fun aClassWithNoFunctionsStillGetsAClassFile() {
        val table = table(listOf(classLine("Player", emptyList())))
        val work = createTempDirectory().toFile()

        val result = InternalTableSplitter(Classifier(rules), work).split(table)

        val actor = result.parts.first { it.id == "actor_entity" }
        assertEquals(1, actor.counts.classes)
        assertTrue(actor.classFile!!.isFile)
        assertTrue(actor.functionFiles.isEmpty())
    }
}
