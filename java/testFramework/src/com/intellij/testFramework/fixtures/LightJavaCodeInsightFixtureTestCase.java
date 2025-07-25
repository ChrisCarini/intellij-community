// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.testFramework.fixtures;

import com.intellij.lang.Language;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.LanguageLevelModuleExtension;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.pom.java.AcceptedLanguageLevelsSettings;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * A JUnit 3-compatible {@link UsefulTestCase} which is based around a {@link JavaCodeInsightTestFixture}.
 * <p>
 * This class is similar to {@link JavaCodeInsightFixtureTestCase}, but with some differences:
 * <ul>
 *   <li>Uses a lightweight project setup with {@link LightProjectDescriptor}
 *   (and provides many predefined descriptors for different Java versions)</li>
 *   <li>Creates a simpler in-memory project structure</li>
 *   <li>Faster to initialize and run but with some limitations</li>
 * </ul>
 * @see LightJavaCodeInsightFixtureTestCase4
 * @see LightJavaCodeInsightFixtureTestCase5
 */
@TestDataPath("$CONTENT_ROOT/testData")
public abstract class LightJavaCodeInsightFixtureTestCase extends UsefulTestCase implements TestIndexingModeSupporter {
  protected static class ProjectDescriptor extends DefaultLightProjectDescriptor {
    protected final LanguageLevel myLanguageLevel;
    private final boolean myWithAnnotations;

    public ProjectDescriptor(@NotNull LanguageLevel languageLevel) {
      this(languageLevel, false);
    }

    public ProjectDescriptor(@NotNull LanguageLevel languageLevel, boolean withAnnotations) {
      myLanguageLevel = languageLevel;
      myWithAnnotations = withAnnotations;
    }

    @Override
    public void setUpProject(@NotNull Project project, @NotNull SetupHandler handler) throws Exception {
      if (myLanguageLevel.isPreview() || myLanguageLevel == LanguageLevel.JDK_X) {
        AcceptedLanguageLevelsSettings.allowLevel(project, myLanguageLevel);
      }
      super.setUpProject(project, handler);
    }

    @Override
    public Sdk getSdk() {
      Sdk jdk = IdeaTestUtil.getMockJdk(myLanguageLevel.toJavaVersion());
      return myWithAnnotations ? PsiTestUtil.addJdkAnnotations(jdk) : jdk;
    }

    /**
     * Calculate the name of SDK without instantiation
     */
    public @NotNull String getSdkName() {
      return IdeaTestUtil.getMockJdkName(myLanguageLevel.toJavaVersion());
    }

    @Override
    public void configureModule(@NotNull Module module, @NotNull ModifiableRootModel model, @NotNull ContentEntry contentEntry) {
      model.getModuleExtension(LanguageLevelModuleExtension.class).setLanguageLevel(myLanguageLevel);
      if (myLanguageLevel.isAtLeast(LanguageLevel.JDK_1_8)) {
        addJetBrainsAnnotationsWithTypeUse(model);
      }
      else {
        addJetBrainsAnnotations(model);
      }
    }
  }

  public static final @NotNull LightProjectDescriptor JAVA_1_4 = new ProjectDescriptor(LanguageLevel.JDK_1_4);
  public static final @NotNull LightProjectDescriptor JAVA_1_4_ANNOTATED = new ProjectDescriptor(LanguageLevel.JDK_1_4, true);
  public static final @NotNull LightProjectDescriptor JAVA_1_5 = new ProjectDescriptor(LanguageLevel.JDK_1_5);
  public static final @NotNull LightProjectDescriptor JAVA_1_6 = new ProjectDescriptor(LanguageLevel.JDK_1_6);
  public static final @NotNull LightProjectDescriptor JAVA_1_7 = new ProjectDescriptor(LanguageLevel.JDK_1_7);
  public static final @NotNull LightProjectDescriptor JAVA_1_7_ANNOTATED = new ProjectDescriptor(LanguageLevel.JDK_1_7, true);
  public static final @NotNull LightProjectDescriptor JAVA_8 = new ProjectDescriptor(LanguageLevel.JDK_1_8);
  public static final @NotNull LightProjectDescriptor JAVA_8_ANNOTATED = new ProjectDescriptor(LanguageLevel.JDK_1_8, true);
  public static final @NotNull LightProjectDescriptor JAVA_9 = new ProjectDescriptor(LanguageLevel.JDK_1_9);
  public static final @NotNull LightProjectDescriptor JAVA_9_ANNOTATED = new ProjectDescriptor(LanguageLevel.JDK_1_9, true);
  public static final @NotNull LightProjectDescriptor JAVA_11 = new ProjectDescriptor(LanguageLevel.JDK_11);
  public static final @NotNull LightProjectDescriptor JAVA_11_ANNOTATED = new ProjectDescriptor(LanguageLevel.JDK_11, true);
  public static final @NotNull LightProjectDescriptor JAVA_12 = new ProjectDescriptor(LanguageLevel.JDK_12);
  public static final @NotNull LightProjectDescriptor JAVA_13 = new ProjectDescriptor(LanguageLevel.JDK_13);
  public static final @NotNull LightProjectDescriptor JAVA_14 = new ProjectDescriptor(LanguageLevel.JDK_14);
  public static final @NotNull LightProjectDescriptor JAVA_15 = new ProjectDescriptor(LanguageLevel.JDK_15);
  public static final @NotNull LightProjectDescriptor JAVA_16 = new ProjectDescriptor(LanguageLevel.JDK_16);
  public static final @NotNull LightProjectDescriptor JAVA_17 = new ProjectDescriptor(LanguageLevel.JDK_17);
  public static final @NotNull LightProjectDescriptor JAVA_17_ANNOTATED = new ProjectDescriptor(LanguageLevel.JDK_17, true);
  public static final @NotNull LightProjectDescriptor JAVA_18 = new ProjectDescriptor(LanguageLevel.JDK_18);
  public static final @NotNull LightProjectDescriptor JAVA_19 = new ProjectDescriptor(LanguageLevel.JDK_19);
  public static final @NotNull LightProjectDescriptor JAVA_20 = new ProjectDescriptor(LanguageLevel.JDK_20);
  public static final @NotNull LightProjectDescriptor JAVA_21 = new ProjectDescriptor(LanguageLevel.JDK_21_PREVIEW);
  public static final @NotNull LightProjectDescriptor JAVA_21_ANNOTATED = new ProjectDescriptor(LanguageLevel.JDK_21_PREVIEW, true);
  public static final @NotNull LightProjectDescriptor JAVA_22 = new ProjectDescriptor(LanguageLevel.JDK_22_PREVIEW);
  public static final @NotNull LightProjectDescriptor JAVA_23 = new ProjectDescriptor(LanguageLevel.JDK_23_PREVIEW);
  public static final @NotNull LightProjectDescriptor JAVA_24 = new ProjectDescriptor(LanguageLevel.JDK_24);
  public static final @NotNull LightProjectDescriptor JAVA_25 = new ProjectDescriptor(LanguageLevel.JDK_25);
  public static final @NotNull LightProjectDescriptor JAVA_X = new ProjectDescriptor(LanguageLevel.JDK_X);

