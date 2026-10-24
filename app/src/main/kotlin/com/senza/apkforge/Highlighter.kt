package com.senza.apkforge

import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/** Syntax highlight sederhana (satu kali lewat regex, tanpa span yang saling tumpang). */
object Highlighter {

    val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "var", "record"
    )

    private class Hl(color: Int) : ForegroundColorSpan(color)

    // grup: 1 komentar, 2 string, 3 anotasi, 4 angka, 5 kata kunci
    private val javaRe = Regex(
        """(//[^\n]*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(@\w+)|\b(\d+(?:\.\d+)?[fFlLdD]?)\b|\b(""" +
            JAVA_KEYWORDS.joinToString("|") + """)\b"""
    )
    private val javaColors = intArrayOf(0xFF7F848E.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(), 0xFFD19A66.toInt(), 0xFFC678DD.toInt())

    // grup: 1 komentar, 2 nilai atribut, 3 nama tag, 4 nama atribut
    private val xmlRe = Regex("""(<!--[\s\S]*?-->)|("[^"]*")|(</?[\w.:-]+)|([\w.:-]+)(?==)""")
    private val xmlColors = intArrayOf(0xFF7F848E.toInt(), 0xFF98C379.toInt(), 0xFFE06C75.toInt(), 0xFFD19A66.toInt())

    fun apply(e: Editable, isXml: Boolean) {
        if (e.length > 80_000) return // file besar: lewati supaya tidak lag
        e.getSpans(0, e.length, Hl::class.java).forEach { e.removeSpan(it) }
        val re = if (isXml) xmlRe else javaRe
        val colors = if (isXml) xmlColors else javaColors
        for (m in re.findAll(e.toString())) {
            val i = (1 until m.groups.size).firstOrNull { m.groups[it] != null } ?: continue
            val r = m.groups[i]!!.range
            e.setSpan(Hl(colors[i - 1]), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
