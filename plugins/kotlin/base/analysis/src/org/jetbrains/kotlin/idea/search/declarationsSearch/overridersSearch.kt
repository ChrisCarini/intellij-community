// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.search.declarationsSearch

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.*
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.AllOverridingMethodsSearch
import com.intellij.psi.search.searches.DirectClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.util.EmptyQuery
import com.intellij.util.MergeQuery
import com.intellij.util.Processor
import com.intellij.util.Query
import org.jetbrains.kotlin.asJava.classes.KtFakeLightClass
import org.jetbrains.kotlin.asJava.classes.KtFakeLightMethod
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.base.util.allScope
import org.jetbrains.kotlin.idea.base.util.excludeKotlinSources
import org.jetbrains.kotlin.idea.base.util.useScope
import org.jetbrains.kotlin.idea.search.KotlinSearchUsagesSupport.SearchUtils.forEachKotlinOverride
import org.jetbrains.kotlin.idea.search.KotlinSearchUsagesSupport.SearchUtils.isOverridable
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import java.util.*

fun PsiElement.isOverridableElement(): Boolean = when (this) {
    is PsiMethod -> PsiUtil.canBeOverridden(this)
    is KtDeclaration -> isOverridable()
    else -> false
}

fun HierarchySearchRequest<*>.searchOverriders(): Query<PsiMethod> {
    val psiMethods = runReadAction { originalElement.toLightMethods() }
    if (psiMethods.isEmpty()) return EmptyQuery.getEmptyQuery()

    return psiMethods
        .map { psiMethod -> KotlinPsiMethodOverridersSearch.search(copy(psiMethod)) }
        .reduce { query1, query2 -> MergeQuery(query1, query2) }
}

object KotlinPsiMethodOverridersSearch : HierarchySearch<PsiMethod>(PsiMethodOverridingHierarchyTraverser) {
    fun searchDirectOverriders(psiMethod: PsiMethod): Iterable<PsiMethod> {
        fun PsiMethod.isAcceptable(inheritor: PsiClass, baseMethod: PsiMethod, baseClass: PsiClass): Boolean =
            when {
                hasModifierProperty(PsiModifier.STATIC) -> false
                baseMethod.hasModifierProperty(PsiModifier.PACKAGE_LOCAL) ->
                    JavaPsiFacade.getInstance(project).arePackagesTheSame(baseClass, inheritor)
                else -> true
            }

        val psiClass = psiMethod.containingClass ?: return Collections.emptyList()

        val classToMethod = LinkedHashMap<PsiClass, PsiMethod>()
        val classTraverser = object : HierarchyTraverser<PsiClass> {
            override fun nextElements(current: PsiClass): Iterable<PsiClass> =
                DirectClassInheritorsSearch.search(
                    current,
                    current.project.allScope(),
                    /* includeAnonymous = */ true
                ).asIterable()

            override fun shouldDescend(element: PsiClass): Boolean =
                element.isInheritable() && !classToMethod.containsKey(element)
        }

        classTraverser.forEach(psiClass) { inheritor ->
            val substitutor = TypeConversionUtil.getSuperClassSubstitutor(psiClass, inheritor, PsiSubstitutor.EMPTY)
            val signature = psiMethod.getSignature(substitutor)
            val candidate = MethodSignatureUtil.findMethodBySuperSignature(inheritor, signature, false)
            if (candidate != null && candidate.isAcceptable(inheritor, psiMethod, psiClass)) {
                classToMethod[inheritor] = candidate
            }
        }

        return classToMethod.values
    }

    override fun isApplicable(request: HierarchySearchRequest<PsiMethod>): Boolean =
        runReadAction { request.originalElement.isOverridableElement() }

    override fun doSearchDirect(request: HierarchySearchRequest<PsiMethod>, consumer: Processor<in PsiMethod>) {
        searchDirectOverriders(request.originalElement).forEach { method -> consumer.process(method) }
    }
}

object PsiMethodOverridingHierarchyTraverser : HierarchyTraverser<PsiMethod> {
    override fun nextElements(current: PsiMethod): Iterable<PsiMethod> = KotlinPsiMethodOverridersSearch.searchDirectOverriders(current)
    override fun shouldDescend(element: PsiMethod): Boolean = PsiUtil.canBeOverridden(element)
}

fun PsiElement.toPossiblyFakeLightMethods(): List<PsiMethod> {
    if (this is PsiMethod) return listOf(this)

    val element = unwrapped ?: return emptyList()

    val lightMethods = element.toLightMethods()
    if (lightMethods.isNotEmpty()) return lightMethods

    return if (element is KtNamedDeclaration) listOfNotNull(KtFakeLightMethod.get(element)) else emptyList()
}

fun KtNamedDeclaration.forEachOverridingElement(
    scope: SearchScope = runReadAction { useScope() },
    searchDeeply: Boolean = true,
    processor: (superMember: PsiElement, overridingMember: PsiElement) -> Boolean
): Boolean {
    val ktClass = runReadAction { containingClassOrObject as? KtClass } ?: return true
    toLightMethods().forEach { baseMethod ->
        if (!OverridingMethodsSearch.search(baseMethod, scope.excludeKotlinSources(project), searchDeeply).asIterable().all { processor(baseMethod, it) }) {
            return false
        }
    }

    return forEachKotlinOverride(ktClass, listOf(this), scope, searchDeeply) { baseElement, overrider ->
        processor(baseElement, overrider)
    }
}

fun KtNamedDeclaration.hasOverridingElement(): Boolean {
    var hasUsage = false
    forEachOverridingElement(searchDeeply = false) { _, _ ->
        hasUsage = true
        false
    }

    return hasUsage
}

fun PsiClass.forEachDeclaredMemberOverride(processor: (superMember: PsiElement, overridingMember: PsiElement) -> Boolean) {
    val scope = runReadAction { useScope() }

    if (this !is KtFakeLightClass) {
        AllOverridingMethodsSearch.search(this, scope.excludeKotlinSources(project)).asIterable().all { processor(it.first, it.second) }
    }

    val ktClass = unwrapped as? KtClass ?: return
    val members = ktClass.declarations.filterIsInstance<KtNamedDeclaration>() +
            ktClass.primaryConstructorParameters.filter { it.hasValOrVar() }
    forEachKotlinOverride(ktClass, members, scope, searchDeeply = true, processor)
}