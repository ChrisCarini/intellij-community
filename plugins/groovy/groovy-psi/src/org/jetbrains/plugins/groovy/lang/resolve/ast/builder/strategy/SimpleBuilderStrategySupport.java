// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.resolve.ast.builder.strategy;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.impl.light.LightMethodBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.impl.PsiImplUtil;
import org.jetbrains.plugins.groovy.lang.resolve.ast.builder.BuilderAnnotationContributor;
import org.jetbrains.plugins.groovy.transformations.TransformationContext;

import static org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil.createType;

public final class SimpleBuilderStrategySupport extends BuilderAnnotationContributor {

  public static final String SIMPLE_STRATEGY_NAME = "SimpleStrategy";

  @Override
  public void applyTransformation(@NotNull TransformationContext context) {
    GrTypeDefinition typeDefinition = context.getCodeClass();
    final PsiAnnotation annotation = PsiImplUtil.getAnnotation(typeDefinition, BUILDER_FQN);
    if (!isApplicable(annotation, SIMPLE_STRATEGY_NAME)) return;
    if (isIncludeSuperProperties(annotation)) return;
    for (GrField field : typeDefinition.getCodeFields()) {
      context.addMethod(createFieldSetter(typeDefinition, field, annotation));
    }
  }

  public static @NotNull LightMethodBuilder createFieldSetter(@NotNull PsiClass builderClass,
                                                              @NotNull GrVariable field,
                                                              @NotNull PsiAnnotation annotation) {
    final String name = field.getName();
    final LightMethodBuilder fieldSetter = new LightMethodBuilder(builderClass.getManager(), getFieldMethodName(annotation, name));
    fieldSetter.addModifier(PsiModifier.PUBLIC);
    fieldSetter.addParameter(name, field.getType(), false);
    fieldSetter.setMethodReturnType(createType(builderClass));
    fieldSetter.setNavigationElement(field);
    fieldSetter.setOriginInfo(ORIGIN_INFO);
    return fieldSetter;
  }

  public static @NotNull String getFieldMethodName(@NotNull PsiAnnotation annotation, @NotNull String fieldName) {
    final String prefix = AnnotationUtil.getDeclaredStringAttributeValue(annotation, "prefix");
    return prefix == null ? "set" + StringUtil.capitalize(fieldName)
                          : prefix.isEmpty() ? fieldName
                                             : prefix + StringUtil.capitalize(fieldName);
  }
}
