// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon;

import com.intellij.util.ThrowableRunnable;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link DaemonAnalyzerTestCase} which uses only production methods of daemon and waits for completion via e.g.
 * {@link com.intellij.codeInsight.daemon.impl.TestDaemonCodeAnalyzerImpl#waitForDaemonToFinish} methods
 * and prohibits explicitly manipulating daemon state via e.g. {@link #doHighlighting()}
 */
public abstract class ProductionDaemonAnalyzerTestCase extends DaemonAnalyzerTestCase {
  @Override
  protected void runTestRunnable(@NotNull ThrowableRunnable<Throwable> testRunnable) throws Throwable {
    ProductionLightDaemonAnalyzerTestCase.runTestInProduction(myDaemonCodeAnalyzer, () -> super.runTestRunnable(testRunnable));
  }
}