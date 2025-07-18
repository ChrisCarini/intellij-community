// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.feedback.dialog

import com.intellij.openapi.project.Project

/**
 * The base class for building feedback dialogs with only common system data.
 *
 * @see CommonBlockBasedFeedbackDialogWithEmail
 */
abstract class CommonBlockBasedFeedbackDialog(myProject: Project?, myForTest: Boolean) : BlockBasedFeedbackDialog<CommonFeedbackSystemData>(myProject, myForTest) {
  override suspend fun computeSystemInfoData(): CommonFeedbackSystemData {
    return CommonFeedbackSystemData.getCurrentData()
  }

  override fun showFeedbackSystemInfoDialog(systemInfoData: CommonFeedbackSystemData) {
    showFeedbackSystemInfoDialog(myProject, systemInfoData)
  }
}
