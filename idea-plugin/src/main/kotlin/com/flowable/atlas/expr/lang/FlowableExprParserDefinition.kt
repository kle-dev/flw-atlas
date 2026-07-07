package com.flowable.atlas.expr.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * A deliberately trivial single-leaf parser definition: the whole fragment is one token under the
 * file node. It exists only so the platform gives the fragment a [PsiFile], which activates the
 * standard [com.flowable.atlas.expr.annotator.FlowableExpressionAnnotator] (squiggles / error stripe)
 * and [com.flowable.atlas.expr.completion.FlowableExpressionCompletionContributor] machinery — no
 * grammar, no generated sources. Real syntax checking is done by the token-stream validator.
 */
abstract class FlowableExprParserDefinition(
    private val language: FlowableExprLanguage,
    private val fileType: LanguageFileType,
) : ParserDefinition {

    private val contentToken = IElementType("FLOWABLE_EXPR_CONTENT", language)
    private val fileNodeType = IFileElementType(language)

    override fun createLexer(project: Project?): Lexer = SingleLeafLexer(contentToken)

    override fun createParser(project: Project?): PsiParser = object : PsiParser {
        override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
            val mark = builder.mark()
            while (!builder.eof()) builder.advanceLexer()
            mark.done(root)
            return builder.treeBuilt
        }
    }

    override fun getFileNodeType(): IFileElementType = fileNodeType
    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile =
        FlowableExprFile(viewProvider, language, fileType)
}

class FlowableBackendExprParserDefinition :
    FlowableExprParserDefinition(FlowableBackendExprLanguage, FlowableBackendExprFileType)

class FlowableFrontendExprParserDefinition :
    FlowableExprParserDefinition(FlowableFrontendExprLanguage, FlowableFrontendExprFileType)

/** A lexer that returns the entire buffer as one [contentToken] and then EOF. */
private class SingleLeafLexer(private val contentToken: IElementType) : LexerBase() {
    private var buffer: CharSequence = ""
    private var tokenStart = 0
    private var end = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.tokenStart = startOffset
        this.end = endOffset
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = if (tokenStart < end) contentToken else null
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = end
    override fun advance() { tokenStart = end }
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end
}
