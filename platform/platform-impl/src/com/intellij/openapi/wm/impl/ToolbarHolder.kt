// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl

internal interface ToolbarHolder {
  fun scheduleUpdateToolbar()

  fun isColorfulToolbar(): Boolean
}