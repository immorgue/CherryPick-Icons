package com.cosmod.launcherglass.bridge

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import com.cosmod.launcherglass.xposed.HookEntry

class ModernEntry : XposedModule() {
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookRuntime.attach(this)
    }
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        HookEntry().handleLoadPackage(HookEntry.LoadPackageParam(param.packageName,param.classLoader))
    }
    // Stateful drawable/view generations currently require a clean process.
    // Decline reload until every retained callback/resource has safe teardown.
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam) = false
}
