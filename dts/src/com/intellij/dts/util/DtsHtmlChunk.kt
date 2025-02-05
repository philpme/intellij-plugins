package com.intellij.dts.util

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.dts.DtsBundle
import com.intellij.dts.highlighting.DtsHighlightAnnotator
import com.intellij.dts.highlighting.DtsTextAttributes
import com.intellij.dts.lang.DtsLanguage
import com.intellij.dts.lang.psi.DtsNode
import com.intellij.dts.lang.psi.DtsPHandle
import com.intellij.dts.lang.psi.DtsProperty
import com.intellij.dts.lang.psi.DtsRootNode
import com.intellij.lang.annotation.Annotation
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.editor.richcopy.SyntaxInfoBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import org.jetbrains.annotations.PropertyKey

object DtsHtmlChunk {
    private val bindingHtmlTag = "!!html\n"
    private val bindingLineBrakeRx = Regex("\\n\\s*\\n")

    private val highlightAnnotator = DtsHighlightAnnotator()

    private val dtsKeywords = listOf(
        "#include", "#define",
        "/include/", "/dts-v1/", "/plugin/", "/memreserve/", "/delete-node/", "/delete-property/", "/omit-if-no-ref/"
    )

    private fun styledSpan(attr: DtsTextAttributes, text: String): @NlsSafe String {
        return HtmlSyntaxInfoUtil.getStyledSpan(attr.attribute, text, 1.0f)
    }

    /**
     * Generates the colored html for a property name.
     */
    fun property(element: DtsProperty): HtmlChunk {
        return HtmlChunk.raw(styledSpan(DtsTextAttributes.PROPERTY_NAME, element.dtsName))
    }

    private fun nodeName(nodeName: String): HtmlChunk {
        val (name, addr) = DtsUtil.splitName(nodeName)
        if (addr == null) return HtmlChunk.raw(styledSpan(DtsTextAttributes.NODE_NAME, name))

        return HtmlChunk.fragment(
            HtmlChunk.raw(styledSpan(DtsTextAttributes.NODE_NAME, name)),
            HtmlChunk.text("@"),
            HtmlChunk.raw(styledSpan(DtsTextAttributes.NODE_UNIT_ADDR, addr))
        )
    }

    private fun pHandle(handle: DtsPHandle): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append("&")

        val label = handle.dtsPHandleLabel
        if (label != null) {
            builder.appendRaw(styledSpan(DtsTextAttributes.LABEL, label.text))
        }

        val path = handle.dtsPHandlePath
        if (path != null) {
            builder.append("{")

            val segments = path.text.split("/").filter { it.isNotEmpty() }
            for (segment in segments) {
                builder.append("/")
                builder.append(nodeName(segment))
            }

            builder.append("}")
        }

        return builder.toFragment()
    }

    /**
     * Generates the colored html for a subnode or root node name.
     */
    fun node(element: DtsNode): HtmlChunk {
        return when (element) {
            is DtsNode.Root -> HtmlChunk.text("/")
            is DtsNode.Sub -> nodeName(element.dtsName)
            is DtsNode.Ref -> pHandle(element.dtsHandle)
        }
    }

    /**
     * Generates the colored html for the path to a node from the root of the
     * current file. Including the node itself.
     */
    fun path(element: DtsNode): HtmlChunk {
        val parents = DtsTreeUtil.parentNodes(element)
        if (parents.isEmpty()) return node(element)

        val builder = HtmlBuilder()
        for (parent in parents.reversed()) {
            if (parent !is DtsRootNode) {
                builder.append(node(parent))
            }

            builder.append("/")
        }
        builder.append(node(element))

        return builder.toFragment()
    }

    fun bundle(key: @PropertyKey(resourceBundle = DtsBundle.BUNDLE) String): HtmlChunk {
        return HtmlChunk.raw(DtsBundle.message(key))
    }

    fun string(text: @NlsSafe String): HtmlChunk {
        return HtmlChunk.raw(styledSpan(DtsTextAttributes.STRING, text))
    }

    private fun tryParseDtsToHtml(project: Project, text: String): @NlsSafe String? {
        val fakePsiFile = PsiFileFactory.getInstance(project).createFileFromText(
            "comment.dtsi",
            DtsLanguage, text.trim(),
            false,
            false,
        )

        val errors = SyntaxTraverser.psiTraverser(fakePsiFile).traverse().filter {
            if (it !is PsiErrorElement) return@filter false

            // Ignore ... sometimes used to skip missing properties and ignore
            // errors at the end of the text. Probably just a missing semicolon.
            it.text != "..." && it.startOffset != fakePsiFile.endOffset
        }
        if (errors.isNotEmpty) return null

        // reformat the file, because whitespace could be messed up after loaded
        // from binding
        CodeStyleManager.getInstance(project).reformat(fakePsiFile, true)

        @Suppress("DEPRECATION")
        val holder = AnnotationHolderImpl(AnnotationSession(fakePsiFile), false)

        fakePsiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is CompositeElement) {
                    holder.runAnnotatorWithContext(element, highlightAnnotator)
                }

                super.visitElement(element)
            }
        })

        val scheme = EditorColorsManager.getInstance().schemeForCurrentUITheme
        val content = HtmlSyntaxInfoUtil.getHtmlContent(
            fakePsiFile,
            fakePsiFile.text,
            AnnotationHolderIterator(holder, scheme),
            scheme,
            0,
            fakePsiFile.text.length,
        ) ?: return null

        return "<code>$content</code>"
    }

    private fun bindingHtml(project: Project, text: String): @NlsSafe String {
        if (text.startsWith(bindingHtmlTag)) return text.removePrefix(bindingHtmlTag).trim()

        val paragraphs = text.trim().split(bindingLineBrakeRx)

        val html = paragraphs.map { paragraph ->
            val couldBeDtsCode = paragraph.contains(";") || dtsKeywords.any { paragraph.contains(it) }
            if (couldBeDtsCode) {
                val html = tryParseDtsToHtml(project, paragraph)
                if (html != null) return@map html
            }

            StringUtil.escapeXmlEntities(paragraph.replace("\n", " "))
        }

        return html.joinToString("<br/><br/>")
    }

    /**
     * Generates html from text which was loaded from a zephyr binding. If the
     * text starts with "!!html" it will be loaded as raw html. Otherwise, the
     * text is split into consecutive paragraphs and separated by two line
     * breaks. If a paragraph can be successfully parsed by the dts parser, it
     * is considered as dts code and will be colored and formatted accordingly.
     * Useful if the binding contains an example.
     */
    fun binding(project: Project, text: @NlsSafe String): HtmlChunk {
        return HtmlChunk.raw(bindingHtml(project, text))
    }
}

private class AnnotationHolderIterator(holder: Iterable<Annotation>, val scheme: EditorColorsScheme) : SyntaxInfoBuilder.RangeIterator {
    private val iterator = holder.iterator()
    private var annotation: Annotation? = null

    private val requireAnnotation: Annotation
        get() = requireNotNull(annotation) { "no annotation, check atEnd first" }

    override fun advance() {
        if (iterator.hasNext()) {
            annotation = iterator.next()
        }
    }

    override fun atEnd(): Boolean = !iterator.hasNext()

    override fun getRangeStart(): Int = requireAnnotation.startOffset

    override fun getRangeEnd(): Int = requireAnnotation.endOffset

    override fun getTextAttributes(): TextAttributes = scheme.getAttributes(requireAnnotation.textAttributes)

    override fun dispose() {}
}
