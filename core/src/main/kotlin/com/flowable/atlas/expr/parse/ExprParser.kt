package com.flowable.atlas.expr.parse

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.lang.ExpressionLexer
import com.flowable.atlas.expr.lang.Tok
import com.flowable.atlas.expr.lang.TokType

/**
 * A dependency-free recursive-descent (Pratt) parser for a Flowable expression *body*, producing an
 * [ExprNode] AST or the first [ParseError]. One grammar, parameterised by [ExpressionDialect]:
 *
 *  - **Backend** (JUEL / Flowable's extended JUEL 2.2): word operators (`and or not empty div mod eq
 *    ne lt gt le ge`), namespaced calls `ns:fn(args)`, lambdas `x -> body`. Rejects `[array,literals]`,
 *    `=>`, JS-only `=== !==`.
 *  - **Frontend** (jsep 0.4.0 + Flowable extensions): symbolic operators only, array literals, the
 *    `|>` pipe, arrows `x => body` / `[a, b] => body`, `=== !==`. Rejects word operators.
 *
 * The grammar and its accept/reject boundary were verified against the real engines (JUEL via
 * flowable-engine-common; jsep 0.4.0 in @flowable/forms). This parser is the single source of truth
 * for *structural* validity; function-name and dialect-fit checks live in the validator's semantic pass.
 */
object ExprParser {

    data class ParseError(val message: String, val start: Int, val end: Int)
    data class ParseResult(val ast: ExprNode?, val error: ParseError?)

    fun parse(body: String, dialect: ExpressionDialect): ParseResult {
        val toks = ExpressionLexer.tokenize(body)
        return try {
            val p = Impl(toks, body.length, dialect)
            val ast = p.parseTop()
            ParseResult(ast, null)
        } catch (e: ParseException) {
            ParseResult(null, ParseError(e.message, e.start, e.end))
        }
    }

    private class ParseException(override val message: String, val start: Int, val end: Int) : RuntimeException(message)

    // Backend JUEL word operators → their symbolic normal form (for the AST) + precedence.
    private val WORD_BINARY = mapOf(
        "or" to "||", "and" to "&&", "eq" to "==", "ne" to "!=",
        "lt" to "<", "gt" to ">", "le" to "<=", "ge" to ">=", "div" to "/", "mod" to "%",
    )
    private val WORD_UNARY = setOf("not", "empty")
    private val WORD_LITERAL = mapOf<String, Any?>("true" to true, "false" to false, "null" to null)

    // Symbolic binary operators → precedence (higher binds tighter). `|>` sits above arithmetic.
    private val BINARY_PREC = mapOf(
        "||" to 1, "&&" to 2,
        "==" to 3, "!=" to 3, "===" to 3, "!==" to 3,
        "<" to 4, ">" to 4, "<=" to 4, ">=" to 4,
        "+" to 5, "-" to 5,
        "*" to 6, "/" to 6, "%" to 6,
        "|>" to 7,
    )
    private val UNARY_OPS = setOf("!", "-", "+")

    private class Impl(private val toks: List<Tok>, private val textLen: Int, private val dialect: ExpressionDialect) {
        private var pos = 0
        private val backend = dialect == ExpressionDialect.BACKEND
        private val arrowText = if (backend) "->" else "=>"

        private fun peek(): Tok? = toks.getOrNull(pos)
        private fun peekAt(n: Int): Tok? = toks.getOrNull(pos + n)
        private fun advance(): Tok = toks[pos++]
        private fun eofOffset(): Int = toks.lastOrNull()?.end ?: textLen

        private fun err(t: Tok?, msg: String): Nothing {
            if (t == null) throw ParseException(msg, eofOffset(), eofOffset())
            throw ParseException(msg, t.start, t.end)
        }

        private fun err(n: ExprNode, msg: String): Nothing = throw ParseException(msg, n.start, n.end)

        fun parseTop(): ExprNode {
            if (peek() == null) err(null, "Expected an expression")
            val node = parseExpression()
            val extra = peek()
            if (extra != null) {
                // JUEL rejects juxtaposed operands (`a b c`) — nothing may follow a complete expression.
                err(extra, "Unexpected '${extra.text}' — expected an operator or end of expression")
            }
            return requireNotParams(node)
        }

        /** Expression = ternary, optionally an arrow whose params are the preceding primary. */
        private fun parseExpression(): ExprNode {
            val left = parseTernary()
            val arrow = peek()
            if (arrow?.type == TokType.ARROW) {
                if (arrow.text != arrowText) err(arrow, "'${arrow.text}' is not valid here (${dialect.display} uses '$arrowText')")
                advance()
                val params = extractParams(left)
                val body = parseExpression()
                return ArrowNode(params, body, left.start, body.end)
            }
            return left
        }

