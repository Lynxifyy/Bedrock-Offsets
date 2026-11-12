package com.chimerateam.offsetextractor.data.patterns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the bundled signature pack loader.
 *
 * The loader is the only place a malformed pack could reach the engine, so it
 * must degrade to an empty list rather than throw.
 */
class SignaturePackTest {

    @Test
    fun aWellFormedPackIsParsed() {
        val json = """
            {
              "patterns": [
                {"name": "frame_push", "pattern": "FD 7B BF A9"},
                {"name": "pac_prologue", "pattern": "7F 23 03 D5 FD 7B BF A9"}
              ]
            }
        """

        val patterns = SignaturePack.parse(json)

        assertEquals(2, patterns.size)
        assertEquals("frame_push", patterns[0].name)
        assertEquals("FD 7B BF A9", patterns[0].pattern)
        assertEquals("pac_prologue", patterns[1].name)
    }

    @Test
    fun aWildcardPatternIsPreserved() {
        val json = """{"patterns":[{"name":"w","pattern":"48 8B ?? ?? 89"}]}"""

        val patterns = SignaturePack.parse(json)

        assertEquals(1, patterns.size)
        assertEquals("48 8B ?? ?? 89", patterns[0].pattern)
    }

    @Test
    fun aMalformedDocumentYieldsNoPatterns() {
        assertTrue(SignaturePack.parse("{not json").isEmpty())
    }

    @Test
    fun aDocumentWithoutAPatternArrayYieldsNoPatterns() {
        assertTrue(SignaturePack.parse("{}").isEmpty())
        assertTrue(SignaturePack.parse("""{"other":[]}""").isEmpty())
    }

    @Test
    fun incompleteEntriesAreDropped() {
        val json = """
            {
              "patterns": [
                {"name": "", "pattern": "FD 7B BF A9"},
                {"name": "good", "pattern": "FD 7B BF A9"},
                {"name": "blank", "pattern": "   "}
              ]
            }
        """

        val patterns = SignaturePack.parse(json)

        assertEquals(1, patterns.size)
        assertEquals("good", patterns[0].name)
    }

    @Test
    fun theBundledPackIsReadableAndNonEmpty() {
        // The pack shipped in src/main/assets must survive the parser; a typo
        // there would silently disable every signature.
        val asset = java.io.File("src/main/assets/patterns.json")
        assertTrue("missing ${asset.absolutePath}", asset.isFile())

        val patterns = SignaturePack.parse(asset.readText())

        assertTrue("the bundled pack declared no patterns", patterns.isNotEmpty())
        assertTrue(
            "every bundled pattern needs a name and bytes",
            patterns.all { it.name.isNotBlank() && it.pattern.isNotBlank() }
        )
    }

    @Test
    fun everyBundledSignatureIsAJniSymbol() {
        // Each name has to be an exported JNI entry point: that is what lets the
        // engine check the offset against the library's own symbol table and
        // report it as verified instead of guessed.
        val asset = java.io.File("src/main/assets/patterns.json")
        val patterns = SignaturePack.parse(asset.readText())

        assertTrue(
            "every signature must be named after a Java_ symbol",
            patterns.all { it.name.startsWith("Java_") }
        )
        assertTrue(
            "a signature name must be a plain identifier so it can be a JSON key",
            patterns.all { name -> name.name.all { it.isLetterOrDigit() || it == '_' } }
        )
    }

    @Test
    fun noTwoBundledSignaturesShareAPattern() {
        // A duplicate would make one entry unreachable: the engine reports a
        // match under the first name that compiled it.
        val asset = java.io.File("src/main/assets/patterns.json")
        val patterns = SignaturePack.parse(asset.readText())

        val distinct = patterns.map { it.pattern }.toSet()
        assertEquals(patterns.size, distinct.size)
    }
}
