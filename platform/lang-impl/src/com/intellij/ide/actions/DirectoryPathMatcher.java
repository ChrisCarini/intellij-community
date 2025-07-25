// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.actions;

import com.intellij.ide.util.gotoByName.GotoFileModel;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.BaseProjectDirectories;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.psi.codeStyle.MinusculeMatcher;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.IdFilter;
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndex;
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSetWithCustomData;
import com.intellij.workspaceModel.core.fileIndex.impl.WorkspaceFileIndexEx;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class DirectoryPathMatcher {
  private final @NotNull GotoFileModel myModel;
  private final @Nullable List<Pair<VirtualFile, String>> myFiles;
  private final @NotNull Predicate<VirtualFile> myProjectFileFilter;

  final @NotNull String dirPattern;

  private DirectoryPathMatcher(@NotNull GotoFileModel model, @Nullable List<Pair<VirtualFile, String>> files, @NotNull String pattern) {
    myModel = model;
    myFiles = files;
    dirPattern = pattern;

    FileBasedIndex fileBasedIndex = FileBasedIndex.getInstance();
    Project project = model.getProject();
    IdFilter projectIndexableFilesFilter = fileBasedIndex.projectIndexableFiles(project);
    var allScope = GlobalSearchScope.allScope(project);
    if (projectIndexableFilesFilter == null) {
      myProjectFileFilter = vFile -> allScope.contains(vFile);
    }
    else {
      myProjectFileFilter = vFile -> {
        return vFile instanceof VirtualFileWithId
               ? projectIndexableFilesFilter.containsFileId(((VirtualFileWithId)vFile).getId()) || allScope.contains(vFile)
               : allScope.contains(vFile);
      };
    }
  }

  static @Nullable DirectoryPathMatcher root(@NotNull GotoFileModel model, @NotNull String pattern) {
    DirectoryPathMatcher matcher = new DirectoryPathMatcher(model, null, "");
    for (int i = 0; i < pattern.length(); i++) {
      matcher = matcher.appendChar(pattern.charAt(i));
      if (matcher == null) return null;
    }
    return matcher;
  }

  @Nullable
  DirectoryPathMatcher appendChar(char c) {
    String nextPattern = dirPattern + c;
    if (c == '*' || c == '/' || c == ' ') return new DirectoryPathMatcher(myModel, myFiles, nextPattern);

    List<Pair<VirtualFile, String>> files = getMatchingRoots();

    List<Pair<VirtualFile, String>> nextRoots = new ArrayList<>();
    MinusculeMatcher matcher = GotoFileItemProvider.getQualifiedNameMatcher(nextPattern);
    for (Pair<VirtualFile, String> pair : files) {
      if (containsChar(pair.second, c) && matcher.matches(pair.second)) {
        nextRoots.add(pair);
      }
      else {
        processProjectFilesUnder(pair.first, sub -> {
          if (!sub.isDirectory()) return false;
          if (!containsChar(sub.getNameSequence(), c)) return true; //go deeper

          String fullName = pair.second + '/' + VfsUtilCore.getRelativePath(sub, pair.first, '/');
          if (matcher.matches(fullName)) {
            nextRoots.add(Pair.create(sub, fullName));
            return false;
          }
          return true;
        });
      }
    }

    return nextRoots.isEmpty() ? null : new DirectoryPathMatcher(myModel, nextRoots, nextPattern);
  }

  /** return null if not cheap */
  @Nullable
  Set<String> findFileNamesMatchingIfCheap(char nextLetter, MinusculeMatcher matcher) {
    List<Pair<VirtualFile, String>> files = getMatchingRoots();
    Set<String> names = new HashSet<>();
    AtomicInteger counter = new AtomicInteger();
    BooleanSupplier tooMany = () -> counter.get() > 1000;
    for (Pair<VirtualFile, String> pair : files) {
      // Commented this out because the pattern Utils/TT matched the original "Utils" directory name and nothing more here.
      // ("Utils" contain "t", "Utils" is matched by a ["T" "T"] matcher)
      //if (containsChar(pair.second, nextLetter) && matcher.matches(pair.second)) {
      //  names.add(pair.first.getName());
      //} else {
      processProjectFilesUnder(pair.first, sub -> {
        counter.incrementAndGet();
        if (tooMany.getAsBoolean()) return false;

        String name = sub.getName();
        if (containsChar(name, nextLetter) && matcher.matches(name)) {
          names.add(name);
        }
        return true;
      });
      //}
    }
    return tooMany.getAsBoolean() ? null : names;
  }

  private @NotNull List<Pair<VirtualFile, String>> getMatchingRoots() {
    return myFiles != null ? myFiles : getProjectRoots(myModel);
  }

  @NotNull
  GlobalSearchScope narrowDown(@NotNull GlobalSearchScope fileSearchScope) {
    if (myFiles == null) return fileSearchScope;

    VirtualFile[] array = ContainerUtil.map2Array(myFiles, VirtualFile.class, p -> p.first);
    return GlobalSearchScopesCore.directoriesScope(myModel.getProject(), true, array).intersectWith(fileSearchScope);
  }

  private void processProjectFilesUnder(VirtualFile root, Processor<? super VirtualFile> consumer) {
    VfsUtilCore.visitChildrenRecursively(root, new VirtualFileVisitor<Void>() {

      @Override
      public boolean visitFile(@NotNull VirtualFile file) {
        return myProjectFileFilter.test(file) && consumer.process(file);
      }

      @Override
      public @Nullable Iterable<VirtualFile> getChildrenIterable(@NotNull VirtualFile file) {
        return file instanceof NewVirtualFile ? ((NewVirtualFile)file).getCachedChildren() : null;
      }
    });
  }

  private static boolean containsChar(CharSequence name, char c) {
    return StringUtil.indexOf(name, c, 0, name.length(), false) >= 0;
  }

  @ApiStatus.Internal
  static @NotNull List<Pair<VirtualFile, String>> getProjectRoots(GotoFileModel model) {
    var workspaceFileIndex = (WorkspaceFileIndexEx)WorkspaceFileIndex.getInstance(model.getProject());
    Set<VirtualFile> roots = new HashSet<>(BaseProjectDirectories.getBaseDirectories(model.getProject()));

    if (Registry.is("search.in.non.indexable")) {
      var nonIndexableRoots = ReadAction.nonBlocking(() -> {
        var contentNonIndexable = VirtualFilePrefixTree.INSTANCE.createSet();
        workspaceFileIndex.visitFileSets((fileSet, entity) -> {
          boolean isRecursive = ((WorkspaceFileSetWithCustomData<?>)fileSet).getRecursive();
          if (isRecursive && fileSet.getKind().isContent() && !fileSet.getKind().isIndexable()) {
            contentNonIndexable.add(fileSet.getRoot());
          }
        });
        return contentNonIndexable.getRoots();
      }).executeSynchronously();
      roots.addAll(nonIndexableRoots);
    }

    for (Module module : ModuleManager.getInstance(model.getProject()).getModules()) {
      for (OrderEntry entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
        if (entry instanceof LibraryOrSdkOrderEntry) {
          Collections.addAll(roots, ((LibraryOrSdkOrderEntry)entry).getRootFiles(OrderRootType.CLASSES));
          Collections.addAll(roots, ((LibraryOrSdkOrderEntry)entry).getRootFiles(OrderRootType.SOURCES));
        }
      }
    }
    for (AdditionalLibraryRootsProvider provider : AdditionalLibraryRootsProvider.EP_NAME.getExtensionList()) {
      for (SyntheticLibrary descriptor : provider.getAdditionalProjectLibraries(model.getProject())) {
        roots.addAll(descriptor.getSourceRoots());
        roots.addAll(descriptor.getBinaryRoots());
      }
    }
    return roots.stream()
      .map(root -> {
        VirtualFile top = model.getTopLevelRoot(root);
        return top != null ? top : root;
      })
      .distinct()
      .map(r -> Pair.create(r, StringUtil.notNullize(model.getFullName(r))))
      .collect(Collectors.toList());
  }
}
