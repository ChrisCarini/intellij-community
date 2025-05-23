// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInsight.daemon;

import com.intellij.JavaTestUtil;
import com.intellij.codeInsight.daemon.LightDaemonAnalyzerTestCase;
import com.intellij.codeInspection.javaDoc.JavaDocReferenceInspection;
import com.intellij.codeInspection.javaDoc.JavadocDeclarationInspection;
import com.intellij.codeInspection.javaDoc.ReplaceWithJavadocInspection;
import com.intellij.model.psi.PsiSymbolReference;
import com.intellij.openapi.paths.UrlReference;
import com.intellij.openapi.paths.WebReference;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vcs.IssueNavigationConfiguration;
import com.intellij.openapi.vcs.IssueNavigationLink;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class JavadocDeclarationHighlightingTest extends LightDaemonAnalyzerTestCase {
  private JavadocDeclarationInspection myInspection;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myInspection = new JavadocDeclarationInspection();
    myInspection.IGNORE_THROWS_DUPLICATE = false;
    enableInspectionTools(myInspection, new JavaDocReferenceInspection(), new ReplaceWithJavadocInspection());
  }

  @Override
  protected Sdk getProjectJDK() {
    return IdeaTestUtil.getMockJdk9();
  }

  @NotNull
  @Override
  protected String getTestDataPath() {
    return JavaTestUtil.getJavaTestDataPath() + "/codeInsight/daemonCodeAnalyzer/javadocDeclaration/";
  }

  public void testJavadocPeriod() { myInspection.IGNORE_PERIOD_PROBLEM = false; doTest(); }
  public void testJavadocPeriod1() { myInspection.IGNORE_PERIOD_PROBLEM = false; doTest(); }
  public void testJavadocPeriod2() { myInspection.IGNORE_PERIOD_PROBLEM = false; doTest(); }
  public void testInlineTagAsDescription() { doTest(); }
  public void testParam0() { doTest(); }
  public void testParam1() { doTest(); }
  public void testParam2() { doTest(); }
  public void testParam3() { doTest(); }
  public void testParam4() { doTest(); }
  public void testRecordParamJava16() { doTest(); }
  public void testSee0() { doTest(); }
  public void testSee1() { doTest(); }
  public void testSee2() { doTest(); }
  public void testSee3() { doTest(); }
  public void testSee4() { doTest(); }
  public void testSee5() { doTest(); }
  public void testSee6() { doTest(); }
  public void testLinkToItself() { doTest(); }
  public void testLinkToMethodNoParams() { doTest(); }
  public void testSeeConstants() { doTest(); }
  public void testSeeNonRefs() { doTest(); }
  public void testReturn0() { doTest(); }
  public void testReturn1() { doTest(); }
  public void testException0() { doTest(); }
  public void testException1() { doTest(); }
  public void testException2() { doTest(); }
  public void testException3() { doTest(); }
  public void testException4() { doTest(); }
  public void testExceptionWrongDot() { doTest(); }
  public void testExceptionWrongDotIgnored() { myInspection.IGNORE_SYNTAX_ERRORS = true; doTest(); }
  public void testInheritJavaDoc() { setLanguageLevel(LanguageLevel.JDK_1_3); doTest(); }
  public void testLink0() { doTest(); }
  public void testLinkFromInnerClassToSelfMethod() { doTest(); }
  public void testValueBadReference() { doTest(); }
  public void testValueGoodReference() { doTest(); }
  public void testValueReference14() { setLanguageLevel(LanguageLevel.JDK_1_4); doTest(); }
  public void testValueEmpty() { doTest(); }
  public void testValueNotOnField() { doTest(); }
  public void testValueNotOnStaticField() { doTest(); }
  public void testValueOnNotInitializedField() { doTest(); }
  public void testEnumValueOfReference() { doTest(); }
  public void testPackageInfo2() { doTest("packageInfo/p2/package-info.java"); }
  public void testPackageInfo4() { doTest("packageInfo/p4/package-info.java"); }
  public void testJava18Tags() { doTest(); }
  public void testJava19Tags() { setLanguageLevel(LanguageLevel.JDK_1_9); doTest(); }
  public void testJava12Tags() { setLanguageLevel(LanguageLevel.JDK_12); doTest(); }
  public void testModuleInfoTags() { setLanguageLevel(LanguageLevel.JDK_1_9); doTest("moduleInfo/m1/module-info.java"); }
  public void testModule() { setLanguageLevel(LanguageLevel.JDK_1_9); doTest("moduleInfo/m2/module-info.java"); }
  public void testUnknownInlineTag() { doTest(); }
  public void testUnknownTags() { doTest(); }
  public void testBadCharacters() { doTest(); }
  public void testVararg() { doTest(); }
  public void testInnerClassReferenceInSignature() { doTest(); }
  public void testBadReference() { doTest(); }
  public void testMissingReturnDescription() { doTest(); }
  public void testDoubleParenthesesInCode() { doTest(); }
  public void testDuplicateParam0() { doTest(); }
  public void testDuplicateParam1() { doTest(); }
  public void testDuplicateParam2() { doTest(); }
  public void testDuplicateReturn() { doTest(); }
  public void testDuplicateDeprecated() { doTest(); }
  public void testDuplicateSerial() { doTest(); }
  public void testDuplicateThrows() { doTest(); }
  public void testMissedTags() { doTest(); }
  public void testDoubleMissedTags() { doTest(); }
  public void testMisplacedThrowsTag() { doTest(); }
  public void testGenericsParams() { doTest(); }
  public void testEnumConstructor() { doTest(); }
  public void testIgnoreDuplicateThrows() { myInspection.IGNORE_THROWS_DUPLICATE = true; doTest(); }
  public void testIgnoreAccessors() { doTest(); }
  public void testAuthoredMethod() { doTest(); }
  public void testThrowsInheritDoc() { doTest(); }
  public void testSnippetInlineTag() {  doTest(); }
  public void testSnippet() { doTest(); }
  public void testSnippetMethod() { doTest(); }
  public void testSnippetInstructions() { doTest(); }
  public void testSnippetMarkup() { doTest(); }
  public void testEmptySnippet() { doTest(); }
  public void testOnlyEmptyLinesInSnippet() { doTest(); }
  public void testSnippetInstructionsWithUnhandledThrowable() { doTest(); }
  public void testJavaDocWithSpaces() { doTest(); }
  public void testCommentsAfterArguments() { doTest(); }
  public void testAnonymousClass() { doTest(); }
  public void testCommentsBeforeArguments() { doTest(); }
  public void testCommentsInModifierList() { doTest(); }
  public void testCommentsBeforeType() { doTest(); }
  public void testCommentsBeforeName() { doTest(); }

  public void testIssueLinksInJavaDoc() {
    IssueNavigationConfiguration navigationConfiguration = IssueNavigationConfiguration.getInstance(getProject());
    List<IssueNavigationLink> oldLinks = navigationConfiguration.getLinks();
    try {
      navigationConfiguration.setLinks(List.of(new IssueNavigationLink("ABC-\\d+", "http://example.com/$0"),
                                               new IssueNavigationLink("INVALID-\\d+", "http://example.com/$0/$1")));
      configureByFile(getTestName(false) + ".java");
      List<String> expected =
        List.of("http://example.com/ABC-1123", "http://example.com/ABC-2", "http://example.com/ABC-22", "http://example.com/ABC-11");
      if (Registry.is("ide.symbol.url.references")) {
        List<UrlReference> refs = PlatformTestUtil.collectUrlReferences(getFile());
        assertEquals(expected, ContainerUtil.map(refs, UrlReference::getUrl));
      }
      else {
        List<WebReference> refs = PlatformTestUtil.collectWebReferences(getFile());
        assertTrue(ContainerUtil.and(refs, PsiReferenceBase::isSoft));
        assertEquals(expected, ContainerUtil.map(refs, WebReference::getUrl));
      }
    }
    finally {
      navigationConfiguration.setLinks(oldLinks);
    }
  }

  public void testLinksInJavaDoc() {
    configureByFile(getTestName(false) + ".java");
    Set<String> expected = ContainerUtil.newHashSet(
      "http://www.unicode.org/unicode/standard/standard.html",
      "http://docs.oracle.com/javase/7/docs/tech-notes/guides/lang/cl-mt.html",
      "https://youtrack.jetbrains.com/issue/IDEA-131621",
      "mailto:webmaster@jetbrains.com");
    if (Registry.is("ide.symbol.url.references")) {
      List<UrlReference> refs = PlatformTestUtil.collectUrlReferences(getFile());
      assertEquals(expected, refs.stream().map(PsiSymbolReference::getReferenceText).collect(Collectors.toSet()));
    }
    else {
      List<WebReference> refs = PlatformTestUtil.collectWebReferences(getFile());
      assertTrue(ContainerUtil.and(refs, PsiReferenceBase::isSoft));
      assertEquals(expected, refs.stream().map(PsiReferenceBase::getCanonicalText).collect(Collectors.toSet()));
    }
  }

  private void doTest() {
    doTest(getTestName(false) + ".java");
  }

  private void doTest(String testFileName) {
    doTest(testFileName, true, false);
  }
}
