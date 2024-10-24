// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.compiler;

import org.jetbrains.plugins.groovy.GroovyProjectDescriptors;
import org.jetbrains.plugins.groovy.TestLibrary;

public class GrEclipse2522Test extends GrEclipseTestBase {
  @Override
  protected TestLibrary getGroovyLibrary() {
    return GroovyProjectDescriptors.LIB_GROOVY_2_5;
  }

  @Override
  protected String getGrEclipseArtifactID() {
    return "org.codehaus.groovy:groovy-eclipse-batch:2.5.22-01";
  }
}
