// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.completion.lookups


import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.signatures.KaVariableSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.symbols.typeParameters
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.completion.impl.k2.KotlinCompletionImplK2Bundle
import org.jetbrains.kotlin.idea.completion.lookups.CompletionShortNamesRenderer.renderFunctionalTypeParameters
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.renderer.render

@ApiStatus.Internal
object TailTextProvider {

    context(KaSession)
    fun getTailText(
        signature: KaCallableSignature<*>,
        useFqName: Boolean = false,
    ): String  {
        // use unsubstituted type when rendering receiver type of extension
        val symbol = signature.symbol
        return getTailText(symbol, useFqName = useFqName)
    }

    context(KaSession)
    fun getTailText(
        symbol: KaCallableSymbol,
        useFqName: Boolean = false,
    ): String = buildString {
        symbol.receiverType?.let { renderReceiverType(it) }

        symbol.getContainerOrAliasPresentation(useFqName = useFqName)?.let { append(it) }
    }

    context(KaSession)
    private fun KaCallableSymbol.getContainerOrAliasPresentation(isFunctionalVariableCall: Boolean = false, useFqName: Boolean = false): String? {
        return if (useFqName) {
            val callableId = callableId ?: return null
            val renderedAliasName = callableId.asSingleFqName().asStringForTailText()
            " ($renderedAliasName)"
        } else {
            getContainerPresentation(isFunctionalVariableCall = isFunctionalVariableCall)
        }
    }

    context(KaSession)
    fun getTailTextForVariableCall(
        functionalType: KaFunctionType,
        signature: KaVariableSignature<*>,
        useFqName: Boolean = false,
    ): String = buildString {
        if (insertLambdaBraces(functionalType)) {
            append(" {...} ")
        }
        append(renderFunctionalTypeParameters(functionalType))

        // use unsubstituted type when rendering receiver type of extension
        functionalType.receiverType?.let { renderReceiverType(it) }

        signature.symbol.getContainerOrAliasPresentation(useFqName = useFqName, isFunctionalVariableCall = true)?.let { append(it) }
    }

    context(KaSession)
    @OptIn(KaExperimentalApi::class)
    fun getTailText(
        symbol: KaClassLikeSymbol,
        usePackageFqName: Boolean = false,
        addTypeParameters: Boolean = true,
        useFqnAsTailText: Boolean = false,
    ): String = buildString {
        symbol.classId?.let { classId ->
            if (addTypeParameters && symbol.typeParameters.isNotEmpty()) {
                // We want to render type parameter names without modifiers and bounds, so no renderer is required.
                append(symbol.typeParameters.joinToString(", ", "<", ">") { it.name.render() })
            }

            val fqName = if (useFqnAsTailText) {
                classId.asSingleFqName()
            } else if (usePackageFqName) {
                classId.packageFqName
            } else {
                classId.asSingleFqName().parent()
            }

            append(" (")
            append(fqName.asStringForTailText())
            append(")")
        }
    }

    context(KaSession)
    @OptIn(KaExperimentalApi::class)
    private fun StringBuilder.renderReceiverType(receiverType: KaType) {
        val renderedType = receiverType.renderVerbose()
        append(KotlinCompletionImplK2Bundle.message("presentation.tail.for.0", renderedType))
    }

    context(KaSession)
    private fun KaCallableSymbol.getContainerPresentation(isFunctionalVariableCall: Boolean): String? {
        val callableId = callableId ?: return null
        val className = callableId.className

        val isExtensionCall = isExtensionCall(isFunctionalVariableCall)
        val packagePresentation = callableId.packageName.asStringForTailText()
        return when {
            !isExtensionCall && className != null -> null
            !isExtensionCall -> " ($packagePresentation)"

            else -> {
                val containerPresentation = className?.asString() ?: packagePresentation
                KotlinCompletionImplK2Bundle.message("presentation.tail.in.0", containerPresentation)
            }
        }
    }

    private fun FqName.asStringForTailText(): String =
        if (isRoot) "<root>" else render()

    context(KaSession)
    fun insertLambdaBraces(symbol: KaFunctionType): Boolean {
        val singleParam = symbol.parameterTypes.singleOrNull()
        return singleParam != null && singleParam is KaFunctionType
    }
}