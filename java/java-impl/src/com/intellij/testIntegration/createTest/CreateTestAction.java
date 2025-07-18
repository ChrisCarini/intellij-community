// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.testIntegration.createTest;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.TestFrameworks;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.java.JavaBundle;
import com.intellij.model.SideEffectGuard;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.JavaProjectRootsUtil;
import com.intellij.openapi.roots.TestModuleProperties;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testIntegration.TestFramework;
import com.intellij.testIntegration.TestIntegrationUtils;
import com.intellij.util.IncorrectOperationException;
import one.util.streamex.MoreCollectors;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static com.intellij.testIntegration.createTest.CreateTestUtils.computeSuitableTestRootUrls;
import static com.intellij.testIntegration.createTest.CreateTestUtils.computeTestRoots;

public class CreateTestAction extends PsiElementBaseIntentionAction {

  private static final String CREATE_TEST_IN_THE_SAME_ROOT = "create.test.in.the.same.root";

  @Override
  public @NotNull String getText() {
    return CodeInsightBundle.message("intention.create.test");
  }

  @Override
  public @NotNull String getFamilyName() {
    return getText();
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
    if (!isAvailableForElement(element)) return false;

    PsiClass psiClass = getContainingClass(element);

    assert psiClass != null;
    PsiElement leftBrace = psiClass.getLBrace();
    if (leftBrace == null) return false;
    if (element.getTextOffset() >= leftBrace.getTextOffset()) return false;

    //TextRange declarationRange = HighlightNamesUtil.getClassDeclarationTextRange(psiClass);
    //if (!declarationRange.contains(element.getTextRange())) return false;

    return true;
  }

  public static boolean isAvailableForElement(PsiElement element) {
    if (!TestFramework.EXTENSION_NAME.hasAnyExtensions()) return false;

    if (element == null) return false;

    PsiClass psiClass = getContainingClass(element);

    if (psiClass == null) return false;
    if (psiClass instanceof PsiImplicitClass) return false;

    PsiFile file = psiClass.getContainingFile();
    if (file.getContainingDirectory() == null || JavaProjectRootsUtil.isOutsideJavaSourceRoot(file)) return false;

    if (psiClass.isAnnotationType() ||
        psiClass instanceof PsiAnonymousClass) {
      return false;
    }

    return TestFrameworks.detectFramework(psiClass) == null;
  }

  @Override
  public void invoke(final @NotNull Project project, Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
    final Module srcModule = ModuleUtilCore.findModuleForPsiElement(element);
    if (srcModule == null) return;

    final PsiClass srcClass = getContainingClass(element);

    if (srcClass == null) return;

    PsiDirectory srcDir = element.getContainingFile().getContainingDirectory();
    PsiPackage srcPackage = JavaDirectoryService.getInstance().getPackage(srcDir);

    final PropertiesComponent propertiesComponent = PropertiesComponent.getInstance(project);
    Module testModule = suggestModuleForTests(project, srcModule);
    final List<VirtualFile> testRootUrls = computeTestRoots(testModule);
    if (testRootUrls.isEmpty() && computeSuitableTestRootUrls(testModule).isEmpty()) {
      testModule = srcModule;
      if (!propertiesComponent.getBoolean(CREATE_TEST_IN_THE_SAME_ROOT)) {
        if (Messages.showOkCancelDialog(project, JavaBundle.message("dialog.message.create.test.in.the.same.source.root"),
                                        JavaBundle.message("dialog.title.no.test.roots.found"), Messages.getWarningIcon()) !=
            Messages.OK) {
          return;
        }
        SideEffectGuard.checkSideEffectAllowed(SideEffectGuard.EffectType.SETTINGS);
        propertiesComponent.setValue(CREATE_TEST_IN_THE_SAME_ROOT, true);
      }
    }

    final CreateTestDialog d = createTestDialog(project, testModule, srcClass, srcPackage);
    if (!d.showAndGet()) {
      return;
    }

    CommandProcessor.getInstance().executeCommand(project, () -> {
      TestFramework framework = d.getSelectedTestFrameworkDescriptor();
      Collection<TestGenerator> generators = TestGenerators.INSTANCE.allForLanguageWithDefault(framework.getLanguage());
      for (TestGenerator generator : generators) {
        PsiElement psiElement = DumbService.getInstance(project).withAlternativeResolveEnabled(() -> generator.generateTest(project, d));
        if (psiElement != null) break;
      }
    }, CodeInsightBundle.message("intention.create.test.title"), this);
  }

  public static @NotNull Module suggestModuleForTests(@NotNull Project project, @NotNull Module productionModule) {
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      if (productionModule.equals(TestModuleProperties.getInstance(module).getProductionModule())) {
        return module;
      }
    }

    if (computeSuitableTestRootUrls(productionModule).isEmpty()) {
      final HashSet<Module> modules = new HashSet<>();
      ModuleUtilCore.collectModulesDependsOn(productionModule, modules);
      modules.remove(productionModule);
      Module moduleWithTestRoot = StreamEx.of(modules)
        .collect(MoreCollectors.onlyOne(module -> !computeSuitableTestRootUrls(module).isEmpty())).orElse(null);
      if (moduleWithTestRoot != null) return moduleWithTestRoot;
    }

    return productionModule;
  }

  protected CreateTestDialog createTestDialog(Project project, Module srcModule, PsiClass srcClass, PsiPackage srcPackage) {
    return new CreateTestDialog(project, CodeInsightBundle.message("intention.create.test.title"), srcClass, srcPackage, srcModule);
  }

  protected static @Nullable PsiClass getContainingClass(PsiElement element) {
    PsiClass aClass = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
    if (aClass == null) return null;
    return TestIntegrationUtils.findOuterClass(element);
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }
}
