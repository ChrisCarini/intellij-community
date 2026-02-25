// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.debugger.impl.frontend

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.debugger.impl.shared.proxy.XBreakpointProxy
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
abstract class FrontendXBreakpointInterLinePlacementDetector {
  abstract fun shouldBePlacedBetweenLines(breakpoint: XBreakpointProxy): Boolean

  companion object {
    private val EP_NAME: ExtensionPointName<FrontendXBreakpointInterLinePlacementDetector> =
      ExtensionPointName.create("com.intellij.xdebugger.frontendBreakpointInterLinePlacementDetector")

    fun shouldBePlacedBetweenLines(breakpoint: XBreakpointProxy): Boolean {
      return EP_NAME.extensionList.any { detector ->
        detector.shouldBePlacedBetweenLines(breakpoint)
      }
    }
  }
}
