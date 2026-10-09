package com.openminis.app.ui.markdown

import org.junit.Assert.*
import org.junit.Test

class MarkdownParserMemoryTest {
    @Test
    fun `ordinary block syntax and math keep their behavior`() {
        val blocks = MarkdownParser.parse("# Title\n\n- [x] done\n- next\n\n2. two\n3. three\n\n> quote\n\n***")
        assertEquals(MarkdownParser.Block.Heading(1, "Title"), blocks[0])
        val bullets = blocks[1] as MarkdownParser.Block.BulletList
        assertEquals(listOf(MarkdownParser.ListItem("done", true), MarkdownParser.ListItem("next")), bullets.items)
        assertEquals(2, (blocks[2] as MarkdownParser.Block.NumberedList).startNumber)
        assertTrue(blocks[3] is MarkdownParser.Block.Blockquote)
        assertEquals(MarkdownParser.Block.ThematicBreak, blocks[4])
        assertEquals("x^2", MarkdownParser.parseWithMath("${'$'}x^2${'$'}").mathSpans.single().latex)
    }

    @Test(timeout = 2_000)
    fun `long thematic rule has no recursive regex matcher`() {
        assertEquals(listOf(MarkdownParser.Block.ThematicBreak), MarkdownParser.parse("-".repeat(8_000)))
        assertEquals(listOf(MarkdownParser.Block.ThematicBreak), MarkdownParser.parse("  * \t* *  "))
        assertTrue(MarkdownParser.parse("- * -").single() is MarkdownParser.Block.BulletList)
        assertTrue(MarkdownParser.parse("    ---").single() is MarkdownParser.Block.Paragraph)
    }

    @Test(timeout = 2_000)
    fun `huge malformed input skips matching and preserves the complete source`() {
        val text = "![" + "[".repeat(2_000_000) + "tail"
        assertEquals(listOf(MarkdownParser.Block.Paragraph(text)), MarkdownParser.parse(text))
        val result = MarkdownParser.parseWithMath(text)
        assertEquals(listOf(MarkdownParser.Block.Paragraph(text)), result.blocks)
        assertTrue(result.mathSpans.isEmpty())
    }

    @Test(timeout = 2_000)
    fun `deep blockquotes stop recursion while keeping the remaining source`() {
        val source = "> ".repeat(1_000) + "tail"
        var block = MarkdownParser.parse(source).single()
        var depth = 0
        while (block is MarkdownParser.Block.Blockquote) {
            depth++
            block = block.blocks.single()
        }
        assertEquals(MarkdownWorkLimits.MAX_NESTING, depth)
        assertEquals("> ".repeat(1_000 - depth) + "tail", (block as MarkdownParser.Block.Paragraph).content)
    }
}
