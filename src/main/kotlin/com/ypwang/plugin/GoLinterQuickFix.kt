package com.ypwang.plugin

import com.goide.inspections.GoInspectionUtil
import com.goide.psi.*
import com.goide.quickfix.*
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.*

private val nonAvailableFix = arrayOf<LocalQuickFix>() to null

private inline fun <reified T : PsiElement> chainFindAndHandle(
        file: PsiFile,
        offset: Int,
        default: Pair<Array<LocalQuickFix>, TextRange?>,
        handler: (T) -> Pair<Array<LocalQuickFix>, TextRange?>?
): Pair<Array<LocalQuickFix>, TextRange?> {
    var element = file.findElementAt(offset)
    while (element != null) {
        if (element is T)
            return handler(element) ?: default

        element = element.parent
    }

    return default
}

open class ProblemHandler {
    fun suggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            try {
                doSuggestFix(file, issue)
            } catch (e: Exception) {
                nonAvailableFix
            }

    open fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, issue.Pos.Offset, nonAvailableFix) { element: GoNamedElement ->
                when (element) {
                    is GoFieldDefinition -> {
                        val decl = element.parent
                        if (decl is GoFieldDeclaration) {
                            var start: PsiElement = decl
                            while (start.prevSibling != null && (start.prevSibling !is PsiWhiteSpaceImpl || start.prevSibling.text != "\n"))
                                start = start.prevSibling

                            var end: PsiElement = decl.nextSibling
                            while (end !is PsiWhiteSpaceImpl || end.text != "\n")
                                end = end.nextSibling

                            // remove entire line
                            arrayOf<LocalQuickFix>(GoDeleteRangeQuickFix(start, end, "Delete field '${element.identifier.text}'"))
                        } else arrayOf()
                    }
                    is GoFunctionDeclaration ->
                        arrayOf(GoDeleteQuickFix("Delete function ${element.identifier}", GoFunctionDeclaration::class.java), GoRenameToBlankQuickFix(element))
                    is GoTypeSpec ->
                        arrayOf<LocalQuickFix>(GoDeleteTypeQuickFix(element.identifier.text))
                    is GoVarDefinition ->
                        (if (GoInspectionUtil.canDeleteDefinition(element)) arrayOf(GoRenameToBlankQuickFix(element), GoDeleteVarDefinitionQuickFix(element.name))
                        else arrayOf<LocalQuickFix>(GoRenameToBlankQuickFix(element)))
                    is GoConstDefinition ->
                        (if (GoInspectionUtil.canDeleteDefinition(element)) arrayOf<LocalQuickFix>(GoDeleteConstDefinitionQuickFix(element.name)) else arrayOf())
//                    is GoMethodDeclaration -> arrayOf(GoDeleteQuickFix("Delete function", GoMethodDeclaration::class.java), GoRenameToBlankQuickFix(element))
//                    is GoLightMethodDeclaration -> arrayOf(GoDeleteQuickFix("Delete function", GoLightMethodDeclaration::class.java), GoRenameToBlankQuickFix(element))
                    else -> nonAvailableFix.first      // TODO
                } to element.identifier?.textRange
            }
}

val defaultHandler = ProblemHandler()

private val ineffassignHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> {
        if (issue.Text.startsWith("ineffectual assignment to")) {
            // get the variable
            val variable = issue.Text.substring(issue.Text.lastIndexOf(' ') + 1).trim('`')
            // normally cur pos is LeafPsiElement, parent should be GoVarDefinition (a := 1) or GoReferenceExpressImpl (a = 1)
            // we cannot delete/rename GoVarDefinition, as that would have surprising impact on usage below
            // while for Reference we could safely rename it to '_' without causing damage
            val element = file.findElementAt(issue.Pos.Offset)?.parent
            if (element is GoReferenceExpression && element.text == variable) {
                return arrayOf<LocalQuickFix>(GoReferenceRenameToBlankQuickFix(element)) to element.identifier.textRange
            }
        }
        return nonAvailableFix
    }
}

private val scopelintHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> {
        return arrayOf<LocalQuickFix>(GoScopeLintFakeFix()) to null
    }
}

private val interfacerHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> {
        return chainFindAndHandle(file, issue.Pos.Offset, nonAvailableFix) { element: GoParameterDeclaration ->
            // last child is type signature
            arrayOf<LocalQuickFix>(GoReplaceParameterTypeFix(
                    issue.Text.substring(issue.Text.lastIndexOf(' ') + 1).trim('`'),
                    element
            )) to element.lastChild.textRange
        }
    }
}

