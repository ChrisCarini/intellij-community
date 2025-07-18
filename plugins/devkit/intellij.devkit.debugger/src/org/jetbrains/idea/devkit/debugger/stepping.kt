// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.debugger

import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.impl.DebuggerUtilsImpl
import com.intellij.debugger.impl.wrapIncompatibleThreadStateException
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.registry.Registry
import com.sun.jdi.*
import java.util.*

private const val CANCELLATION_FQN = "com.intellij.openapi.progress.Cancellation"

private object PauseListener : DebuggerManagerListener {
  private val sessions = WeakHashMap<DebuggerSession, SessionThreadsData>()

  override fun sessionAttached(session: DebuggerSession?) {
    if (session == null) return
    sessions[session] = SessionThreadsData()
    session.process.addDebugProcessListener(object : DebugProcessListener {
      override fun paused(suspendContext: SuspendContext) {
        val context = suspendContext as? SuspendContextImpl ?: return
        val sessionData = getSessionData(context.debugProcess.session) ?: return
        sessionData.setNonCancellableSection(context)
      }
    })
  }

  override fun sessionDetached(session: DebuggerSession?) {
    if (session == null) return
    sessions.remove(session)
  }

  fun getSessionData(session: DebuggerSession): SessionThreadsData? = sessions[session]
}

private class ResumeListener : SteppingListener {
  override fun beforeResume(suspendContext: SuspendContextImpl) {
    val sessionData = PauseListener.getSessionData(suspendContext.debugProcess.session) ?: return
    sessionData.resetNonCancellableSection(suspendContext)
  }
}

private data class ThreadState(val reference: ObjectReference, var state: Boolean = false) {
  /**
   * @see com.intellij.openapi.progress.Cancellation.DebugNonCancellableState.inNonCancelableSection
   */
  fun setNonCancellable(suspendContext: SuspendContextImpl, value: Boolean) {
    if (value == state) return
    state = value
    val field = DebuggerUtils.findField(reference.referenceType(), "inNonCancelableSection") ?: return
    reference.setValue(field, booleanValue(suspendContext, value))
  }
}

/**
 * Manages cancellability state of the IDE threads within a single debugger session.
 */
private class SessionThreadsData() {
  private val threadStates = hashMapOf<ThreadReferenceProxyImpl, ThreadState?>()
  private var isIdeRuntime = false

  /**
   * Sets the non-cancellable state for the current thread.
   * This method requires a suspend context command, as it may cause evaluation.
   */
  fun setNonCancellableSection(suspendContext: SuspendContextImpl) {
    try {
      wrapIncompatibleThreadStateException {
        if (!isPCEAdjustmentEnabled(suspendContext)) return
        val thread = suspendContext.thread ?: return
        val state = getOrCreateThreadState(suspendContext) ?: return
        setNonCancelableSafe(state, thread, suspendContext, true)
      }
    }
    catch (e: Exception) {
      DebuggerUtilsImpl.logError(e)
    }
  }

  /**
   * Resets the non-cancellable flag for the paused threads.
   */
  fun resetNonCancellableSection(suspendContext: SuspendContextImpl) {
    try {
      wrapIncompatibleThreadStateException {
        if (!isPCEAdjustmentEnabled(suspendContext)) return
        val pausedThreads = suspendContext.debugProcess.suspendManager.pausedContexts
          .mapNotNull { it.thread }
        for (thread in pausedThreads) {
          val state = threadStates[thread] ?: continue
          setNonCancelableSafe(state, thread, suspendContext, false)
        }
      }
    }
    catch (e: Exception) {
      DebuggerUtilsImpl.logError(e)
    }
  }

  private fun setNonCancelableSafe(
    state: ThreadState, thread: ThreadReferenceProxyImpl,
    suspendContext: SuspendContextImpl, value: Boolean,
  ) {
    try {
      state.setNonCancellable(suspendContext, value)
    }
    catch (_: ObjectCollectedException) {
      threadStates.remove(thread)
    }
  }

  /**
   * Get a reference to the [com.intellij.openapi.progress.Cancellation.DebugNonCancellableState] instance
   * bounded to the current thread.
   * Uses cached value if already created to reduce the number of evaluations.
   */
  private fun getOrCreateThreadState(suspendContext: SuspendContextImpl): ThreadState? {
    val thread = suspendContext.thread ?: return null
    if (threadStates.containsKey(thread)) return threadStates[thread]
    val reference = invokeInSuspendCommand(suspendContext) { initializeThreadState(suspendContext) } ?: return null
    return ThreadState(reference).also { threadStates[thread] = it }
  }

  private fun isPCEAdjustmentEnabled(suspendContextImpl: SuspendContextImpl): Boolean {
    if (!Registry.`is`("devkit.debugger.prevent.pce.while.stepping")) return false
    if (isIdeRuntime) return true
    val cancellationClasses = suspendContextImpl.virtualMachineProxy.classesByName(CANCELLATION_FQN).filter(ReferenceType::isPrepared)
    return cancellationClasses.isNotEmpty().also { isIde -> if (isIde) isIdeRuntime = true }
  }
}

/**
 * @see com.intellij.openapi.progress.Cancellation.initThreadNonCancellableState
 * @see com.intellij.openapi.progress.Cancellation.isInNonCancelableSection
 */
private fun initializeThreadState(suspendContext: SuspendContextImpl): ObjectReference? {
  if (!suspendContext.debugProcess.isEvaluationPossible(suspendContext)) return null
  val cancellationClass = findClassOrNull(suspendContext, CANCELLATION_FQN) as? ClassType ?: return null
  val method = DebuggerUtilsImpl.findMethod(cancellationClass,
                                            "initThreadNonCancellableState",
                                            "()Lcom/intellij/openapi/progress/Cancellation\$DebugNonCancellableState;")
               ?: run {
                 logger<ResumeListener>().debug("Init method not found. Unsupported IJ platform version?")
                 return null
               }
  val evaluationContext = EvaluationContextImpl(suspendContext, suspendContext.frameProxy)
  try {
    return evaluationContext.debugProcess.invokeMethod(evaluationContext, cancellationClass, method, emptyList()) as? ObjectReference
  }
  catch (e: EvaluateException) {
    val targetException = e.exceptionFromTargetVM
    if (targetException != null && DebuggerUtils.instanceOf(targetException.type(), "java.lang.StackOverflowError")) {
      return null
    }
    throw e
  }
}

private fun booleanValue(suspendContext: SuspendContextImpl, b: Boolean): BooleanValue = suspendContext.virtualMachineProxy.mirrorOf(b)

private fun <T> invokeInSuspendCommand(suspendContext: SuspendContextImpl, action: () -> T): T? {
  if (DebugProcessImpl.isInSuspendCommand(suspendContext)) {
    return action()
  }
  var result: T? = null
  var exception: Exception? = null
  val command = object : SuspendContextCommandImpl(suspendContext) {
    override fun contextAction(suspendContext: SuspendContextImpl) {
      try {
        result = action()
      }
      catch (e: VMDisconnectedException) {
        throw e
      }
      catch (e: Exception) {
        exception = e
      }
    }
  }
  exception?.let { throw it }
  suspendContext.managerThread.invokeNow(command)
  return result
}
