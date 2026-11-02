package io.github.danisec2601.idelink.python

import kotlin.test.Test
import kotlin.test.assertEquals

class DocstringTextTest {
    @Test fun docstringTextStripsQuotesAndIndent() {
        assertEquals("Doc.", docstringText("\"\"\"Doc.\"\"\""))
        assertEquals("Doc.", docstringText("r'''Doc.'''"))
        val indented = "\"\"\"Summary.\n\n    Detail\n      more\n    \"\"\""
        assertEquals("Summary.\n\nDetail\n  more", docstringText(indented))
        assertEquals("one", docstringText("'one'"))
        assertEquals("unterminated", docstringText("\"\"\"unterminated"))
    }
}
