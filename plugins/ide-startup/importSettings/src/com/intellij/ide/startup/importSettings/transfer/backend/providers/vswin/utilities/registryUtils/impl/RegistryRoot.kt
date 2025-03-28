// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.startup.importSettings.providers.vswin.utilities.registryUtils.impl

import com.intellij.ide.startup.importSettings.providers.vswin.utilities.registryUtils.IRegistryKey
import com.intellij.ide.startup.importSettings.providers.vswin.utilities.registryUtils.IRegistryRoot
import com.intellij.openapi.diagnostic.logger
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.throwIfNotAlive
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

typealias WinRegAction<T> = (WinReg.HKEY) -> T

private val logger = logger<RegistryKey>()
class RegistryKey internal constructor(val key: String, private val registryRoot: RegistryRoot): IRegistryKey {
    override fun withSuffix(suffix: String): IRegistryKey {
        return RegistryKey("$key$suffix", registryRoot)
    }

    override fun inChild(child: String): IRegistryKey {
        return RegistryKey("$key\\$child", registryRoot)
    }

    override fun getStringValue(value: String): String? = tryExecuteWithHKEY(key, value) {
        if (!Advapi32Util.registryValueExists(it, key, value)) {
            return@tryExecuteWithHKEY null
        }
        return@tryExecuteWithHKEY Advapi32Util.registryGetStringValue(it, key, value)
    }
    override fun getKeys(): List<String>? = tryExecuteWithHKEY(key) { Advapi32Util.registryGetKeys(it, key) }?.toList()
    override fun getValues(): Map<String, Any>? = tryExecuteWithHKEY(key) {
      if (!Advapi32Util.registryKeyExists(it, key)) return@tryExecuteWithHKEY null
      Advapi32Util.registryGetValues(it, key)
    }

    private fun <T> tryExecuteWithHKEY(vararg args: String, action: WinRegAction<T>): T? {
        return registryRoot.executeWithHKEY {
            try {
                return@executeWithHKEY action(it)
            }
            catch (t: com.sun.jna.platform.win32.Win32Exception) {
                logger.info("registry arguments: ${args.joinToString()}")
                logger.warn("Failed to work with registry", t)
                return@executeWithHKEY null
            }
        }
    }
}

open class RegistryRoot(private val hKey: WinReg.HKEY, private val lifetime: Lifetime) : IRegistryRoot {
    init {
      lifetime.onTermination {
          closeRoot()
      }
    }

    fun <T> executeWithHKEY(action: WinRegAction<T>): T {
        lifetime.throwIfNotAlive()
        return action(hKey)
    }

    override fun fromKey(key: String): IRegistryKey {
        return RegistryKey(key, this)
    }

    protected open fun closeRoot() {
        Advapi32.INSTANCE.RegCloseKey(hKey)
    }
}