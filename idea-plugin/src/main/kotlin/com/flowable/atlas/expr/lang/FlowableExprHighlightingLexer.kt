package com.flowable.atlas.expr.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * The lexer that drives editor syntax coloring + brace matching. It reuses [ExpressionLexer] and
 * fills the skipped whitespace with [TokenType.WHITE_SPACE] so the token stream is contiguous (the
 * editor highlighter requires gap-free coverage).
 *
 * Round parens are coloured **per pair**: every opening `(` takes the next colour in the cycle and
 * its matching `)` reuses it (a stack pairs them), so consecutive/sibling pairs are visually distinct
 * *and* a pair's `(` / `)` always share a colour. The running colour cursor plus the stack of open
 * colours are encoded into the lexer **state** (see [ParenState]) so the incremental editor
 * highlighter restarts with the correct colours after an edit. Doing this in the lexer (not an
 * annotator) means the colours paint in every surface, including the playground's embedded field.
 */
class FlowableExprHighlightingLexer : LexerBase() {

    private data class Segment(val start: Int, val end: Int, val type: IElementType, val stateAtStart: Int)

    private var buffer: CharSequence = ""
    private var end = 0
    private var segments: List<Segment> = emptyList()
    private var endState = 0
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        buildSegments(buffer.subSequence(startOffset, endOffset).toString(), startOffset, initialState)
        this.index = 0
    }

    /** Tokenize [text] (offsets rebased by [base]), cycling paren colours from [initialState]. */
    private fun buildSegments(text: String, base: Int, initialState: Int) {
        val out = ArrayList<Segment>()
        var pos = 0
        val (initNext, initStack) = ParenState.decode(initialState)
        var next = initNext                       // next opener's colour (0 until PAREN_LEVELS)
        val stack = ArrayDeque<Int>().apply { initStack.forEach { addLast(it) } }
        for (tk in ExpressionLexer.tokenize(text)) {
            if (tk.start > pos) out += Segment(base + pos, base + tk.start, TokenType.WHITE_SPACE, ParenState.encode(next, stack))
            val stateAtStart = ParenState.encode(next, stack)
            val type = when (tk.type) {
                TokType.LPAREN -> {
                    val color = next
                    stack.addLast(color)
                    next = (next + 1) % FlowableExprTokenTypes.PAREN_LEVELS
                    FlowableExprTokenTypes.paren(true, color)
                }
                TokType.RPAREN -> {
                    val color = if (stack.isNotEmpty()) stack.removeLast() else next
                    FlowableExprTokenTypes.paren(false, color)
                }
                else -> FlowableExprTokenTypes.of(tk.type)
            }
            out += Segment(base + tk.start, base + tk.end, type, stateAtStart)
            pos = tk.end
        }
        if (pos < text.length) out += Segment(base + pos, base + text.length, TokenType.WHITE_SPACE, ParenState.encode(next, stack))
        this.segments = out
        this.endState = ParenState.encode(next, stack)
    }

    override fun getState(): Int = segments.getOrNull(index)?.stateAtStart ?: endState
    override fun getTokenType(): IElementType? = segments.getOrNull(index)?.type
    override fun getTokenStart(): Int = segments[index].start
    override fun getTokenEnd(): Int = segments[index].end
    override fun advance() { index++ }
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end

    /**
     * Reversible packing of (next-colour cursor, stack-of-open-colours) into one non-negative Int
     * lexer state. Empty stack → just the cursor (0..N-1). Otherwise the stack is a base-N number with
     * a leading 1 (so its length is recoverable). Beyond [MAX_DEPTH] we degrade to the cursor only —
     * an unreachable nesting depth for real expressions, so matching stays correct in practice.
     */
    private object ParenState {
        private const val N = FlowableExprTokenTypes.PAREN_LEVELS
        private const val MAX_DEPTH = 12

        fun encode(nextColor: Int, stack: Collection<Int>): Int {
            if (stack.isEmpty() || stack.size > MAX_DEPTH) return nextColor
            var code = 1
            for (c in stack) code = code * N + c          // bottom → top
            return nextColor + N * code
        }

        fun decode(state: Int): Pair<Int, List<Int>> {
            if (state < N) return state to emptyList()
            var code = state / N
            val rev = ArrayList<Int>()
            while (code > 1) { rev.add(code % N); code /= N }
            rev.reverse()                                  // bottom → top
            return (state % N) to rev
        }
    }
}
