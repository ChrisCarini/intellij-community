// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.syntax.psi

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.LighterLazyParseableNode
import com.intellij.openapi.components.service
import com.intellij.platform.syntax.lexer.Lexer
import com.intellij.platform.syntax.psi.impl.PsiSyntaxBuilderFactoryImpl

interface PsiSyntaxBuilderFactory {
  fun createBuilder(
    chameleon: ASTNode,
    lexer: Lexer? = null,
    lang: Language = chameleon.elementType.language,
    text: CharSequence = chameleon.chars,
  ): PsiSyntaxBuilder

  fun createBuilder(
    chameleon: LighterLazyParseableNode,
    lexer: Lexer? = null,
    lang: Language = chameleon.tokenType.language,
    text: CharSequence = chameleon.text,
  ): PsiSyntaxBuilder

  companion object {
    @JvmStatic
    fun getInstance(): PsiSyntaxBuilderFactory = service<PsiSyntaxBuilderFactoryImpl>() // todo replace with proper service registration.
  }
}