        private fun parseTernary(): ExprNode {
            val cond = parseBinary(0)
            val q = peek()
            if (q?.type == TokType.OP && q.text == "?") {
                advance()
                val then = parseExpression()
                val colon = peek()
                if (colon?.type != TokType.COLON) err(colon ?: q, "Expected ':' in ternary expression")
                advance()
                val otherwise = parseExpression()
                return TernaryNode(cond, then, otherwise, cond.start, otherwise.end)
            }
            return cond
        }

        /** A binary operator at the cursor (symbolic OP, PIPE, or a backend word op) → normalised op + precedence. */
        private fun peekBinaryOp(): Pair<String, Int>? {
            val t = peek() ?: return null
            return when {
                t.type == TokType.PIPE -> "|>" to BINARY_PREC["|>"]!!
                t.type == TokType.OP && t.text in BINARY_PREC && !(backend && (t.text == "===" || t.text == "!==")) ->
                    t.text to BINARY_PREC[t.text]!!
                backend && t.type == TokType.IDENT && t.text in WORD_BINARY -> {
                    val sym = WORD_BINARY[t.text]!!; sym to BINARY_PREC[sym]!!
                }
                else -> null
            }
        }

        private fun parseBinary(minPrec: Int): ExprNode {
            var left = parseUnary()
            while (true) {
                val (op, prec) = peekBinaryOp() ?: break
                if (prec < minPrec) break
                val opTok = advance()
                val next = peek()
                if (next == null) err(opTok, "Expression ends unexpectedly after '${opTok.text}'")
                if (next.type == TokType.RPAREN || next.type == TokType.RBRACKET || next.type == TokType.COMMA) {
                    err(opTok, "'${opTok.text}' is missing its right operand")
                }
                val right = parseBinary(prec + 1)
                left = if (op == "|>") PipeNode(left, right, left.start, right.end)
                else BinaryNode(op, left, right, left.start, right.end)
            }
            return left
        }

        private fun parseUnary(): ExprNode {
            val t = peek()
            if (t != null && ((t.type == TokType.OP && t.text in UNARY_OPS) || (backend && t.type == TokType.IDENT && t.text in WORD_UNARY))) {
                advance()
                val operand = parseUnary()
                val op = if (t.type == TokType.IDENT) t.text else t.text
                return UnaryNode(op, operand, t.start, operand.end)
            }
            return parsePostfix()
        }

        private fun parsePostfix(): ExprNode {
            var node = parsePrimary()
            while (true) {
                val t = peek() ?: break
                when (t.type) {
                    TokType.DOT -> {
                        advance()
                        val name = peek()
                        if (name?.type != TokType.IDENT) err(name ?: t, "Expected a name after '.'")
                        advance()
                        node = MemberNode(node, name.text, node.start, name.end)
                    }
                    TokType.LBRACKET -> {
                        advance()
                        val idx = parseExpression()
                        val close = peek()
                        if (close?.type != TokType.RBRACKET) err(close ?: t, "Unclosed '['")
                        val end = advance().end
                        node = IndexNode(node, idx, node.start, end)
                    }
                    TokType.LPAREN -> {
                        val (args, end) = parseArgList()
                        node = CallNode(node, args, node.start, end)
                    }
                    else -> break
                }
            }
            return node
        }

        private fun parsePrimary(): ExprNode {
            val t = peek() ?: err(null, "Expected an expression")
            return when (t.type) {
                TokType.NUMBER -> { advance(); LitNode(t.text.toDoubleOrNull(), t.text, t.start, t.end) }
                TokType.STRING -> { advance(); LitNode(decodeString(t.text), t.text, t.start, t.end) }
                TokType.STRING_BAD -> err(t, "Unterminated string literal")
                TokType.IDENT -> parseIdentOrCall(t)
                TokType.LPAREN -> parseParenOrParams()
                TokType.LBRACKET ->
                    if (!backend) parseArrayLiteral()
                    else err(t, "Array literals are not supported in backend expressions — use listOf(…)")
                // A binary-only operator with no left operand (e.g. `&& b`, `* b`).
                TokType.OP -> err(t, "'${t.text}' is missing its left operand")
                TokType.PIPE -> err(t, "'|>' is missing its left operand")
                TokType.ARROW -> err(t, "'${t.text}' is missing its parameter")
                TokType.DOT -> err(t, "Expected an expression before '.'")
                TokType.RPAREN -> err(t, "Unmatched ')'")
                TokType.RBRACKET -> err(t, "Unmatched ']'")
                TokType.COLON -> err(t, "Unexpected ':'")
                TokType.COMMA -> err(t, "Unexpected ','")
                // The most common slip: using curly braces to group. They only ever delimit the
                // whole expression (`${…}`/`#{…}` backend, `{{…}}` frontend) — inside, group with parens.
                TokType.BAD -> when (t.text) {
                    "{", "}" -> err(
                        t,
                        "Unexpected '${t.text}' — curly braces only delimit the whole expression " +
                            (if (backend) "(\${…} / #{…})" else "({{…}})") + "; use parentheses to group",
                    )
                    else -> err(t, "Unexpected character '${t.text}'")
                }
            }
        }

