// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInspection.dataFlow.inference.JavaSourceInference;
import com.intellij.codeInspection.dataFlow.types.DfReferenceType;
import com.intellij.codeInspection.dataFlow.types.DfType;
import com.intellij.codeInspection.dataFlow.types.DfTypes;
import com.intellij.java.analysis.JavaAnalysisBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.psi.impl.source.PsiMethodImpl;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.psiutils.ClassUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.PropertyKey;

import java.util.Collections;
import java.util.List;

/**
 * Lattice:
 *     UNKNOWN
 *     |      \
 * MUTABLE   MUST_NOT_MODIFY
 *     |       |
 *     |     UNMODIFIABLE_VIEW
 *     |       |
 *     |     UNMODIFIABLE
 *     \      /
 *     BOTTOM (null)
 */
public enum Mutability {
  /**
   * Mutability is not known; probably value can be mutated; TOP
   */
  UNKNOWN("mutability.unknown", null),
  /**
   * A value is known to be mutable (e.g. elements are sometimes added to the collection)
   */
  MUTABLE("mutability.modifiable", null),
  /**
   * A value that could be mutable, but must not be modified due to contract
   * (e.g. a parameter of pure method)
   */
  MUST_NOT_MODIFY("mutability.must.not.modify", null),
  /**
   * A value is known to be an immutable view over a possibly mutable value: it cannot be mutated directly using this
   * reference; however subsequent reads (e.g. {@link java.util.Collection#size}) may return different results if the
   * underlying value is mutated by somebody else.
   */
  UNMODIFIABLE_VIEW("mutability.unmodifiable.view", "org.jetbrains.annotations.UnmodifiableView"),
  /**
   * A value is known to be immutable. For collection no elements could be added, removed or altered (though if collection
   * contains mutable elements, they still could be mutated).
   */
  UNMODIFIABLE("mutability.unmodifiable", "org.jetbrains.annotations.Unmodifiable");

  public static final @NotNull String UNMODIFIABLE_ANNOTATION = UNMODIFIABLE.myAnnotation;
  public static final @NotNull String UNMODIFIABLE_VIEW_ANNOTATION = UNMODIFIABLE_VIEW.myAnnotation;
  private final @PropertyKey(resourceBundle = JavaAnalysisBundle.BUNDLE) String myResourceKey;
  private final String myAnnotation;
  private final Key<CachedValue<PsiAnnotation>> myKey;

  Mutability(@PropertyKey(resourceBundle = JavaAnalysisBundle.BUNDLE) String resourceKey, String annotation) {
    myResourceKey = resourceKey;
    myAnnotation = annotation;
    myKey = annotation == null ? null : Key.create(annotation);
  }

  public DfReferenceType asDfType() {
    return DfTypes.customObject(TypeConstraints.TOP, DfaNullability.UNKNOWN, this, null, DfType.BOTTOM);
  }

  public @NotNull @Nls String getPresentationName() {
    return JavaAnalysisBundle.message(myResourceKey);
  }

  public boolean isUnmodifiable() {
    return this == UNMODIFIABLE || this == UNMODIFIABLE_VIEW;
  }

  public boolean canBeModified() {
    return this == MUTABLE || this == UNKNOWN;
  }

  public @NotNull Mutability join(@NotNull Mutability other) {
    if (this == other) return this;
    if (this == UNKNOWN || other == UNKNOWN) return UNKNOWN;
    if (this == MUTABLE || other == MUTABLE) return UNKNOWN;
    if (this == MUST_NOT_MODIFY || other == MUST_NOT_MODIFY) return MUST_NOT_MODIFY;
    if (this == UNMODIFIABLE_VIEW || other == UNMODIFIABLE_VIEW) return UNMODIFIABLE_VIEW;
    return UNMODIFIABLE;
  }

  /**
   * @param other mutability to meet
   * @return resulting mutability; null if bottom
   */
  public @Nullable Mutability meet(@NotNull Mutability other) {
    if (this == other) return this;
    if (this == UNKNOWN) return other;
    if (other == UNKNOWN) return this;
    if (this == MUTABLE || other == MUTABLE) return null;
    if (this == UNMODIFIABLE || other == UNMODIFIABLE) return UNMODIFIABLE;
    if (this == UNMODIFIABLE_VIEW || other == UNMODIFIABLE_VIEW) return UNMODIFIABLE_VIEW;
    return MUST_NOT_MODIFY;
  }

