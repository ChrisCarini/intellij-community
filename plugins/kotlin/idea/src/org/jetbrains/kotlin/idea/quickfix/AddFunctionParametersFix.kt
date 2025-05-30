// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.builtins.isKFunctionType
import org.jetbrains.kotlin.builtins.isKSuspendFunctionType
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.CollectingNameValidator
import org.jetbrains.kotlin.idea.intentions.reflectToRegularFunctionType
import org.jetbrains.kotlin.idea.refactoring.changeSignature.*
import org.jetbrains.kotlin.idea.util.getDataFlowAwareTypes
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker

class AddFunctionParametersFix(
    callElement: KtCallElement,
    functionDescriptor: FunctionDescriptor,
    private val kind: Kind
) : ChangeFunctionSignatureFix(callElement, functionDescriptor) {
    sealed class Kind {
        object ChangeSignature : Kind()
        object AddParameterGeneric : Kind()
        class AddParameter(val argumentIndex: Int) : Kind()
    }

    private val argumentIndex: Int?
        get() = (kind as? Kind.AddParameter)?.argumentIndex

    private val callElement: KtCallElement?
        get() = element as? KtCallElement

    override fun getText(): String {
        val callElement = callElement ?: return ""

        val parameters = functionDescriptor.valueParameters
        val arguments = callElement.valueArguments
        val newParametersCnt = arguments.size - parameters.size
        assert(newParametersCnt > 0)

        val declarationName = when {
            isConstructor() -> functionDescriptor.containingDeclaration.name.asString()
            else -> functionDescriptor.name.asString()
        }

        return when (kind) {
            is Kind.ChangeSignature -> {
                if (isConstructor()) {
                    KotlinBundle.message("fix.add.function.parameters.change.signature.constructor", declarationName)
                } else {
                    KotlinBundle.message("fix.add.function.parameters.change.signature.function", declarationName)
                }
            }
            is Kind.AddParameterGeneric -> {
                if (isConstructor()) {
                    KotlinBundle.message("fix.add.function.parameters.add.parameter.generic.constructor", newParametersCnt, declarationName)
                } else {
                    KotlinBundle.message("fix.add.function.parameters.add.parameter.generic.function", newParametersCnt, declarationName)
                }
            }
            is Kind.AddParameter -> {
                if (isConstructor()) {
                    KotlinBundle.message(
                        "fix.add.function.parameters.add.parameter.constructor",
                        kind.argumentIndex + 1, newParametersCnt, declarationName
                    )
                } else {
                    KotlinBundle.message(
                        "fix.add.function.parameters.add.parameter.function",
                        kind.argumentIndex + 1, newParametersCnt, declarationName
                    )
                }
            }
        }
    }

    override fun isAvailable(project: Project, editor: Editor?, file: KtFile): Boolean {
        if (!super.isAvailable(project, editor, file)) return false
        val callElement = callElement ?: return false

        // newParametersCnt <= 0: psi for this quickfix is no longer valid
        val newParametersCnt = callElement.valueArguments.size - functionDescriptor.valueParameters.size
        if (argumentIndex != null && newParametersCnt != 1) return false
        return newParametersCnt > 0
    }

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val callElement = callElement ?: return
        runChangeSignature(project, editor, functionDescriptor, addParameterConfiguration(), callElement, text)
    }

    private fun addParameterConfiguration(): KotlinChangeSignatureConfiguration {
        return object : KotlinChangeSignatureConfiguration {
            override fun configure(originalDescriptor: KotlinMethodDescriptor): KotlinMethodDescriptor {
                val argumentIndex = this@AddFunctionParametersFix.argumentIndex

                return originalDescriptor.modify(fun(descriptor: KotlinMutableMethodDescriptor) {
                    val callElement = callElement ?: return
                    val arguments = callElement.valueArguments
                    val parameters = functionDescriptor.valueParameters
                    val validator = CollectingNameValidator()
                    val receiverCount = if (descriptor.receiver != null) 1 else 0

                    if (argumentIndex != null) {
                        parameters.forEach { validator.addName(it.name.asString()) }
                        val argument = arguments[argumentIndex]
                        val parameterInfo = getNewParameterInfo(
                            originalDescriptor.baseDescriptor as FunctionDescriptor,
                            argument,
                            validator,
                        )

                        descriptor.addParameter(argumentIndex + receiverCount, parameterInfo)
                        return
                    }

                    val call = callElement.getCall(callElement.analyze()) ?: return
                    for (i in arguments.indices) {
                        val argument = arguments[i]
                        val expression = argument.getArgumentExpression()

                        if (i < parameters.size) {
                            validator.addName(parameters[i].name.asString())
                            val argumentType = expression?.let {
                                val bindingContext = it.analyze()
                                val smartCasts = bindingContext[BindingContext.SMARTCAST, it]
                                smartCasts?.defaultType ?: smartCasts?.type(call) ?: bindingContext.getType(it)
                            }

                            val parameterType = parameters[i].type
                            if (argumentType != null && !KotlinTypeChecker.DEFAULT.isSubtypeOf(argumentType, parameterType)) {
                                descriptor.parameters[i + receiverCount].currentTypeInfo = KotlinTypeInfo(false, argumentType)
                            }
                        } else {
                            val parameterInfo = getNewParameterInfo(
                                originalDescriptor.baseDescriptor as FunctionDescriptor,
                                argument,
                                validator,
                            )

                            descriptor.addParameter(parameterInfo)
                        }
                    }
                })
            }

            override fun isPerformSilently(affectedFunctions: Collection<PsiElement>): Boolean {
                val onlyFunction = affectedFunctions.singleOrNull() ?: return false
                return kind != Kind.ChangeSignature && !isConstructor() && !hasOtherUsages(onlyFunction)
            }
        }
    }

    private fun getNewParameterInfo(
        functionDescriptor: FunctionDescriptor,
        argument: ValueArgument,
        validator: (String) -> Boolean
    ): KotlinParameterInfo {
        val name = getNewArgumentName(argument, validator)
        val expression = argument.getArgumentExpression()
        val type = (expression?.let { getDataFlowAwareTypes(it).firstOrNull() } ?: functionDescriptor.builtIns.nullableAnyType).let {
            if (it.isKFunctionType || it.isKSuspendFunctionType) it.reflectToRegularFunctionType() else it
        }
        return KotlinParameterInfo(functionDescriptor, -1, name, KotlinTypeInfo(false, null)).apply {
            currentTypeInfo = KotlinTypeInfo(false, type)
            if (expression != null) defaultValueForCall = expression
        }
    }

    private fun hasOtherUsages(function: PsiElement): Boolean {
        (function as? PsiNamedElement)?.let {
            val name = it.name ?: return false
            val project = runReadAction { it.project }
            val psiSearchHelper = PsiSearchHelper.getInstance(project)
            val globalSearchScope = GlobalSearchScope.projectScope(project)
            val cheapEnoughToSearch = psiSearchHelper.isCheapEnoughToSearch(name, globalSearchScope, null)
            if (cheapEnoughToSearch == PsiSearchHelper.SearchCostResult.TOO_MANY_OCCURRENCES) return false
        }

        return ReferencesSearch.search(function).asIterable().any {
            val call = it.element.getParentOfType<KtCallElement>(false)
            call != null && callElement != call
        }
    }

    private fun isConstructor() = functionDescriptor is ConstructorDescriptor

    companion object TypeMismatchFactory : KotlinSingleIntentionActionFactoryWithDelegate<KtCallElement, Pair<FunctionDescriptor, Int>>() {
        override fun getElementOfInterest(diagnostic: Diagnostic): KtCallElement? {
            val (_, valueArgumentList) = diagnostic.valueArgument() ?: return null
            return valueArgumentList.getStrictParentOfType()
        }

        override fun extractFixData(element: KtCallElement, diagnostic: Diagnostic): Pair<FunctionDescriptor, Int>? {
            val (valueArgument, valueArgumentList) = diagnostic.valueArgument() ?: return null
            val arguments = valueArgumentList.arguments + element.lambdaArguments
            val argumentIndex = arguments.indexOfFirst { it == valueArgument }
            val context = element.analyze()
            val functionDescriptor = element.getResolvedCall(context)?.resultingDescriptor as? FunctionDescriptor ?: return null
            val parameters = functionDescriptor.valueParameters
            if (arguments.size - 1 != parameters.size) return null
            if ((arguments - valueArgument).zip(parameters).any { (argument, parameter) ->
                    val argumentType = argument.getArgumentExpression()?.let { context.getType(it) }
                    argumentType == null || !KotlinTypeChecker.DEFAULT.isSubtypeOf(argumentType, parameter.type)
                }) return null

            return functionDescriptor to argumentIndex
        }

        override fun createFix(originalElement: KtCallElement, data: Pair<FunctionDescriptor, Int>): IntentionAction? {
            val (functionDescriptor, argumentIndex) = data
            val parameters = functionDescriptor.valueParameters
            val arguments = originalElement.valueArguments
            return if (arguments.size > parameters.size) {
                AddFunctionParametersFix(originalElement, functionDescriptor, Kind.AddParameter(argumentIndex))
            } else {
                null
            }
        }

        private fun Diagnostic.valueArgument(): Pair<KtValueArgument, KtValueArgumentList>? {
            val element = DiagnosticFactory.cast(
                this,
                Errors.TYPE_MISMATCH,
                Errors.TYPE_MISMATCH_WARNING,
                Errors.CONSTANT_EXPECTED_TYPE_MISMATCH,
                Errors.NULL_FOR_NONNULL_TYPE,
            ).psiElement

            val valueArgument = element.getStrictParentOfType<KtValueArgument>() ?: return null
            val valueArgumentList = valueArgument.getStrictParentOfType<KtValueArgumentList>() ?: return null
            return valueArgument to valueArgumentList
        }
    }
}
