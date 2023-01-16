// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.astro.lang.sfc.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.html.HtmlParsing
import com.intellij.lang.javascript.JSElementTypes
import com.intellij.lang.javascript.JSStubElementTypes
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.lang.javascript.ecmascript6.parsing.TypeScriptExpressionParser
import com.intellij.lang.javascript.ecmascript6.parsing.TypeScriptParser
import com.intellij.lang.javascript.parsing.JSXmlParser
import com.intellij.lang.javascript.types.JSEmbeddedContentElementType
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.tree.IElementType
import com.intellij.psi.xml.XmlTokenType
import com.intellij.util.containers.Stack
import com.intellij.xml.psi.XmlPsiBundle
import org.jetbrains.astro.lang.jsx.AstroJsxLanguage
import org.jetbrains.astro.lang.sfc.lexer.AstroSfcTokenTypes

class AstroSfcParsing(builder: PsiBuilder) : HtmlParsing(builder), JSXmlParser {

  private val tsxParser = AstroJsxParser()

  override fun isXmlTagStart(currentToken: IElementType?): Boolean =
    currentToken === XmlTokenType.XML_START_TAG_START

  override fun parseTag(names: Stack<String>): Boolean {
    parseTag()
    while (tagLevel() > 0 && peekTagName() != EXPRESSION_MARKER) {
      if (isEndTagRequired(peekTagName())) {
        error(XmlPsiBundle.message("xml.parsing.named.element.is.not.closed", peekTagName()))
      }
      doneTag()
    }
    return true
  }

  override fun shouldContinueParsingTag(): Boolean {
    val token = token()
    if (token === JSTokenTypes.XML_LBRACE || token is JSEmbeddedContentElementType) return true
    if (builder.hasJSToken()) return false
    if (token === XmlTokenType.XML_START_TAG_START) return true
    return tagLevel() == 0 || peekTagName() != EXPRESSION_MARKER
  }

  override fun parseOpenTagName(): String {
    val result: String
    if (token() === XmlTokenType.XML_NAME) {
      result = builder.tokenText!!
      advance()
    }
    else {
      result = ""
    }
    return result
  }

  override fun parseEndTagName(): String {
    val result: String
    if (token() === XmlTokenType.XML_NAME) {
      result = StringUtil.toLowerCase(builder.tokenText!!)
      advance()
    }
    else {
      // Astro does not care about closing tag names at all
      // Make an exception and allow empty closing tag </>
      // to close anything.
      result = peekTagName()
    }
    return result
  }

  override fun parseProlog() {
    builder.setDebugMode(true)
    builder.enforceCommentTokens(JSTokenTypes.COMMENTS)
    while (token().let {
        it === XmlTokenType.XML_COMMENT_CHARACTERS
        || it === AstroSfcTokenTypes.FRONTMATTER_SEPARATOR
        || it === AstroSfcTokenTypes.FRONTMATTER_SCRIPT
      })
      advance()
    super.parseProlog()
  }

  override fun hasCustomTagContent(): Boolean {
    return token() === JSTokenTypes.XML_LBRACE
  }

  override fun parseCustomTagContent(xmlText: PsiBuilder.Marker?): PsiBuilder.Marker? {
    var result = xmlText
    when (token()) {
      JSTokenTypes.XML_LBRACE -> {
        result = terminateText(xmlText)
        parseJsxExpression(false)
      }
    }
    return result
  }

  override fun hasCustomTopLevelContent(): Boolean {
    return hasCustomTagContent()
  }

  override fun parseCustomTopLevelContent(error: PsiBuilder.Marker?): PsiBuilder.Marker? {
    val result = flushError(error)
    terminateText(parseCustomTagContent(null))
    return result
  }

  override fun hasCustomAttributeValue(): Boolean {
    return token().let {
      it === JSTokenTypes.XML_LBRACE
      || it === JSTokenTypes.BACKQUOTE
    }
  }

  override fun parseCustomAttributeValue() {
    if (token() === JSTokenTypes.BACKQUOTE) {
      parseAttributeTemplateLiteralExpression()
    }
    else {
      parseJsxExpression(true)
    }
  }

  override fun hasCustomTagHeaderContent(): Boolean {
    return token() === JSTokenTypes.XML_LBRACE
  }