  public @Nullable PsiAnnotation asAnnotation(Project project) {
    if (myAnnotation == null) return null;
    return CachedValuesManager.getManager(project).getCachedValue(project, myKey, () -> {
      PsiAnnotation annotation = JavaPsiFacade.getElementFactory(project).createAnnotationFromText("@" + myAnnotation, null);
      ((LightVirtualFile)annotation.getContainingFile().getViewProvider().getVirtualFile()).setWritable(false);
      return CachedValueProvider.Result.create(annotation, ModificationTracker.NEVER_CHANGED);
    }, false);
  }

  /**
   * Returns a mutability of the supplied element, if known. The element could be a method
   * (in this case the return value mutability is returned), a method parameter
   * (the returned mutability will reflect whether the method can mutate the parameter),
   * or a field (in this case the mutability could be obtained from its initializer).
   *
   * @param owner an element to check the mutability
   * @return a Mutability enum value; {@link #UNKNOWN} if cannot be determined or specified element type is not supported.
   */
  public static @NotNull Mutability getMutability(@NotNull PsiModifierListOwner owner) {
    if (owner instanceof LightElement) return UNKNOWN;
    return CachedValuesManager.getCachedValue(owner, () ->
      CachedValueProvider.Result.create(calcMutability(owner), owner, PsiModificationTracker.MODIFICATION_COUNT));
  }

  private static @NotNull Mutability calcMutability(@NotNull PsiModifierListOwner owner) {
    if (owner instanceof PsiParameter parameter
        && owner.getParent() instanceof PsiParameterList list
        && list.getParent() instanceof PsiMethod method) {
      JavaMethodContractUtil.ContractInfo contractInfo = JavaMethodContractUtil.getContractInfo(method);
      if (contractInfo.isExplicit()) {
        int index = list.getParameterIndex(parameter);
        MutationSignature signature = contractInfo.getMutationSignature();
        if (signature.mutatesArg(index)) {
          return MUTABLE;
        }
        else if (signature.preservesArg(index)) {
          return MUST_NOT_MODIFY;
        }
      }
      return UNKNOWN;
    }
    if (AnnotationUtil.isAnnotated(owner, Collections.singleton(UNMODIFIABLE_ANNOTATION),
                                   AnnotationUtil.CHECK_HIERARCHY |
                                   AnnotationUtil.CHECK_EXTERNAL |
                                   AnnotationUtil.CHECK_INFERRED)) {
      return UNMODIFIABLE;
    }
    if (AnnotationUtil.isAnnotated(owner, Collections.singleton(UNMODIFIABLE_VIEW_ANNOTATION),
                                   AnnotationUtil.CHECK_HIERARCHY |
                                   AnnotationUtil.CHECK_EXTERNAL |
                                   AnnotationUtil.CHECK_INFERRED)) {
      return UNMODIFIABLE_VIEW;
    }
    if (owner instanceof PsiField field && owner.hasModifierProperty(PsiModifier.FINAL)) {
      List<PsiExpression> initializers = ContainerUtil.createMaybeSingletonList(field.getInitializer());
      if (initializers.isEmpty() && !owner.hasModifierProperty(PsiModifier.STATIC)) {
        initializers = DfaPsiUtil.findAllConstructorInitializers(field);
      }
      return calcMutability(initializers);
    }
    else if (owner instanceof PsiRecordComponent component) {
      return calcMutability(DfaPsiUtil.findAllConstructorInitializers(component));
    }
    return owner instanceof PsiMethodImpl method ? JavaSourceInference.inferMutability(method) : UNKNOWN;
  }

  private static @NotNull Mutability calcMutability(List<PsiExpression> expressions) {
    expressions = StreamEx.of(expressions).flatMap(ExpressionUtils::nonStructuralChildren).toList();
    if (expressions.isEmpty()) return UNKNOWN;
    Mutability mutability = UNMODIFIABLE;
    for (PsiExpression initializer : expressions) {
      Mutability newMutability = UNKNOWN;
      PsiType type = initializer.getType();
      if (ClassUtils.isImmutable(type) || (type != null && type.hasAnnotation(UNMODIFIABLE_ANNOTATION))) {
        newMutability = UNMODIFIABLE;
      } else if (initializer instanceof PsiMethodCallExpression call) {
        PsiMethod method = call.resolveMethod();
        newMutability = method == null ? UNKNOWN : getMutability(method);
      }
      mutability = mutability.join(newMutability);
      if (!mutability.isUnmodifiable()) break;
    }
    return mutability;
  }

  public static Mutability fromDfType(DfType dfType) {
    return dfType instanceof DfReferenceType referenceType ? referenceType.getMutability() : UNKNOWN;
  }
}
