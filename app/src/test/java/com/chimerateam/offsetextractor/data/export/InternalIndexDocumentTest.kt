package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.data.native.ShardPart
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the offset search reads the documents a real scan produces.
 *
 * The engine classifies as it writes, so a real scan has no NDJSON table at all
 * — only one document per category. The index used to open `summary.path`,
 * which is blank on that path, so the search returned nothing for every query
 * and the 883k internal functions were unreachable from the UI.
 */
class InternalIndexDocumentTest {

    private fun document(dir: File, id: String, body: String): File {
        val file = File(dir, "$id.json")
        file.writeText(body)
        return file
    }

    private val header =
        """{"schemaVersion":1,"generatedBy":"BedrockOffsetExtractor",""" +
            """"kind":"internal_cpp_category","category":"block_placement",""" +
            """"label":"Block placement","part":1,"version":"26.60","""

    @Test
    fun finds_a_function_inside_a_category_document() {
        val dir = createTempDirectory("index").toFile()
        val file = document(
            dir,
            "block_placement",
            header + """"classes":{},"functions":[""" +
                """{"address":"0x107b0ac4","fileOffset":"0x107b0ac4","size":592,""" +
                """"name":"BlockItem::useOn","nameSource":"symbol","aliases":[],""" +
                """"kind":"virtual","prologueKind":"frame_push","status":"verified"},""" +
                """{"address":"0x107b0b00","fileOffset":"0x107b0b00","size":40,""" +
                """"name":"sub_107b0b00","nameSource":"auto","aliases":[],""" +
                """"kind":"function","prologueKind":"none","status":"fde_only"}],""" +
                """"symbols":[]}"""
        )
        val index = InternalIndex(
            InternalSummary(path = dir.absolutePath, shards = listOf(part(file)))
        )

        assertTrue(index.isAvailable())
        val rows = index.search("BlockItem::useOn")
        assertEquals(1, rows.size)
        assertEquals("BlockItem::useOn", rows[0].name)
        assertEquals("0x107b0ac4", rows[0].addressHex)
    }

    @Test
    fun finds_a_slot_selector_inside_a_category_document() {
        val dir = createTempDirectory("index").toFile()
        val file = document(
            dir,
            "actor_entity",
            header + """"classes":{"Actor":{"name":"Actor","mangled":"5Actor",""" +
                """"bases":[],"vtable":"0x13da7000","vtableOffset":"0x13b61000",""" +
                """"typeInfo":"0x13dab000","slotCount":2,"slots":[""" +
                """{"slot":0,"offset":"0x0","address":"0x1000","fileOffset":"0x1000",""" +
                """"size":8,"name":"Actor::vfunc_0","kind":"virtual",""" +
                """"prologueKind":"none","overrides":false,"inherited":true,""" +
                """"status":"verified"},""" +
                """{"slot":1,"offset":"0x8","address":"0x1010","fileOffset":"0x1010",""" +
                """"size":8,"name":"Actor::tick","kind":"virtual",""" +
                """"prologueKind":"frame_push","overrides":true,"inherited":false,""" +
                """"status":"verified"}]}},"functions":[],"symbols":[]}"""
        )
        val index = InternalIndex(
            InternalSummary(path = dir.absolutePath, shards = listOf(part(file)))
        )

        val rows = index.search("Actor[1]")
        assertEquals(1, rows.size)
        assertEquals("Actor::tick", rows[0].name)
        assertEquals("slot 1 · virtual", rows[0].detail)
    }

    @Test
    fun finds_a_data_symbol_inside_a_category_document() {
        val dir = createTempDirectory("index").toFile()
        val file = document(
            dir,
            "world_level",
            header + """"classes":{},"functions":[],"symbols":[""" +
                """{"address":"0x1a2b3c4","fileOffset":"0x1a2b3c4","size":8,""" +
                """"name":"g_levelSeed","mangled":"g_levelSeed","section":".data",""" +
                """"status":"verified"}]}"""
        )
        val index = InternalIndex(
            InternalSummary(path = dir.absolutePath, shards = listOf(part(file)))
        )

        val rows = index.search("g_levelSeed")
        assertEquals(1, rows.size)
        assertEquals("g_levelSeed", rows[0].name)
        assertEquals("data · .data", rows[0].detail)
    }

