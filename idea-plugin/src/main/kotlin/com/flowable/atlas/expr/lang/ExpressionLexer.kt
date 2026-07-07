package com.flowable.atlas.expr.lang

/** The token kinds the expression tokenizer emits. Deliberately coarse — enough for validation
 *  and caret-context classification, not a full grammar. */
enum class TokType {
    IDENT,        // a name: foo, $item, authenticatedUserId, JSON
    NUMBER,       // 12, 3.14
    STRING,       // 'x' or "x" (terminated)
    STRING_BAD,   // an unterminated string literal
    DOT,          // .
    COLON,        // :
    COMMA,        // ,
    LPAREN, RPAREN,
    LBRACKET, RBRACKET,
    PIPE,         // |>  (frontend pipe operator)
    ARROW,        // ->  (backend JUEL lambda)  or  =>  (frontend arrow)
    OP,           // any other operator: + - * / % == != <= >= < > ! ? = && || === !==
    BAD,          // an unrecognized character
}

/** One lexical token with absolute offsets into the tokenized text. */
data class Tok(val type: TokType, val start: Int, val end: Int, val text: String)

/**
 * A tiny, dependency-free tokenizer for the Flowable expression *body* (the text inside `${…}` /
 * `{{…}}` — the delimiters themselves are handled by the injector, not here).
 *
 * Whitespace is skipped (offsets are preserved on the emitted tokens), so callers iterate a clean
 * token stream. Used by [com.flowable.atlas.expr.ExpressionValidator] and
 * [com.flowable.atlas.expr.ExpressionContext]; the IntelliJ [FlowableExprParserDefinition] uses a
 * separate single-leaf lexer.
 */
object ExpressionLexer {

    fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++

                c == '\'' || c == '"' -> {
                    val start = i
                    i++
                    var terminated = false
                    while (i < n) {
                        val ch = text[i]
                        if (ch == '\\' && i + 1 < n) { i += 2; continue }
                        if (ch == c) { i++; terminated = true; break }
                        if (ch == '\n') break // strings don't span lines in these DSLs
                        i++
                    }
                    out += Tok(if (terminated) TokType.STRING else TokType.STRING_BAD, start, i, text.substring(start, i))
                }

                isIdentStart(c) -> {
                    val start = i
                    i++
                    while (i < n && isIdentPart(text[i])) i++
                    out += Tok(TokType.IDENT, start, i, text.substring(start, i))
                }

                c.isDigit() -> {
                    val start = i
                    i++
                    while (i < n && (text[i].isDigit() || text[i] == '.')) i++
                    out += Tok(TokType.NUMBER, start, i, text.substring(start, i))
                }

                else -> {
                    val three = if (i + 2 < n) text.substring(i, i + 3) else ""
                    val two = if (i + 1 < n) text.substring(i, i + 2) else ""
                    when {
                        three in THREE_CHAR_OPS -> { out += Tok(TokType.OP, i, i + 3, three); i += 3 }
                        two == "|>" -> { out += Tok(TokType.PIPE, i, i + 2, two); i += 2 }
                        two == "->" || two == "=>" -> { out += Tok(TokType.ARROW, i, i + 2, two); i += 2 }
                        two in TWO_CHAR_OPS -> { out += Tok(TokType.OP, i, i + 2, two); i += 2 }
                        c == '.' -> { out += Tok(TokType.DOT, i, i + 1, "."); i++ }
                        c == ':' -> { out += Tok(TokType.COLON, i, i + 1, ":"); i++ }
                        c == ',' -> { out += Tok(TokType.COMMA, i, i + 1, ","); i++ }
                        c == '(' -> { out += Tok(TokType.LPAREN, i, i + 1, "("); i++ }
                        c == ')' -> { out += Tok(TokType.RPAREN, i, i + 1, ")"); i++ }
                        c == '[' -> { out += Tok(TokType.LBRACKET, i, i + 1, "["); i++ }
                        c == ']' -> { out += Tok(TokType.RBRACKET, i, i + 1, "]"); i++ }
                        c in ONE_CHAR_OPS -> { out += Tok(TokType.OP, i, i + 1, c.toString()); i++ }
                        else -> { out += Tok(TokType.BAD, i, i + 1, c.toString()); i++ }
                    }
                }
            }
        }
        return out
    }

    private val THREE_CHAR_OPS = setOf("===", "!==")
    private val TWO_CHAR_OPS = setOf("==", "!=", "<=", ">=", "&&", "||")
    private val ONE_CHAR_OPS = "+-*/%<>!?=".toSet()

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
