package com.gemmory.vault

import com.gemmory.vault.parser.MarkdownFrontmatterParser
import com.gemmory.vault.parser.WikiLinkParser
import com.gemmory.vault.storage.MarkdownVaultStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParsersTest {

    @Test
    fun parsesFrontmatterListsAndBody() {
        val parsed = MarkdownFrontmatterParser.parse(
            """
            ---
            id: "abc"
            title: "Retrieval-Augmented Generation"
            tags:
              - ai
              - retrieval
            aliases:
              - RAG
            ---

            # Retrieval-Augmented Generation
            """.trimIndent(),
        )

        assertEquals(listOf("abc"), parsed.fields["id"])
        assertEquals(listOf("ai", "retrieval"), parsed.fields["tags"])
        assertEquals(listOf("RAG"), parsed.fields["aliases"])
        assertTrue(parsed.body.contains("# Retrieval-Augmented Generation"))
    }

    @Test
    fun parsesWikiLinksWithLabelsAndOffsets() {
        val markdown = "Related to [[Vector Databases]] and [[RAG|retrieval augmented generation]]."

        val links = WikiLinkParser.parse(markdown)

        assertEquals(2, links.size)
        assertEquals("Vector Databases", links[0].rawTarget)
        assertEquals(null, links[0].label)
        assertEquals("RAG", links[1].rawTarget)
        assertEquals("retrieval augmented generation", links[1].label)
        assertEquals("[[Vector Databases]]", markdown.substring(links[0].start, links[0].end))
    }

    @Test
    fun rejectsUnsafeVaultPaths() {
        assertTrue(MarkdownVaultStorage.isSafePath("concepts/RAG.md"))
        assertFalse(MarkdownVaultStorage.isSafePath("../secrets.md"))
        assertFalse(MarkdownVaultStorage.isSafePath("/absolute.md"))
        assertFalse(MarkdownVaultStorage.isSafePath("concepts/not-markdown.txt"))
        assertFalse(MarkdownVaultStorage.isSafePath("concepts//empty.md"))
    }
}
