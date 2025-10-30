package com.intellij.lambda.testFramework.junit

import com.intellij.ide.starter.config.ConfigurationStorage
import com.intellij.ide.starter.config.splitMode
import com.intellij.ide.starter.runner.Starter
import com.intellij.ide.starter.utils.catchAll
import com.intellij.lambda.testFramework.starter.newContextWithLambda
import com.intellij.lambda.testFramework.utils.BackgroundRunWithLambda
import com.intellij.lambda.testFramework.utils.IdeLambdaStarter
import com.intellij.lambda.testFramework.utils.IdeLambdaStarter.runIdeWithLambda
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

class MonolithAndSplitModeIdeInstanceInitializer : AfterAllCallback {
  companion object {
    lateinit var ideBackgroundRun: BackgroundRunWithLambda
      private set
    lateinit var currentIdeMode: IdeRunMode
      private set

    fun startIde(runMode: IdeRunMode, context: ExtensionContext): BackgroundRunWithLambda {
      synchronized(this) {
        if (this::ideBackgroundRun.isInitialized && currentIdeMode == runMode) {
          println("IDE is already running in mode: $runMode. Reusing the current instance of IDE.")
          return ideBackgroundRun
        }

        stopIde()
        currentIdeMode = runMode
        ConfigurationStorage.splitMode(currentIdeMode == IdeRunMode.SPLIT)

        println("Starting IDE in mode: $runMode")

        val testContext = Starter.newContextWithLambda(context.testClass.get().simpleName,
                                                       UltimateTestCases.JpsEmptyProject,
                                                       additionalPluginModule = IdeLambdaStarter.ADDITIONAL_LAMBDA_TEST_PLUGIN)
        ideBackgroundRun = testContext.runIdeWithLambda()

        return ideBackgroundRun
      }
    }

    private fun stopIde() {
      synchronized(this) {
        if (!this::ideBackgroundRun.isInitialized) return

        println("Stopping IDE that is running in mode: $currentIdeMode")
        catchAll { ideBackgroundRun.closeIdeAndWait() }
      }
    }

  }

  override fun afterAll(context: ExtensionContext) {
    stopIde()
  }
}