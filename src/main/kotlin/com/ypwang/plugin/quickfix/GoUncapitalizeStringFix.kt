package com.ypwang.plugin.quickfix

import com.goide.psi.GoStringLiteral
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.apache.commons.lang.StringEscapeUtils

class GoDecapitalizeStringFix(element: GoStringLiteral): LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Decapitalize string"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val stringLiteral = startElement as GoStringLiteral
        stringLiteral.replace(GoElementFactory.createStringLiteral(project,
                "\"${StringEscapeUtils.escapeJava(stringLiteral.decodedText.decapitalize())}\""))
    }
}