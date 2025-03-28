// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.java.JavaBundle;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.PsiUpdateModCommandQuickFix;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Dmitry Avdeev
 */
public final class DefaultAnnotationParamInspection extends AbstractBaseJavaLocalInspectionTool {

  /**
   * Allows skipping DefaultAnnotationParamInspection for specific annotations parameters
   */
  public interface IgnoreAnnotationParamSupport {
    ExtensionPointName<IgnoreAnnotationParamSupport> EP_NAME =
      ExtensionPointName.create("com.intellij.lang.jvm.ignoreAnnotationParamSupport");

    /**
     * @param annotationFQN full qualified name of the annotation
     * @param annotationParameterName name of the annotation param
     * @return true to skip inspection for {@code annotationParameterName} and annotation {@code annotationFQN}
     */
    default boolean ignoreAnnotationParam(@Nullable String annotationFQN, @NotNull String annotationParameterName) {
      return false;
    }
  }

  @Override
  public @NotNull PsiElementVisitor buildVisitor(final @NotNull ProblemsHolder holder, boolean isOnTheFly) {
    return new JavaElementVisitor() {
      @Override
      public void visitNameValuePair(final @NotNull PsiNameValuePair pair) {
        PsiAnnotationMemberValue value = pair.getValue();
        PsiReference reference = pair.getReference();
        if (reference == null) return;
        PsiElement element = reference.resolve();
        if (!(element instanceof PsiAnnotationMethod)) return;

        PsiAnnotationMemberValue defaultValue = ((PsiAnnotationMethod)element).getDefaultValue();
        if (defaultValue == null) return;

        if (AnnotationUtil.equal(value, defaultValue)) {
          PsiElement elementParent = element.getParent();
          if (elementParent instanceof PsiClass) {
            final String qualifiedName = ((PsiClass)elementParent).getQualifiedName();
            final String name = ((PsiAnnotationMethod)element).getName();
            if (ContainerUtil.exists(IgnoreAnnotationParamSupport.EP_NAME.getExtensionList(),
                                     ext -> ext.ignoreAnnotationParam(qualifiedName, name))) {
              return;
            }
          }
          holder.registerProblem(value, JavaBundle.message("inspection.message.redundant.default.parameter.value.assignment"), ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                                 createRemoveParameterFix());
        }
      }
    };
  }

  private static @NotNull LocalQuickFix createRemoveParameterFix() {
    return new PsiUpdateModCommandQuickFix() {
      @Override
      public @Nls @NotNull String getFamilyName() {
        return JavaBundle.message("quickfix.family.remove.redundant.parameter");
      }

      @Override
      protected void applyFix(@NotNull Project project, @NotNull PsiElement element, @NotNull ModPsiUpdater updater) {
        element.getParent().delete();
      }
    };
  }
}
