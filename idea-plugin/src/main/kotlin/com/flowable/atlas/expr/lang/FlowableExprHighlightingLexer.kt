package com.flowable.atlas.expr.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * The lexer that drives editor syntax coloring + brace matching. It reuses [ExpressionLexer] and
 * fills the skipped whitespace with [TokenType.WHITE_SPACE] so the token stream is contiguous (the
 * editor highlighter requires gap-free coverage). Stateless — safe to restart at any token boundary.
 */
class FlowableExprHighlightingLexer : LexerBase() {

    private data class Segment(val start: Int, val end: Int, val type: IElementType)

    private var buffer: CharSequence = ""
    private var end = 0
    private var segments: List<Segment> = emptyList()
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        this.segments = buildSegments(buffer.subSequence(startOffset, endOffset).toString(), startOffset)
        this.index = 0
    }

    private fun buildSegments(text: String, base: Int): List<Segment> {
        val out = ArrayList<Segment>()
        var pos = 0
        for (tk in ExpressionLexer.tokenize(text)) {
            if (tk.start > pos) out += Segment(base + pos, base + tk.start, TokenType.WHITE_SPACE)
            out += Segment(base + tk.start, base + tk.end, FlowableExprTokenTypes.of(tk.type))
            pos = tk.end
        }
        if (pos < text.length) out += Segment(base + pos, base + text.length, TokenType.WHITE_SPACE)
        return out
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = segments.getOrNull(index)?.type
    override fun getTokenStart(): Int = segments[index].start
    override fun getTokenEnd(): Int = segments[index].end
    override fun advance() { index++ }
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end
}
