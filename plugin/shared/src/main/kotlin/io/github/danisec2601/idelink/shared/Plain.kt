package io.github.danisec2601.idelink.shared

/**
 * Makes Claude-supplied text safe in any Swing label, title or tooltip: with no '<' left, BasicHTML can't render it
 * as HTML, and invisible characters show as ⟦U+XXXX⟧. Swing text only, and only where called (docs/security.md).
 */
object Plain {
    /** [s] cut to about [max] characters (marked "…"), with newlines and tabs as spaces unless [multiline]. */
    fun text(s: String, max: Int, multiline: Boolean = false): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length && out.length < max) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '<'.code -> out.append('‹')
                cp == '>'.code -> out.append('›')
                cp == '\r'.code -> {}
                cp == '\n'.code || cp == '\t'.code -> out.append(if (multiline) cp.toChar() else ' ')
                Invisible.isSuspect(cp) -> out.append("⟦U+%04X⟧".format(cp))
                else -> out.appendCodePoint(cp)
            }
        }
        if (i < s.length) out.append('…')
        return out.toString()
    }
}

/**
 * Finds characters that make displayed text differ from what it means: Cf format, controls, separators, variation
 * selectors, blank-looking letters. HEURISTIC: misses homoglyphs, Zs whitespace (U+00A0) and other combining marks.
 */
object Invisible {
    /** A suspect [codePoint] at 1-based [line] and [col]. */
    data class Hit(
        /** 1-based. */
        val line: Int,
        /** 1-based, in code points. */
        val col: Int,
        /** The suspect character. */
        val codePoint: Int,
    ) {
        override fun toString() = "$line:$col U+%04X %s".format(codePoint, Character.getName(codePoint) ?: "?")
    }

    private val blankLetters = setOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, 0x180E)

    /** Whether [cp] is one of the characters described above. */
    fun isSuspect(cp: Int): Boolean {
        val t = Character.getType(cp)
        return when {
            cp == '\t'.code || cp == '\n'.code || cp == '\r'.code -> false
            t == Character.FORMAT.toInt() || t == Character.CONTROL.toInt() -> true
            t == Character.LINE_SEPARATOR.toInt() || t == Character.PARAGRAPH_SEPARATOR.toInt() -> true
            cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF -> true
            else -> cp in blankLetters
        }
    }

    /** The first [limit] suspect characters in [s]. */
    fun scan(s: String, limit: Int = 50): List<Hit> {
        val hits = mutableListOf<Hit>()
        var line = 1
        var col = 1
        var i = 0
        while (i < s.length && hits.size < limit) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (isSuspect(cp)) hits += Hit(line, col, cp)
            if (cp == '\n'.code) { line++; col = 1 } else col++
        }
        return hits
    }

    /** How many suspect characters [s] holds. */
    fun count(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) { val cp = s.codePointAt(i); i += Character.charCount(cp); if (isSuspect(cp)) n++ }
        return n
    }
}
