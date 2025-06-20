// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/**
 * This file is generated by [com.intellij.platform.eel.codegen.BuildersGeneratorTest].
 */
package com.intellij.platform.eel.fs

import com.intellij.platform.eel.GeneratedBuilder
import com.intellij.platform.eel.fs.EelFileSystemApi.StatArgs
import com.intellij.platform.eel.fs.EelFileSystemApi.SymlinkPolicy
import com.intellij.platform.eel.path.EelPath
import org.jetbrains.annotations.ApiStatus


@GeneratedBuilder.Result
@ApiStatus.Internal
class StatArgsBuilder(
  private var path: EelPath,
) {
  private var symlinkPolicy: SymlinkPolicy = SymlinkPolicy.DO_NOT_RESOLVE

  fun path(arg: EelPath): StatArgsBuilder = apply {
    this.path = arg
  }

  fun symlinkPolicy(arg: SymlinkPolicy): StatArgsBuilder = apply {
    this.symlinkPolicy = arg
  }

  fun doNotResolve(): StatArgsBuilder =
    symlinkPolicy(SymlinkPolicy.DO_NOT_RESOLVE)

  fun justResolve(): StatArgsBuilder =
    symlinkPolicy(SymlinkPolicy.JUST_RESOLVE)

  fun resolveAndFollow(): StatArgsBuilder =
    symlinkPolicy(SymlinkPolicy.RESOLVE_AND_FOLLOW)

  fun build(): StatArgs =
    StatArgsImpl(
      path = path,
      symlinkPolicy = symlinkPolicy,
    )
}

@GeneratedBuilder.Result
internal class StatArgsImpl(
  override val path: EelPath,
  override val symlinkPolicy: SymlinkPolicy,
) : StatArgs