  public static final @NotNull LightProjectDescriptor JAVA_LATEST = new ProjectDescriptor(LanguageLevel.HIGHEST) {
    @Override
    public Sdk getSdk() {
      return IdeaTestUtil.getMockJdk17();
    }
  };
  
  public static final @NotNull LightProjectDescriptor JAVA_LATEST_WITH_LATEST_JDK = new ProjectDescriptor(LanguageLevel.HIGHEST);

  protected JavaCodeInsightTestFixture myFixture;

  private @NotNull IndexingMode myIndexingMode = IndexingMode.SMART;

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
    TestFixtureBuilder<IdeaProjectTestFixture> fixtureBuilder = factory.createLightFixtureBuilder(getProjectDescriptor(), getTestName(false));
    IdeaProjectTestFixture fixture = fixtureBuilder.getFixture();
    myFixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(fixture, getTempDirFixture());
    myFixture = JavaIndexingModeCodeInsightTestFixture.Companion.wrapFixture(myFixture, getIndexingMode());

    myFixture.setTestDataPath(getTestDataPath());
    myFixture.setUp();

    IdeaTestUtil.setProjectLanguageLevel(getProject(), LanguageLevel.JDK_1_6);
  }

  protected @NotNull TempDirTestFixture getTempDirFixture() {
    IdeaTestExecutionPolicy policy = IdeaTestExecutionPolicy.current();
    return policy != null
           ? policy.createTempDirTestFixture()
           : new LightTempDirTestFixtureImpl(true);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      if (myFixture != null) {
        myFixture.tearDown();
      }
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      myFixture = null;
      super.tearDown();
    }
  }

  /**
   * Returns a relative path to the test data.
   */
  protected String getBasePath() {
    return "";
  }

  protected @NotNull LightProjectDescriptor getProjectDescriptor() {
    return JAVA_LATEST;
  }

  /**
   * Return an absolute path to the test data. Not intended to be overridden.
   *
   * @see #getBasePath()
   */
  protected String getTestDataPath() {
    String communityPath = PlatformTestUtil.getCommunityPath().replace(File.separatorChar, '/');
    String path = communityPath + getBasePath();
    return new File(path).exists() ? path : communityPath + "/../" + getBasePath();
  }

  protected Project getProject() {
    return myFixture.getProject();
  }

  protected PsiFile getFile() { return myFixture.getFile(); }

  protected Editor getEditor() { return myFixture.getEditor(); }

  protected Module getModule() {
    return myFixture.getModule();
  }

  protected PsiManager getPsiManager() {
    return PsiManager.getInstance(getProject());
  }

  public PsiElementFactory getElementFactory() {
    return JavaPsiFacade.getElementFactory(getProject());
  }

  protected PsiFile createLightFile(FileType fileType, String text) {
    return PsiFileFactory.getInstance(getProject()).createFileFromText("a." + fileType.getDefaultExtension(), fileType, text);
  }

  public PsiFile createLightFile(String fileName, Language language, String text) {
    return PsiFileFactory.getInstance(getProject()).createFileFromText(fileName, language, text, false, true);
  }

  @Override
  public void setIndexingMode(@NotNull IndexingMode mode) {
    myIndexingMode = mode;
  }

  @Override
  public @NotNull IndexingMode getIndexingMode() {
    return myIndexingMode;
  }
}
