package com.goutou.junshi;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import com.goutou.junshi.core.Boot;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Self;

/**
 * Module entry point, declared in {@code assets/xposed_init}.
 *
 * <ul>
 *   <li>{@link IXposedHookZygoteInit} remembers where the module APK lives so the in-page JavaScript
 *       payloads can be read from it later (the host process cannot read our assets directly).</li>
 *   <li>{@link IXposedHookLoadPackage} boots the module inside the host process only.  Every other
 *       application is left untouched.</li>
 * </ul>
 */
public final class Entry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    @Override
    public void initZygote(StartupParam startupParam) {
        try {
            if (startupParam != null) {
                Self.setModulePath(startupParam.modulePath);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !Boot.TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        try {
            Boot.start(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(t);
            Logx.e("entry failed", t);
        }
    }
}
