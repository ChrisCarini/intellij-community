// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator;
import com.intellij.codeInsight.daemon.impl.TestDaemonCodeAnalyzerImpl;
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl;
import com.intellij.util.ThrowableRunnable;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link LightDaemonAnalyzerTestCase} which uses only production methods of daemon and waits for completion via e.g.
 * {@link com.intellij.codeInsight.daemon.impl.TestDaemonCodeAnalyzerImpl#waitForDaemonToFinish} methods
 * and prohibits explicitly manipulating daemon state via e.g. {@link #doHighlighting()}
 */
public abstract class ProductionLightDaemonAnalyzerTestCase extends LightDaemonAnalyzerTestCase {
  @Override
  protected void runTestRunnable(@NotNull ThrowableRunnable<Throwable> testRunnable) throws Throwable {
    runTestInProduction(myDaemonCodeAnalyzer, () -> super.runTestRunnable(testRunnable));
  }

  public static void runTestInProduction(@NotNull DaemonCodeAnalyzerImpl codeAnalyzer, @NotNull ThrowableRunnable<Throwable> testRunnable) throws Throwable {
    boolean isUpdateByTimerEnabled = codeAnalyzer.isUpdateByTimerEnabled();
    try {
      codeAnalyzer.setUpdateByTimerEnabled(true);
      DaemonProgressIndicator.runInDebugMode(() ->
      CodeInsightTestFixtureImpl.disableInstantiateAndRunIn(() ->
      TestDaemonCodeAnalyzerImpl.runWithReparseDelay(0, testRunnable)));
    }
    finally {
      codeAnalyzer.setUpdateByTimerEnabled(isUpdateByTimerEnabled);
    }
  }
}