  override fun parseCustomTagHeaderContent() {
    when (token()) {
      JSTokenTypes.XML_LBRACE -> {
        val attributeName = builder.mark()
        parseJsxExpression(true)
        // Consume possible bad characters
        while (token() == XmlTokenType.XML_BAD_CHARACTER) {
          builder.advanceLexer()
        }
        // Expression attributes, which are followed by `=`,
        // are not expression attributes as far as Astro lexer is concerned
        if (token() == XmlTokenType.XML_EQ) {
          attributeName.collapse(XmlTokenType.XML_NAME)
          advance()
          parseAttributeValue()
          attributeName.precede().done(JSStubElementTypes.XML_ATTRIBUTE)
        }
        else {
          attributeName.done(JSStubElementTypes.XML_ATTRIBUTE)
        }
      }
    }
  }

  override fun doneTag() {
    if (peekTagName() == EXPRESSION_MARKER) {
      throw IllegalStateException(
        "Expression marker should not be done within the tag parsing code - it will cause unbalanced tree issues.")
    }
    super.doneTag()
  }

  override fun getHtmlTagElementType(): IElementType {
    return JSElementTypes.JSX_XML_LITERAL_EXPRESSION
  }

  override fun getHtmlAttributeElementType(): IElementType {
    return JSElementTypes.XML_ATTRIBUTE
  }

  override fun getHtmlAttributeValueElementType(): IElementType {
    return JSElementTypes.XML_ATTRIBUTE_VALUE
  }

  private fun parseJsxExpression(supportsNestedTemplateLiterals: Boolean) {
    parseExpressionWithTagsHandled {
      (tsxParser.expressionParser as AstroTypeScriptExpressionParser).parseExpression(supportsNestedTemplateLiterals)
    }
  }

  private fun parseAttributeTemplateLiteralExpression() {
    parseExpressionWithTagsHandled {
      (tsxParser.expressionParser as AstroTypeScriptExpressionParser).parseAttributeTemplateLiteralExpression()
    }
  }

  private fun parseExpressionWithTagsHandled(parse: () -> Unit) {
    val exprStart = mark()
    pushTag(exprStart, EXPRESSION_MARKER, EXPRESSION_MARKER)
    parse()
    // Since we're jumping in and out of HTML parser loop, expression parsing should be properly balanced.
    // We can expect that our `exprStart` marker has not been closed yet.
    while (tagLevel() > 0 && peekTagMarker() != exprStart) {
      val tagName = peekTagName()
      if (isEndTagRequired(tagName)) {
        error(XmlPsiBundle.message("xml.parsing.named.element.is.not.closed", peekTagName()))
      }
      doneTag()
    }
    if (tagLevel() == 0 || peekTagMarker() != exprStart) {
      throw IllegalStateException("Expression marker has already been closed. The tree is unbalanced.")
    }
    exprStart.done(JSStubElementTypes.EMBEDDED_EXPRESSION)
    closeTag()
  }

  inner class AstroJsxParser : TypeScriptParser(AstroJsxLanguage.INSTANCE, builder) {
    init {
      myXmlParser = this@AstroSfcParsing
      myExpressionParser = AstroTypeScriptExpressionParser(this)
    }
  }

  class AstroTypeScriptExpressionParser(parser: TypeScriptParser) : TypeScriptExpressionParser(parser) {

    private var supportNestedTemplateLiterals: Boolean = true

    fun parseExpression(supportsNestedTemplateLiterals: Boolean) {
      withNestedTemplateLiteralsSupport(supportsNestedTemplateLiterals) {
        checkMatches(builder, JSTokenTypes.XML_LBRACE, "javascript.parser.message.expected.lbrace")
        parseArgument()
        if (!checkMatches(builder, JSTokenTypes.XML_RBRACE, "javascript.parser.message.expected.rbrace")) {
          while (builder.hasJSToken()) {
            val tokenType = builder.tokenType
            builder.advanceLexer()
            if (tokenType === JSTokenTypes.XML_RBRACE) break
          }
        }
      }
    }

    fun parseAttributeTemplateLiteralExpression() {
      withNestedTemplateLiteralsSupport(false) {
        parseStringTemplate()
      }
    }

    private fun withNestedTemplateLiteralsSupport(enabled: Boolean, action: () -> Unit) {
      val prev = supportNestedTemplateLiterals
      supportNestedTemplateLiterals = enabled
      try {
        action()
      } finally {
        supportNestedTemplateLiterals = prev
      }
    }

    override fun parsePrimaryExpression(): Boolean {
      if (!supportNestedTemplateLiterals && builder.tokenType === JSTokenTypes.BACKQUOTE) {
        builder.error("Astro does not support nested template literals in this context.")
        return false
      }
      return super.parsePrimaryExpression()
    }
  }

  companion object {
    private const val EXPRESSION_MARKER = "<EXPR>"

    private fun PsiBuilder.hasJSToken() =
      tokenType?.language?.isKindOf(JavascriptLanguage.INSTANCE) == true

  }
}