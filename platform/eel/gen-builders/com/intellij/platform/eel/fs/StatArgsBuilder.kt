// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/**
 * This file is generated by [com.intellij.platform.eel.codegen.BuildersGenerator].
 */
package com.intellij.platform.eel.fs

import com.intellij.platform.eel.*
import com.intellij.platform.eel.fs.EelFileSystemApi.StatArgs
import com.intellij.platform.eel.fs.EelFileSystemApi.StatError
import com.intellij.platform.eel.fs.EelFileSystemApi.SymlinkPolicy
import com.intellij.platform.eel.path.EelPath
import org.jetbrains.annotations.CheckReturnValue

/**
 * Similar to stat(2) and lstat(2). [symlinkPolicy] has an impact only on [EelFileInfo.type] if [path] points on a symlink.
 */
@GeneratedBuilder.Result
fun EelFileSystemApi.stat(
  path: EelPath,
): com_intellij_platform_eel_fs_EelFileSystemApi_stat_OwnedBuilder =
  com_intellij_platform_eel_fs_EelFileSystemApi_stat_OwnedBuilder(
    owner = this,
    path = path,
  )

@GeneratedBuilder.Result
class com_intellij_platform_eel_fs_EelFileSystemApi_stat_OwnedBuilder(
  private val owner: EelFileSystemApi,
  private var path: EelPath,
) : OwnedBuilder<EelResult<EelFileInfo, StatError>> {
  private var symlinkPolicy: SymlinkPolicy = SymlinkPolicy.DO_NOT_RESOLVE

  fun path(arg: EelPath): com_intellij_platform_eel_fs_EelFileSystemApi_stat_OwnedBuilder = apply {
    this.path = arg
  }

  fun symlinkPolicy(arg: SymlinkPolicy): com_intellij_platform_eel_fs_EelFileSystemApi_stat_OwnedBuilder = apply {
    this.symlinkPolicy = arg
  }

  fun doNotResolve(): com_intellij_platform_eel_fs_EelFileSystemApi_stat_OwnedBuilder =
    symlinkPolicy(SymlinkPolicy.DO_NOT_RESOLVE)

  fun justResolve(): com_intellij_platform_eel_fs_EelFileSystemApi_stat_OwnedBuilder =
    symlinkPolicy(SymlinkPolicy.JUST_RESOLVE)

  fun resolveAndFollow(): com_intellij_platform_eel_fs_EelFileSystemApi_stat_OwnedBuilder =
    symlinkPolicy(SymlinkPolicy.RESOLVE_AND_FOLLOW)

  /**
   * Complete the builder and call [com.intellij.platform.eel.fs.EelFileSystemApi.stat]
   * with an instance of [com.intellij.platform.eel.fs.EelFileSystemApi.StatArgs].
   */
  @org.jetbrains.annotations.CheckReturnValue
  override suspend fun eelIt(): EelResult<EelFileInfo, StatError> =
    owner.stat(
      StatArgsImpl(
        path = path,
        symlinkPolicy = symlinkPolicy,
      )
    )
}

@GeneratedBuilder.Result
fun EelFileSystemPosixApi.stat(
  path: EelPath,
): com_intellij_platform_eel_fs_EelFileSystemPosixApi_stat_OwnedBuilder =
  com_intellij_platform_eel_fs_EelFileSystemPosixApi_stat_OwnedBuilder(
    owner = this,
    path = path,
  )

