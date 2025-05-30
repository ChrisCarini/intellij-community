// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.j2k.post.processing.inference.common

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.j2k.ConverterContext
import org.jetbrains.kotlin.nj2k.*
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

abstract class SuperFunctionsProvider {
    abstract fun provideSuperFunctionDescriptors(function: KtFunction): List<FunctionDescriptor>?
    lateinit var inferenceContext: InferenceContext
}

class ResolveSuperFunctionsProvider(private val resolutionFacade: ResolutionFacade) : SuperFunctionsProvider() {
    override fun provideSuperFunctionDescriptors(function: KtFunction): List<FunctionDescriptor>? =
        function.resolveToDescriptorIfAny(resolutionFacade)
            ?.safeAs<FunctionDescriptor>()
            ?.original
            ?.overriddenDescriptors
            ?.toList()
}

class ByInfoSuperFunctionsProvider(
    private val resolutionFacade: ResolutionFacade,
    private val converterContext: ConverterContext
) : SuperFunctionsProvider() {

    private val labelToFunction by lazy(LazyThreadSafetyMode.NONE) {
        val functions = mutableMapOf<JKElementInfoLabel, KtNamedFunction>()
        for (element in inferenceContext.elements) {
            element.forEachDescendantOfType<KtNamedFunction> { function ->
                val label = function.nameIdentifier?.getInferenceLabel() ?: return@forEachDescendantOfType
                functions += label to function
            }
        }
        functions
    }

    override fun provideSuperFunctionDescriptors(function: KtFunction): List<FunctionDescriptor>? =
        function.nameIdentifier?.elementInfo(converterContext)?.firstIsInstanceOrNull<FunctionInfo>()
            ?.superFunctions
            ?.mapNotNull { superFunction ->
                when (superFunction) {
                    is ExternalSuperFunctionInfo -> superFunction.descriptor
                    is InternalSuperFunctionInfo ->
                        labelToFunction[superFunction.label]?.resolveToDescriptorIfAny(resolutionFacade)
                }
            }
}