private val gocriticHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            when {
                issue.Text.startsWith("assignOp: replace") -> {
                    var begin = issue.Text.indexOf('`')
                    var end = issue.Text.indexOf('`', begin + 1)
                    val currentAssignment = issue.Text.substring(begin + 1, end)

                    begin = issue.Text.indexOf('`', end + 1)
                    end = issue.Text.indexOf('`', begin + 1)
                    val replace = issue.Text.substring(begin + 1, end)

                    chainFindAndHandle(file, issue.Pos.Offset, nonAvailableFix) { element: GoAssignmentStatement ->
                        if (element.text == currentAssignment) {
                            if (replace.endsWith("++") || replace.endsWith("--"))
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoIncDecStatement::class.java)) to element.textRange
                            else
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoAssignmentStatement::class.java)) to element.textRange
                        }
                        else null
                    }
                }
                issue.Text.startsWith("sloppyLen:") -> {
                    chainFindAndHandle(file, issue.Pos.Offset, nonAvailableFix) { element: GoConditionalExpr ->
                        if (issue.Text.contains(element.text)) {
                            val searchPattern = "can be "
                            val replace = issue.Text.substring(issue.Text.indexOf(searchPattern) + searchPattern.length)
                            arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoConditionalExpr::class.java)) to element.textRange
                        } else null
                    }
                }
                issue.Text.startsWith("unslice:") -> {
                    chainFindAndHandle(file, issue.Pos.Offset, nonAvailableFix) { element: GoIndexOrSliceExpr ->
                        if (issue.Text.contains(element.text) && element.expression != null)
                            arrayOf<LocalQuickFix>(GoReplaceExpressionFix(element.expression!!.text, element)) to element.textRange
                        else null
                    }
                }
                else -> nonAvailableFix
            }
}

private val golintHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            when {
                issue.Text.startsWith("var ") || issue.Text.startsWith("const ") -> {
                    var begin = issue.Text.indexOf('`')
                    var end = issue.Text.indexOf('`', begin + 1)
                    val curName = issue.Text.substring(begin + 1, end)

                    begin = issue.Text.indexOf('`', end + 1)
                    end = issue.Text.indexOf('`', begin + 1)
                    val newName = issue.Text.substring(begin + 1, end)

                    chainFindAndHandle(file, issue.Pos.Offset, nonAvailableFix) { element: GoNamedElement ->
                        if (element.text == curName)
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, newName)) to element.identifier?.textRange
                        else null
                    }
                }
                issue.Text.startsWith("receiver name ") -> {
                    val searchPattern = "receiver name "
                    var begin = issue.Text.indexOf(searchPattern) + searchPattern.length
                    val curName = issue.Text.substring(begin, issue.Text.indexOf(' ', begin))

                    begin = issue.Text.indexOf(searchPattern, begin + 1) + searchPattern.length
                    val newName = issue.Text.substring(begin, issue.Text.indexOf(' ', begin))
                    chainFindAndHandle(file, issue.Pos.Offset, nonAvailableFix) { element: GoMethodDeclaration ->
                        val receiver = element.receiver
                        if (receiver != null && receiver.identifier!!.text == curName) {
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(receiver, newName)) to receiver.identifier?.textRange
                        } else null
                    }
                }
                issue.Text.startsWith("type name will be used as ") -> {
                    val newName = issue.Text.substring(issue.Text.lastIndexOf(' ') + 1)
                    chainFindAndHandle(file, issue.Pos.Offset, nonAvailableFix) { element: GoTypeSpec ->
                        if (element.identifier.text.startsWith(element.containingFile.packageName ?: "", true))
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, newName)) to element.identifier.textRange
                        else null
                    }
                }
                else -> nonAvailableFix
            }
}

private val whitespaceHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> {
        assert(issue.LineRange != null)
        // whitespace linter tells us the start line and end line
        var start = Int.MAX_VALUE
        var end = Int.MIN_VALUE

        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        if (document != null) {
            val elements = mutableListOf<PsiElement>()
            for (line in issue.LineRange!!.To downTo issue.LineRange.From) {
                // line in document starts from 0
                val s = document.getLineStartOffset(line - 1)
                val e = document.getLineEndOffset(line - 1)
                start = minOf(start, s)
                end = maxOf(end, e)
                if (s == e) {
                    // whitespace line
                    val element = file.findElementAt(s)
                    if (element is PsiWhiteSpaceImpl && element.chars.all { it == '\n' })
                        elements.add(element)
                }
            }

            if (elements.isNotEmpty()) return arrayOf<LocalQuickFix>(GoDeleteElementsFix(elements)) to TextRange(start, end)
        }

        return nonAvailableFix
    }
}

// experimental
private val goconstHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> {
        return nonAvailableFix
    }
}

// attempt to suggest auto-fix, if possible, clarify affected PsiElement for better inspection
val quickFixHandler = mapOf(
        "ineffassign" to ineffassignHandler,
        "structcheck" to defaultHandler,
        "varcheck" to defaultHandler,
        "deadcode" to defaultHandler,
        "unused" to defaultHandler,
        "scopelint" to scopelintHandler,
        "gocritic" to gocriticHandler,
        "interfacer" to interfacerHandler,
        "whitespace" to whitespaceHandler,
        "golint" to golintHandler,
        "goconst" to goconstHandler
)