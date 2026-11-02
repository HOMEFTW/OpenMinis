package com.openminis.app.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DollarMathRulesTest {
    @Test fun amountsAndShellVariablesStayText() {
        val text = paragraph("The range is \$5-\$10 and PATH=\$HOME/bin:\$PATH.")
        assertFalse(text.contains('\uFFFC'))
        assertTrue(text.contains("\$5"))
        assertTrue(text.contains("\$HOME"))
        assertTrue(text.contains("\$PATH"))
    }

    @Test fun realFormulaStillExtracts() {
        val parsed = MarkdownParser.parseWithMath("Energy is \$E = mc^2\$ today.")
        assertTrue(parsed.mathSpans.isNotEmpty())
    }

    private fun paragraph(markdown: String): String {
        val parsed = MarkdownParser.parseWithMath(markdown)
        val block = parsed.blocks.filterIsInstance<MarkdownParser.Block.Paragraph>().single()
        return block.content
    }
}