@GeneratedBuilder.Result
class com_intellij_platform_eel_fs_EelFileSystemPosixApi_stat_OwnedBuilder(
  private val owner: EelFileSystemPosixApi,
  private var path: EelPath,
) : OwnedBuilder<EelResult<EelPosixFileInfo, StatError>> {
  private var symlinkPolicy: SymlinkPolicy = SymlinkPolicy.DO_NOT_RESOLVE

  fun path(arg: EelPath): com_intellij_platform_eel_fs_EelFileSystemPosixApi_stat_OwnedBuilder = apply {
    this.path = arg
  }

  fun symlinkPolicy(arg: SymlinkPolicy): com_intellij_platform_eel_fs_EelFileSystemPosixApi_stat_OwnedBuilder = apply {
    this.symlinkPolicy = arg
  }

  fun doNotResolve(): com_intellij_platform_eel_fs_EelFileSystemPosixApi_stat_OwnedBuilder =
    symlinkPolicy(SymlinkPolicy.DO_NOT_RESOLVE)

  fun justResolve(): com_intellij_platform_eel_fs_EelFileSystemPosixApi_stat_OwnedBuilder =
    symlinkPolicy(SymlinkPolicy.JUST_RESOLVE)

  fun resolveAndFollow(): com_intellij_platform_eel_fs_EelFileSystemPosixApi_stat_OwnedBuilder =
    symlinkPolicy(SymlinkPolicy.RESOLVE_AND_FOLLOW)

  /**
   * Complete the builder and call [com.intellij.platform.eel.fs.EelFileSystemPosixApi.stat]
   * with an instance of [com.intellij.platform.eel.fs.EelFileSystemApi.StatArgs].
   */
  @org.jetbrains.annotations.CheckReturnValue
  override suspend fun eelIt(): EelResult<EelPosixFileInfo, StatError> =
    owner.stat(
      StatArgsImpl(
        path = path,
        symlinkPolicy = symlinkPolicy,
      )
    )
}

@GeneratedBuilder.Result
fun EelFileSystemWindowsApi.stat(
  path: EelPath,
): com_intellij_platform_eel_fs_EelFileSystemWindowsApi_stat_OwnedBuilder =
  com_intellij_platform_eel_fs_EelFileSystemWindowsApi_stat_OwnedBuilder(
    owner = this,
    path = path,
  )

@GeneratedBuilder.Result
class com_intellij_platform_eel_fs_EelFileSystemWindowsApi_stat_OwnedBuilder(
  private val owner: EelFileSystemWindowsApi,
  private var path: EelPath,
) : OwnedBuilder<EelResult<EelWindowsFileInfo, StatError>> {
  private var symlinkPolicy: SymlinkPolicy = SymlinkPolicy.DO_NOT_RESOLVE

  fun path(arg: EelPath): com_intellij_platform_eel_fs_EelFileSystemWindowsApi_stat_OwnedBuilder = apply {
    this.path = arg
  }

  fun symlinkPolicy(arg: SymlinkPolicy): com_intellij_platform_eel_fs_EelFileSystemWindowsApi_stat_OwnedBuilder = apply {
    this.symlinkPolicy = arg
  }

  fun doNotResolve(): com_intellij_platform_eel_fs_EelFileSystemWindowsApi_stat_OwnedBuilder =
    symlinkPolicy(SymlinkPolicy.DO_NOT_RESOLVE)

  fun justResolve(): com_intellij_platform_eel_fs_EelFileSystemWindowsApi_stat_OwnedBuilder =
    symlinkPolicy(SymlinkPolicy.JUST_RESOLVE)

  fun resolveAndFollow(): com_intellij_platform_eel_fs_EelFileSystemWindowsApi_stat_OwnedBuilder =
    symlinkPolicy(SymlinkPolicy.RESOLVE_AND_FOLLOW)

  /**
   * Complete the builder and call [com.intellij.platform.eel.fs.EelFileSystemWindowsApi.stat]
   * with an instance of [com.intellij.platform.eel.fs.EelFileSystemApi.StatArgs].
   */
  @org.jetbrains.annotations.CheckReturnValue
  override suspend fun eelIt(): EelResult<EelWindowsFileInfo, StatError> =
    owner.stat(
      StatArgsImpl(
        path = path,
        symlinkPolicy = symlinkPolicy,
      )
    )
}

@GeneratedBuilder.Result
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
private class StatArgsImpl(
  override val path: EelPath,
  override val symlinkPolicy: SymlinkPolicy,
) : StatArgs
      