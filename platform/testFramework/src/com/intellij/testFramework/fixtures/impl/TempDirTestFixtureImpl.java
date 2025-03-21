// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.testFramework.fixtures.impl;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileFilter;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.HeavyTestHelper;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertNotNull;

/**
 * @author Dmitry Avdeev
 */
public class TempDirTestFixtureImpl extends BaseFixture implements TempDirTestFixture {
  private Path myTempDir;

  @Override
  public @NotNull VirtualFile copyAll(@NotNull String dataDir, @NotNull String targetDir) {
    return copyAll(dataDir, targetDir, VirtualFileFilter.ALL);
  }

  @Override
  public @NotNull VirtualFile copyAll(@NotNull String dataDir, @NotNull String targetDir, @NotNull VirtualFileFilter filter) {
    createTempDirectory();
    return WriteAction.computeAndWait(() -> {
      try {
        VirtualFile tempDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(myTempDir.toString());
        assertNotNull(tempDir);
        if (!targetDir.isEmpty()) {
          tempDir = VfsUtil.createDirectoryIfMissing(tempDir, targetDir.replace('\\', '/'));
        }
        VirtualFile from = LocalFileSystem.getInstance().refreshAndFindFileByPath(dataDir);
        assertNotNull(dataDir + " not found", from);
        VfsUtil.copyDirectory(null, from, tempDir, filter);
        return tempDir;
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  @Override
  public @NotNull String getTempDirPath() {
    return createTempDirectory().toString();
  }

  public Path createTempFile(@NotNull String path) throws IOException {
    String prefix = StringUtil.getPackageName(path);
    if (prefix.length() < 3) {
      prefix += "___";
    }

    String suffix = "." + StringUtil.getShortName(path);
    Path file = FileUtil.createTempFile(createTempDirectory().toFile(), prefix, suffix, true).toPath();
    VfsRootAccess.allowRootAccess(getTestRootDisposable(), file.toString());
    return file;
  }

  @Override
  public VirtualFile getFile(@NotNull String path) {
    String fullPath = myTempDir.toString() + '/' + path;
    VfsRootAccess.allowRootAccess(getTestRootDisposable(), fullPath);
    VirtualFile vFile = WriteAction.computeAndWait(() -> LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath));
    IndexingTestUtil.waitUntilIndexesAreReadyInAllOpenedProjects();
    return vFile;
  }

  @Override
  public @NotNull VirtualFile createFile(@NotNull String name) {
    Path file;
    try {
      file = createEmptyTempFile(createTempDirectory(), name);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }

    VfsRootAccess.allowRootAccess(getTestRootDisposable(), file.toString());
    VirtualFile createdVFile = WriteAction.computeAndWait(() -> {
      return LocalFileSystem.getInstance().refreshAndFindFileByPath(FileUtil.toSystemIndependentName(file.toString()));
    });
    IndexingTestUtil.waitUntilIndexesAreReadyInAllOpenedProjects();
    return createdVFile;
  }

  public static @NotNull Path createEmptyTempFile(@NotNull Path parent, @NotNull String name) throws IOException {
    Path file = resolvePath(parent, name);
    Files.createDirectories(file.getParent());
    Files.newByteChannel(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).close();
    return file;
  }

  private static @NotNull Path resolvePath(@NotNull Path parent, @NotNull String name) {
    return parent.resolve(name.startsWith("/") ? name.substring(1) : name).normalize();
  }

  @Override
  public @NotNull VirtualFile findOrCreateDir(@NotNull String name) throws IOException {
    String path = resolvePath(createTempDirectory(), name).toString();
    VfsRootAccess.allowRootAccess(getTestRootDisposable(), path);
    return VfsUtil.createDirectories(path);
  }

  @Override
  public @NotNull VirtualFile createFile(@NotNull String name, @NotNull String text) throws IOException {
    VirtualFile file = createFile(name);
    WriteAction.runAndWait(() -> VfsUtil.saveText(file, text));
    return file;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    createTempDirectory();
  }

  @Override
  public void tearDown() throws Exception {
    if (myTempDir == null) {
      return;
    }

    try {
      if (deleteOnTearDown()) {
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(myTempDir.toString()));
        if (virtualFile != null) {
          WriteAction.runAndWait(() -> {
            virtualFile.delete(this);
          });
        }
      }
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      super.tearDown();
    }
  }

  protected boolean deleteOnTearDown() {
    return true;
  }

  protected @Nullable Path getTempHome() {
    return null;
  }

  private @NotNull Path createTempDirectory() {
    if (myTempDir == null) {
      myTempDir = doCreateTempDirectory();
    }
    return myTempDir;
  }

  protected @NotNull Path doCreateTempDirectory() {
    return HeavyTestHelper.createTempDirectoryForTempDirTestFixture(getTempHome(), "unitTest");
  }
}