// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.lang.jvm.actions

import com.intellij.lang.jvm.types.JvmType

public typealias ExpectedTypes = List<ExpectedType>

public fun expectedType(type: JvmType, kind: ExpectedType.Kind = ExpectedType.Kind.EXACT): ExpectedType = SimpleExpectedType(type, kind)

public fun expectedTypes(type: JvmType, kind: ExpectedType.Kind = ExpectedType.Kind.EXACT): ExpectedTypes = listOf(expectedType(type, kind))

internal class SimpleExpectedType(private val theType: JvmType, private val theKind: ExpectedType.Kind) : ExpectedType {
  override fun getTheType(): JvmType = theType
  override fun getTheKind(): ExpectedType.Kind = theKind
}
