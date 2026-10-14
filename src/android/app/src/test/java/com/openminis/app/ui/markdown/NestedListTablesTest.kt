package com.openminis.app.ui.markdown

import com.openminis.app.ui.chat.debugParseBlockRaws
import com.openminis.app.ui.chat.debugParseIncrementalRaws
import org.junit.Assert.*
import org.junit.Test

/** Exercises the block parser used by both live and frozen chat markdown. */
class NestedListTablesTest {
    private fun blocks(source: String): List<Any> {
        val parser = Class.forName("com.openminis.app.ui.chat.StreamingMarkdownTextKt")
            .getDeclaredMethod("parseMarkdownBlocksBlocking", String::class.java)
        parser.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return parser.invoke(null, source) as List<Any>
    }

    private fun field(node: Any, name: String): Any {
        val field = node.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(node)
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodes(node: Any, name: String): List<Any> = field(node, name) as List<Any>

    private val table = "| Name | Count |\n| :--- | ---: |\n| apples | 2 |"

    @Test
    fun `bullet item keeps its table as a child and its sibling separate`() {
        val source = "- inventory\n\n${table.prependIndent("  ")}\n\n- next"
        val list = blocks(source).single()
        assertEquals("UnorderedList", list.javaClass.simpleName)
        val items = nodes(list, "items")
        assertEquals(2, items.size)
        val children = nodes(items.first(), "children")
        assertEquals(listOf("Paragraph", "Table"), children.map { it.javaClass.simpleName })
        val nested = children.last()
        assertEquals(listOf("Name", "Count"), field(nested, "headers"))
        assertEquals(listOf(listOf("apples", "2")), field(nested, "rows"))
        assertEquals(listOf("START", "END"), nodes(nested, "alignments").map { it.toString() })
        assertEquals("next", field(items.last(), "text"))
        assertEquals(listOf(source), debugParseBlockRaws(source))
    }

    @Test
    fun `numbered item supports a table followed by continuation prose`() {
        val source = "12. inventory\n${table.prependIndent("    ")}\n\n    more details\n13. next"
        val list = blocks(source).single()
        assertEquals("OrderedList", list.javaClass.simpleName)
        assertEquals(12, field(list, "startNum"))
        val children = nodes(nodes(list, "items").first(), "children")
        assertEquals(listOf("Paragraph", "Table", "Paragraph"), children.map { it.javaClass.simpleName })
        assertEquals(2, nodes(list, "items").size)
    }

    @Test
    fun `task item consumes its table without moving it outside the checkbox`() {
        val source = "- [x] inventory\n\n${table.prependIndent("      ")}\n- [ ] next"
        val list = blocks(source).single()
        assertEquals("TaskList", list.javaClass.simpleName)
        val items = nodes(list, "items")
        assertEquals(2, items.size)
        assertEquals(true, field(items.first(), "checked"))
        assertEquals(false, field(items.last(), "checked"))
        assertEquals(listOf("Paragraph", "Table"), nodes(items.first(), "children").map { it.javaClass.simpleName })
    }

    @Test
    fun `table may start on the list marker line`() {
        val source = "- ${table.replace("\n", "\n  ")}\n- next"
        val list = blocks(source).single()
        assertEquals("UnorderedList", list.javaClass.simpleName)
        val nested = nodes(nodes(list, "items").first(), "children").single()
        assertEquals("Table", nested.javaClass.simpleName)
        assertEquals(listOf("Name", "Count"), field(nested, "headers"))
    }

    @Test
    fun `nested list keeps the table under the inner item`() {
        val source = "- outer\n  - inner\n\n${table.prependIndent("    ")}\n  - inner sibling\n- outer sibling"
        val outer = blocks(source).single()
        val inner = nodes(nodes(outer, "items").first(), "children").last()
        assertEquals("UnorderedList", inner.javaClass.simpleName)
        val innerItems = nodes(inner, "items")
        assertEquals(2, innerItems.size)
        assertEquals("Table", nodes(innerItems.first(), "children").last().javaClass.simpleName)
        assertEquals(2, nodes(outer, "items").size)
    }

    @Test
    fun `streaming blank lines do not freeze away an incomplete nested table`() {
        val source = "intro\n\n- inventory\n\n${table.prependIndent("  ")}\n\n- next\n\nfinished\n"
        val chunks = source.indices.map { source.substring(0, it + 1) }
        assertEquals(debugParseBlockRaws(source), debugParseIncrementalRaws(chunks))
        assertTrue(debugParseIncrementalRaws(chunks).any { it.contains("| apples | 2 |") })
    }

    @Test
    fun `fenced pipes stay code and wide list math stays a display block`() {
        val source = "- inventory\n  ```text\n${table.prependIndent("  ")}\n  ```\n- total ${'$'}\\frac{a}{b}${'$'}"
        val list = blocks(source).single()
        val items = nodes(list, "items")
        assertEquals(listOf("Paragraph", "CodeBlock"), nodes(items.first(), "children").map { it.javaClass.simpleName })
        val code = nodes(items.first(), "children").last()
        assertEquals(table, field(code, "code"))
        assertTrue(nodes(items.last(), "children").any { it.javaClass.simpleName == "MathDisplay" })
    }

    @Test(timeout = 2_000)
    fun `deep list content obeys the existing nesting limit and keeps raw source`() {
        val source = (0 until MarkdownWorkLimits.MAX_NESTING + 8).joinToString("\n") {
            "  ".repeat(it) + "- level $it"
        }
        var node = blocks(source).single()
        var depth = 0
        while (node.javaClass.simpleName == "UnorderedList") {
            depth++
            node = nodes(nodes(node, "items").single(), "children").last()
        }
        assertEquals(MarkdownWorkLimits.MAX_NESTING, depth)
        assertEquals("PlainText", node.javaClass.simpleName)
        assertEquals(listOf(source), debugParseBlockRaws(source))
    }
}