        private fun parseIdentOrCall(t: Tok): ExprNode {
            // Backend/`prefix:name(` namespaced function call.
            if (peekAt(1)?.type == TokType.COLON && peekAt(2)?.type == TokType.IDENT && peekAt(3)?.type == TokType.LPAREN) {
                val ns = advance()          // prefix
                advance()                   // ':'
                val name = advance()        // name
                val (args, end) = parseArgList()
                return NsCallNode(ns.text, name.text, args, ns.start, name.end, ns.start, end)
            }
            advance()
            WORD_LITERAL[t.text]?.let { return LitNode(it, t.text, t.start, t.end) }
            if (t.text == "null" && "null" in WORD_LITERAL) return LitNode(null, t.text, t.start, t.end)
            return IdentNode(t.text, t.start, t.end)
        }

        /** `( expr )` grouping, or `( a, b )` parameter list (only legal before an arrow). */
        private fun parseParenOrParams(): ExprNode {
            val open = advance() // '('
            if (peek()?.type == TokType.RPAREN) {
                val end = advance().end
                return ParenParamsNode(emptyList(), open.start, end)
            }
            val first = parseExpression()
            if (peek()?.type == TokType.COMMA) {
                val items = mutableListOf(first)
                while (peek()?.type == TokType.COMMA) {
                    advance()
                    items += parseExpression()
                }
                val close = peek()
                if (close?.type != TokType.RPAREN) err(close ?: open, "Unclosed '('")
                val end = advance().end
                return ParenParamsNode(items, open.start, end)
            }
            val close = peek()
            if (close?.type != TokType.RPAREN) err(close ?: open, "Unclosed '('")
            advance()
            return first
        }

        private fun parseArrayLiteral(): ExprNode {
            val open = advance() // '['
            val elements = mutableListOf<ExprNode>()
            if (peek()?.type != TokType.RBRACKET) {
                elements += parseExpression()
                while (peek()?.type == TokType.COMMA) {
                    advance()
                    if (peek()?.type == TokType.RBRACKET) break // trailing comma tolerated
                    elements += parseExpression()
                }
            }
            val close = peek()
            if (close?.type != TokType.RBRACKET) err(close ?: open, "Unclosed '['")
            val end = advance().end
            return ArrayNode(elements, open.start, end)
        }

        /** Parses `( args )` starting at the LPAREN; returns args + the offset just past `)`. */
        private fun parseArgList(): Pair<List<ExprNode>, Int> {
            val open = advance() // '('
            val args = mutableListOf<ExprNode>()
            if (peek()?.type == TokType.RPAREN) return emptyList<ExprNode>() to advance().end
            while (true) {
                val here = peek()
                if (here == null) err(open, "Unclosed '('")
                if (here.type == TokType.COMMA || here.type == TokType.RPAREN) err(here, "Empty argument")
                args += parseExpression()
                val sep = peek()
                when (sep?.type) {
                    TokType.COMMA -> {
                        advance()
                        if (peek()?.type == TokType.RPAREN) err(peek(), "Empty argument")
                    }
                    TokType.RPAREN -> return args to advance().end
                    null -> err(open, "Unclosed '('")
                    else -> err(sep, "Expected ',' or ')' in argument list")
                }
            }
        }

        private fun extractParams(node: ExprNode): List<String> = when (node) {
            is IdentNode -> listOf(node.name)
            is ParenParamsNode -> node.items.map { (it as? IdentNode)?.name ?: err(it, "Arrow parameters must be plain names") }
            is ArrayNode -> node.elements.map { (it as? IdentNode)?.name ?: err(it, "Arrow parameters must be plain names") }
            else -> err(node, "Invalid arrow parameters")
        }

        private fun requireNotParams(node: ExprNode): ExprNode {
            if (node is ParenParamsNode) err(node, "Unexpected ',' — a comma is only allowed inside a call or array")
            return node
        }

        private fun decodeString(raw: String): String {
            if (raw.length < 2) return ""
            val inner = raw.substring(1, raw.length - 1)
            val sb = StringBuilder(inner.length)
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length) { sb.append(inner[i + 1]); i += 2 } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}
