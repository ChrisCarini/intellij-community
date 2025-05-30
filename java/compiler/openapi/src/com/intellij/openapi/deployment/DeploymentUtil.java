// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.deployment;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.descriptors.ConfigFile;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

public abstract class DeploymentUtil {
  public static DeploymentUtil getInstance() {
    return ApplicationManager.getApplication().getService(DeploymentUtil.class);
  }

  public static String trimForwardSlashes(@NotNull String path) {
    while (!path.isEmpty() && (path.charAt(0) == '/' || path.charAt(0) == File.separatorChar)) {
      path = path.substring(1);
    }
    return path;
  }

  public static String concatPaths(String... paths) {
    final StringBuilder builder = new StringBuilder();
    for (String path : paths) {
      if (path.isEmpty()) continue;

      final int len = builder.length();
      if (len > 0 && builder.charAt(len - 1) != '/' && builder.charAt(len - 1) != File.separatorChar) {
        builder.append('/');
      }
      builder.append(len != 0 ? trimForwardSlashes(path) : path);
    }
    return builder.toString();
  }

  public static String appendToPath(@NotNull String basePath, @NotNull String relativePath) {
    final boolean endsWithSlash = StringUtil.endsWithChar(basePath, '/') || StringUtil.endsWithChar(basePath, '\\');
    final boolean startsWithSlash = StringUtil.startsWithChar(relativePath, '/') || StringUtil.startsWithChar(relativePath, '\\');
    String tail;
    if (endsWithSlash && startsWithSlash) {
      tail = trimForwardSlashes(relativePath);
    }
    else if (!endsWithSlash && !startsWithSlash && !basePath.isEmpty() && !relativePath.isEmpty()) {
      tail = "/" + relativePath;
    }
    else {
      tail = relativePath;
    }
    return basePath + tail;
  }

  public abstract @Nullable @Nls String getConfigFileErrorMessage(ConfigFile configFile);
}
