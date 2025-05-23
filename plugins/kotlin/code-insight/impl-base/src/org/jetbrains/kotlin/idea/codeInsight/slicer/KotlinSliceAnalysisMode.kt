// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.codeInsight.slicer

import com.intellij.psi.createSmartPointer
import com.intellij.slicer.SliceUsage
import com.intellij.util.Processor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction

data class KotlinSliceAnalysisMode(val behaviourStack: List<Behaviour>, val inlineCallStack: List<InlineFunctionCall>) {
    fun withBehaviour(behaviour: Behaviour) = copy(behaviourStack = behaviourStack + behaviour)

    fun withInlineFunctionCall(
        callElement: KtElement,
        function: KtNamedFunction
    ) = copy(inlineCallStack = inlineCallStack + InlineFunctionCall(callElement, function))

    fun dropBehaviour(): KotlinSliceAnalysisMode {
        check(behaviourStack.isNotEmpty())
        return copy(behaviourStack = behaviourStack.dropLast(1))
    }

    fun popInlineFunctionCall(function: KtNamedFunction): Pair<KotlinSliceAnalysisMode?, KtElement?> {
        val last = inlineCallStack.lastOrNull()
        if (last?.function?.navigationElement != function) return null to null
        val newMode = copy(inlineCallStack = inlineCallStack.dropLast(1))
        return newMode to last.callElement
    }

    val currentBehaviour: Behaviour?
        get() = behaviourStack.lastOrNull()

    interface Behaviour {
        fun processUsages(
            element: KtElement,
            parent: AbstractKotlinSliceUsage,
            uniqueProcessor: Processor<in SliceUsage>
        )

        val slicePresentationPrefix: String
        val testPresentationPrefix: String

        override fun equals(other: Any?): Boolean
        override fun hashCode(): Int
    }

    class InlineFunctionCall(callElement: KtElement, function: KtNamedFunction) {
        private val callElementPointer = callElement.createSmartPointer()
        private val functionPointer = function.createSmartPointer()

        val callElement: KtElement?
            get() = callElementPointer.element

        val function: KtNamedFunction?
            get() = functionPointer.element

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is InlineFunctionCall && other.callElement == callElement && other.function == function
        }

        override fun hashCode() = 0
    }

    companion object {
        val Default = KotlinSliceAnalysisMode(emptyList(), emptyList())
    }
}
