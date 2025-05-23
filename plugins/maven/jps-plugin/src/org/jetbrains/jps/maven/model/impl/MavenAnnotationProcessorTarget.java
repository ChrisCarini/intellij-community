// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.maven.model.impl;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.builders.BuildRootDescriptor;
import org.jetbrains.jps.builders.BuildTarget;
import org.jetbrains.jps.builders.BuildTargetRegistry;
import org.jetbrains.jps.builders.TargetOutputIndex;
import org.jetbrains.jps.builders.java.JavaModuleBuildTargetType;
import org.jetbrains.jps.builders.storage.BuildDataPaths;
import org.jetbrains.jps.incremental.JVMModuleBuildTarget;
import org.jetbrains.jps.incremental.ModuleBuildTarget;
import org.jetbrains.jps.indices.IgnoredFileIndex;
import org.jetbrains.jps.indices.ModuleExcludeIndex;
import org.jetbrains.jps.maven.model.JpsMavenExtensionService;
import org.jetbrains.jps.maven.model.JpsMavenModuleExtension;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.module.JpsModule;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class is used to provide implicit dependencies between modules. It is used in cases when one module uses other project modules as
 * annotation processors, which is possible using "annotationProcessorPaths" option of "maven-compiler-plugin"
 */
@ApiStatus.Internal
public final class MavenAnnotationProcessorTarget extends JVMModuleBuildTarget<BuildRootDescriptor> {
  private final @NotNull MavenAnnotationProcessorTargetType myTargetType;

  public MavenAnnotationProcessorTarget(@NotNull MavenAnnotationProcessorTargetType targetType, JpsModule module) {
    super(targetType, module);
    myTargetType = targetType;
  }

  @Override
  public @NotNull String getPresentableName() {
    return myTargetType.getTypeId() + ":" + myModule.getName();
  }

  @Override
  public boolean isTests() {
    return myTargetType.isTests();
  }

  @Override
  public boolean isCompiledBeforeModuleLevelBuilders() {
    return true;
  }

  /**
   * Creates list of {@link ModuleBuildTarget} targets according to list of annotation processors, declared in
   * {@link JpsMavenModuleExtension}
   */
  @Override
  public @NotNull Collection<BuildTarget<?>> computeDependencies(@NotNull BuildTargetRegistry targetRegistry, @NotNull TargetOutputIndex outputIndex) {
    JpsMavenModuleExtension moduleExtension = JpsMavenExtensionService.getInstance().getExtension(myModule);
    if (moduleExtension == null) return Collections.emptyList();

    Set<String> names = new HashSet<>(moduleExtension.getAnnotationProcessorModules());
    JavaModuleBuildTargetType javaModuleBuildTargetType = JavaModuleBuildTargetType.getInstance(isTests());
    return myModule.getProject().getModules().stream()
                   .filter(module -> names.contains(module.getName()))
                   .map(module -> new ModuleBuildTarget(module, javaModuleBuildTargetType))
                   .collect(Collectors.toList());
  }

  @Override
  public @NotNull List<BuildRootDescriptor> computeRootDescriptors(@NotNull JpsModel model,
                                                                   @NotNull ModuleExcludeIndex index,
                                                                   @NotNull IgnoredFileIndex ignoredFileIndex,
                                                                   @NotNull BuildDataPaths dataPaths) {
    return List.of();
  }
}
