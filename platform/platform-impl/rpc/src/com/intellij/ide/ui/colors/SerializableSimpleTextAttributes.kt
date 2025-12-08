// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.ui.colors

import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus

@Serializable
@ApiStatus.Experimental
data class SerializableSimpleTextAttributes(val bgColor: ColorId?, val fgColor: ColorId?, val waveColor: ColorId?, val style: Int)