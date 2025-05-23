// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.completion.smart

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.base.analysis.withRootPrefixIfNeeded
import org.jetbrains.kotlin.idea.caches.resolve.util.resolveToDescriptor
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.codeInsight.collectSyntheticStaticMembersAndConstructors
import org.jetbrains.kotlin.idea.completion.*
import org.jetbrains.kotlin.idea.completion.handlers.KotlinFunctionCompositeDeclarativeInsertHandler
import org.jetbrains.kotlin.idea.completion.handlers.KotlinFunctionInsertHandler
import org.jetbrains.kotlin.idea.core.ExpectedInfo
import org.jetbrains.kotlin.idea.core.KotlinIndicesHelper
import org.jetbrains.kotlin.idea.core.Tail
import org.jetbrains.kotlin.idea.core.multipleFuzzyTypes
import org.jetbrains.kotlin.idea.core.overrideImplement.ImplementMembersHandler
import org.jetbrains.kotlin.idea.formatter.kotlinCustomSettings
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.util.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.resolveTopLevelClass
import org.jetbrains.kotlin.resolve.sam.SamConstructorDescriptor
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.util.constructors
import org.jetbrains.kotlin.util.kind
import org.jetbrains.kotlin.utils.addIfNotNull

class TypeInstantiationItems(
  val resolutionFacade: ResolutionFacade,
  val bindingContext: BindingContext,
  val visibilityFilter: (DeclarationDescriptor) -> Boolean,
  val toFromOriginalFileMapper: ToFromOriginalFileMapper,
  val inheritorSearchScope: GlobalSearchScope,
  val lookupElementFactory: LookupElementFactory,
  private val forOrdinaryCompletion: Boolean,
  val indicesHelper: KotlinIndicesHelper
) {
    companion object {
        private val FUNCTIONS_OR_CLASSIFIERS_MASK =
            DescriptorKindFilter(DescriptorKindFilter.FUNCTIONS_MASK or DescriptorKindFilter.CLASSIFIERS_MASK)
    }

    fun addTo(
        items: MutableCollection<LookupElement>,
        inheritanceSearchers: MutableCollection<InheritanceItemsSearcher>,
        expectedInfos: Collection<ExpectedInfo>
    ) {
        val expectedInfosGrouped = LinkedHashMap<FuzzyType, MutableList<ExpectedInfo>>()
        for (expectedInfo in expectedInfos) {
            for (fuzzyType in expectedInfo.multipleFuzzyTypes) {
                expectedInfosGrouped.getOrPut(fuzzyType.makeNotNullable()) { ArrayList() }.add(expectedInfo)
            }
        }

        for ((type, infos) in expectedInfosGrouped) {
            val tail = mergeTails(infos.map { it.tail })
            addTo(items, inheritanceSearchers, type, tail)
        }
    }

    private fun addTo(
        items: MutableCollection<LookupElement>,
        inheritanceSearchers: MutableCollection<InheritanceItemsSearcher>,
        fuzzyType: FuzzyType,
        tail: Tail?
    ) {
        if (fuzzyType.type.isFunctionType) return // do not show "object: ..." for function types

        val classifier = fuzzyType.type.constructor.declarationDescriptor as? ClassifierDescriptorWithTypeParameters ?: return
        val classDescriptor = when (classifier) {
            is ClassDescriptor -> classifier
            is TypeAliasDescriptor -> classifier.classDescriptor
            else -> null
        }

        addSamConstructorItem(items, classifier, classDescriptor, tail)
        items.addIfNotNull(createTypeInstantiationItem(fuzzyType, classDescriptor, tail))

        indicesHelper.resolveTypeAliasesUsingIndex(fuzzyType.type, classifier.name.asString()).forEach {
            addSamConstructorItem(items, it, classDescriptor, tail)
            val typeAliasFuzzyType = it.defaultType.toFuzzyType(fuzzyType.freeParameters)
            items.addIfNotNull(createTypeInstantiationItem(typeAliasFuzzyType, classDescriptor, tail))
        }

        if (classDescriptor != null && !forOrdinaryCompletion && !KotlinBuiltIns.isAny(classDescriptor)) { // do not search inheritors of Any
            val typeArgs = fuzzyType.type.arguments
            inheritanceSearchers.addInheritorSearcher(classDescriptor, classDescriptor, typeArgs, fuzzyType.freeParameters, tail)

            val javaClassId = JavaToKotlinClassMap.mapKotlinToJava(DescriptorUtils.getFqName(classifier))
            if (javaClassId != null) {
                val javaAnalog =
                    resolutionFacade.moduleDescriptor.resolveTopLevelClass(javaClassId.asSingleFqName(), NoLookupLocation.FROM_IDE)
                if (javaAnalog != null) {
                    inheritanceSearchers.addInheritorSearcher(javaAnalog, classDescriptor, typeArgs, fuzzyType.freeParameters, tail)
                }
            }
        }
    }

    private fun MutableCollection<InheritanceItemsSearcher>.addInheritorSearcher(
        descriptor: ClassDescriptor,
        kotlinClassDescriptor: ClassDescriptor,
        typeArgs: List<TypeProjection>,
        freeParameters: Collection<TypeParameterDescriptor>,
        tail: Tail?
    ) {
        val _declaration = DescriptorToSourceUtilsIde.getAnyDeclaration(resolutionFacade.project, descriptor) ?: return
        val declaration = if (_declaration is KtDeclaration)
            toFromOriginalFileMapper.toOriginalFile(_declaration) ?: return
        else
            _declaration

        val psiClass: PsiClass = when (declaration) {
            is PsiClass -> declaration
            is KtClassOrObject -> declaration.toLightClass() ?: return
            else -> return
        }
        add(InheritanceSearcher(psiClass, kotlinClassDescriptor, typeArgs, freeParameters, tail))
    }

    private fun createTypeInstantiationItem(fuzzyType: FuzzyType, classDescriptor: ClassDescriptor?, tail: Tail?): LookupElement? {
        val classifier = fuzzyType.type.constructor.declarationDescriptor as? ClassifierDescriptorWithTypeParameters ?: return null

        var lookupElement = lookupElementFactory.createLookupElement(classifier, useReceiverTypes = false)

        if (DescriptorUtils.isNonCompanionObject(classifier)) {
            return lookupElement.addTail(tail)
        }

        // not all inner classes can be instantiated and we handle them via constructors returned by ReferenceVariantsHelper
        if (classifier.isInner) return null

        val isAbstract = classDescriptor?.modality == Modality.ABSTRACT
        if (forOrdinaryCompletion && isAbstract) return null

        val allConstructors = classifier.constructors
        val visibleConstructors = allConstructors.filter {
            if (isAbstract)
                visibilityFilter(it) || it.visibility == DescriptorVisibilities.PROTECTED
            else
                visibilityFilter(it)
        }
        if (allConstructors.isNotEmpty() && visibleConstructors.isEmpty()) return null

        var lookupString = lookupElement.lookupString
        var allLookupStrings = setOf(lookupString)
        var itemText = lookupString
        var signatureText: String? = null
        val typeText = IdeDescriptorRenderers.SOURCE_CODE.renderClassifierName(classifier)

        val insertHandler: InsertHandler<LookupElement>
        if (isAbstract) {
            val typeArgs = fuzzyType.type.arguments
            // drop "in" and "out" from type arguments - they cannot be used in constructor call
            val typeArgsToUse = typeArgs.map { TypeProjectionImpl(Variance.INVARIANT, it.type) }

            val allTypeArgsKnown =
                fuzzyType.freeParameters.isEmpty() || typeArgs.none { it.type.areTypeParametersUsedInside(fuzzyType.freeParameters) }
            itemText += if (allTypeArgsKnown) {
                IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_NO_ANNOTATIONS.renderTypeArguments(typeArgsToUse)
            } else {
                "<...>"
            }

            val constructorParenthesis = if (classifier.kind != ClassKind.INTERFACE) "()" else ""
            itemText += constructorParenthesis
            itemText = "object : $itemText{...}"
            lookupString = "object"
            allLookupStrings = setOf(lookupString, lookupElement.lookupString)
            insertHandler = InsertHandler<LookupElement> { context, _ ->
                val startOffset = context.startOffset

                val settings = context.file.kotlinCustomSettings
                val spaceBefore = if (settings.SPACE_BEFORE_EXTEND_COLON) " " else ""
                val spaceAfter = if (settings.SPACE_AFTER_EXTEND_COLON) " " else ""
                val text1 = "object$spaceBefore:$spaceAfter$typeText"
                val text2 = "$constructorParenthesis {}"
                val text = if (allTypeArgsKnown)
                    text1 + IdeDescriptorRenderers.SOURCE_CODE.renderTypeArguments(typeArgsToUse) + text2
                else
                    "$text1<>$text2"

                context.document.replaceString(startOffset, context.tailOffset, text)

                if (allTypeArgsKnown) {
                    context.editor.caretModel.moveToOffset(startOffset + text.length - 1)

                    shortenReferences(context, startOffset, startOffset + text.length)

                    ImplementMembersHandler().invoke(context.project, context.editor, context.file, true)
                } else {
                    context.editor.caretModel.moveToOffset(startOffset + text1.length + 1) // put caret into "<>"

                    shortenReferences(context, startOffset, startOffset + text.length)
                }
            }
            lookupElement = lookupElement.suppressAutoInsertion()
            lookupElement = lookupElement.assignSmartCompletionPriority(SmartCompletionItemPriority.ANONYMOUS_OBJECT)
        } else {
            //TODO: when constructor has one parameter of lambda type with more than one parameter, generate special additional item
            signatureText = when (visibleConstructors.size) {
                0 -> "()"

                1 -> {
                    val constructor = visibleConstructors.single()
                    val substitutor = TypeSubstitutor.create(fuzzyType.presentationType())
                    val substitutedConstructor = constructor.substitute(substitutor)
                        ?: constructor // render original signature if failed to substitute
                    BasicLookupElementFactory.SHORT_NAMES_RENDERER.renderFunctionParameters(substitutedConstructor)
                }

                else -> "(...)"
            }

            val baseInsertHandler = when (visibleConstructors.size) {
                0 -> KotlinFunctionInsertHandler.Normal(
                    CallType.DEFAULT,
                    inputTypeArguments = false,
                    inputValueArguments = false,
                    argumentsOnly = true
                )

                1 -> lookupElementFactory.insertHandlerProvider.insertHandler(visibleConstructors.single(), argumentsOnly = true)

                else -> KotlinFunctionInsertHandler.Normal(
                    CallType.DEFAULT,
                    inputTypeArguments = false,
                    inputValueArguments = true,
                    argumentsOnly = true
                )
            }

            insertHandler = InsertHandler { context, item ->
                val insertText = FqName(typeText).withRootPrefixIfNeeded(null).asString()
                context.document.replaceString(context.startOffset, context.tailOffset, insertText)
                context.tailOffset = context.startOffset + insertText.length

                baseInsertHandler.handleInsert(context, item)

                shortenReferences(context, context.startOffset, context.tailOffset)
            }

            run {
                val (inputValueArgs, isLambda) = when (baseInsertHandler) {
                    is KotlinFunctionInsertHandler.Normal -> baseInsertHandler.inputValueArguments to (baseInsertHandler.lambdaInfo != null)
                    is KotlinFunctionCompositeDeclarativeInsertHandler -> baseInsertHandler.inputValueArguments to baseInsertHandler.isLambda
                    else -> false to false
                }
                if (inputValueArgs) {
                    lookupElement = lookupElement.keepOldArgumentListOnTab()
                }
                if (isLambda) {
                    lookupElement.acceptOpeningBrace = true
                }
            }
            lookupElement = lookupElement.assignSmartCompletionPriority(SmartCompletionItemPriority.INSTANTIATION)
        }

        class InstantiationLookupElement : LookupElementDecorator<LookupElement>(lookupElement) {
            override fun getLookupString() = lookupString

            override fun getAllLookupStrings() = allLookupStrings

            override fun renderElement(presentation: LookupElementPresentation) {
                delegate.renderElement(presentation)
                presentation.itemText = itemText

                presentation.clearTail()
                signatureText?.let {
                    presentation.appendTailText(it, false)
                }
                presentation.appendTailText(" (" + DescriptorUtils.getFqName(classifier.containingDeclaration) + ")", true)
            }

            override fun getDelegateInsertHandler() = insertHandler

            override fun equals(other: Any?): Boolean {
                if (other === this) return true
                if (other !is InstantiationLookupElement) return false
                if (getLookupString() != other.lookupString) return false
                val presentation1 = LookupElementPresentation()
                val presentation2 = LookupElementPresentation()
                renderElement(presentation1)
                other.renderElement(presentation2)
                return presentation1.itemText == presentation2.itemText && presentation1.tailText == presentation2.tailText
            }

            override fun hashCode() = lookupString.hashCode()
        }

        return InstantiationLookupElement().addTail(tail)
    }

    private fun KotlinType.areTypeParametersUsedInside(freeParameters: Collection<TypeParameterDescriptor>): Boolean {
        return FuzzyType(this, freeParameters).freeParameters.isNotEmpty()
    }

    private fun addSamConstructorItem(
        collection: MutableCollection<LookupElement>,
        classifier: ClassifierDescriptorWithTypeParameters,
        classDescriptor: ClassDescriptor?,
        tail: Tail?
    ) {
        if (classDescriptor?.kind == ClassKind.INTERFACE) {
            val samConstructor = run {
                val scope = when (val container = classifier.containingDeclaration) {
                    is PackageFragmentDescriptor -> container.getMemberScope()
                    is ClassDescriptor -> container.unsubstitutedMemberScope
                    else -> return
                }
                scope.collectSyntheticStaticMembersAndConstructors(
                    resolutionFacade,
                    FUNCTIONS_OR_CLASSIFIERS_MASK
                ) { classifier.name == it }
                    .filterIsInstance<SamConstructorDescriptor>()
                    .singleOrNull() ?: return
            }
            lookupElementFactory
                .createStandardLookupElementsForDescriptor(samConstructor, useReceiverTypes = false)
                .mapTo(collection) { it.assignSmartCompletionPriority(SmartCompletionItemPriority.INSTANTIATION).addTail(tail) }
        }
    }

    private inner class InheritanceSearcher(
        private val psiClass: PsiClass,
        classDescriptor: ClassDescriptor,
        typeArgs: List<TypeProjection>,
        private val freeParameters: Collection<TypeParameterDescriptor>,
        private val tail: Tail?
    ) : InheritanceItemsSearcher {

        private val baseHasTypeArgs = classDescriptor.declaredTypeParameters.isNotEmpty()
        private val expectedType = KotlinTypeFactory.simpleNotNullType(TypeAttributes.Empty, classDescriptor, typeArgs)
        private val expectedFuzzyType = expectedType.toFuzzyType(freeParameters)

        override fun search(nameFilter: (String) -> Boolean, consumer: (LookupElement) -> Unit) {
            val parameters = ClassInheritorsSearch.SearchParameters(psiClass, inheritorSearchScope, true, true, false, nameFilter)
            for (inheritor in ClassInheritorsSearch.search(parameters).asIterable()) {
                val descriptor = inheritor.resolveToDescriptor(
                    resolutionFacade
                ) { toFromOriginalFileMapper.toSyntheticFile(it) } ?: continue
                if (!visibilityFilter(descriptor)) continue

                var inheritorFuzzyType = descriptor.defaultType.toFuzzyType(descriptor.typeConstructor.parameters)
                val hasTypeArgs = descriptor.declaredTypeParameters.isNotEmpty()
                if (hasTypeArgs || baseHasTypeArgs) {
                    val substitutor = inheritorFuzzyType.checkIsSubtypeOf(expectedFuzzyType) ?: continue
                    if (!substitutor.isEmpty) {
                        val inheritorTypeSubstituted = substitutor.substitute(inheritorFuzzyType.type, Variance.INVARIANT)!!
                        inheritorFuzzyType = inheritorTypeSubstituted.toFuzzyType(freeParameters + inheritorFuzzyType.freeParameters)
                    }
                }

                val lookupElement = createTypeInstantiationItem(inheritorFuzzyType, descriptor, tail) ?: continue
                consumer(lookupElement.assignSmartCompletionPriority(SmartCompletionItemPriority.INHERITOR_INSTANTIATION))
            }
        }
    }
}