    @Test
    fun an_empty_summary_has_nothing_to_search() {
        val index = InternalIndex(InternalSummary())
        assertFalse(index.isAvailable())
        assertTrue(index.search("Player").isEmpty())
    }

    @Test
    fun every_document_is_searched_not_only_the_first() {
        val dir = createTempDirectory("index").toFile()
        val first = document(dir, "a", header + """"classes":{},"functions":[],"symbols":[]}""")
        val second = document(
            dir,
            "b",
            header + """"classes":{},"functions":[""" +
                """{"address":"0x2000","fileOffset":"0x2000","size":8,""" +
                """"name":"Player::tick","nameSource":"symbol","aliases":[],""" +
                """"kind":"function","prologueKind":"none","status":"verified"}],""" +
                """"symbols":[]}"""
        )
        val index = InternalIndex(
            InternalSummary(
                path = dir.absolutePath,
                shards = listOf(part(first), part(second))
            )
        )

        assertEquals(1, index.search("Player::tick").size)
        assertEquals(2, index.documentCount())
    }

    private fun part(file: File) = ShardPart(
        id = file.nameWithoutExtension,
        path = file.absolutePath,
        bytes = file.length()
    )
}

/**
 * Paging behaviour of the internal search.
 *
 * A hard 200-row cutoff used to make the rest of a broad query unreachable.
 * These tests pin the offset arithmetic the "Load more" button relies on.
 */
class InternalIndexPagingTest {

    private fun indexWith(count: Int): InternalIndex {
        val dir = createTempDirectory("index-paging").toFile()
        val functions = (0 until count).joinToString(",") { number ->
            """{"address":"0x${(0x1000 + number * 4).toString(16)}",""" +
                """"fileOffset":"0x0","size":4,"name":"Function$number",""" +
                """"nameSource":"auto","aliases":[],"kind":"function",""" +
                """"prologueKind":"none","status":"fde_only"}"""
        }
        val file = File(dir, "functions.json")
        file.writeText(
            """{"schemaVersion":1,"generatedBy":"BedrockOffsetExtractor",""" +
                """"kind":"internal_cpp_category","category":"functions",""" +
                """"label":"Functions","part":1,"version":"26.60",""" +
                """"classes":{},"functions":[$functions],"symbols":[]}"""
        )
        return InternalIndex(
            InternalSummary(
                path = dir.absolutePath,
                shards = listOf(
                    ShardPart(
                        id = "functions",
                        label = "Functions",
                        part = 1,
                        path = file.absolutePath,
                        bytes = file.length(),
                        functions = count
                    )
                )
            )
        )
    }

    @Test
    fun theFirstPageStopsAtThePageSizeAndReportsMore() {
        val page = indexWith(5).searchPage("Function", offset = 0, pageSize = 2)

        assertEquals(2, page.rows.size)
        assertTrue(page.hasMore)
        assertEquals("Function0", page.rows[0].name)
        assertEquals("Function1", page.rows[1].name)
    }

    @Test
    fun theOffsetSkipsRowsAlreadyShown() {
        val page = indexWith(5).searchPage("Function", offset = 2, pageSize = 2)

        assertEquals(2, page.rows.size)
        assertTrue(page.hasMore)
        assertEquals("Function2", page.rows[0].name)
        assertEquals("Function3", page.rows[1].name)
    }

    @Test
    fun theLastPageReportsNoMore() {
        val page = indexWith(5).searchPage("Function", offset = 4, pageSize = 2)

        assertEquals(1, page.rows.size)
        assertFalse(page.hasMore)
        assertEquals("Function4", page.rows[0].name)
    }
}
