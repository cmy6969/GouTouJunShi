package com.goutou.junshi.stealth;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Hooks;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Rf;

/**
 * Generic environment masking (optional, off by default).
 *
 * <p>Some third party detectors do not go through the packer at all: they simply look for
 * {@code su} binaries, list installed root managers, or inspect the stack for framework classes.
 * This layer answers those probes the way a clean device would.</p>
 *
 * <p>It is deliberately <em>not</em> enabled by default: masking core {@code java.io.File} and
 * {@code PackageManager} calls affects every part of the host app, and the targeted
 * {@link Detect} layer already covers everything the host actually asks.</p>
 */
public final class Env {

    private static final String[] ROOT_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin", "/su/xbin", "/data/local/su",
            "/data/local/bin/su", "/data/local/xbin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/magisk", "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules", "/data/adb/ksu",
            "/system/app/Superuser.apk", "/system/etc/init.d/99SuperSUDaemon", "/data/adb/lspd",
            "/data/misc/riru", "/data/adb/riru", "busybox"
    };

    private static final String[] ROOT_PACKAGES = {
            "de.robv.android.xposed.installer", "org.lsposed.manager", "io.github.lsposed.manager",
            "com.topjohnwu.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu",
            "com.rifsxd.ksunext", "eu.chainfire.supersu", "com.noshufou.android.su",
            "com.koushikdutta.superuser", "com.thirdparty.superuser", "com.yellowes.su",
            "com.kingroot.kinguser", "org.meowcat.edxposed.manager", "io.va.exposed",
            "moe.shizuku.privileged.api", "com.saurik.substrate"
    };

    private static final String[] FRAMEWORK_FRAMES = {
            "de.robv.android.xposed", "org.lsposed", "io.github.libxposed",
            "com.elderdrivers.riru", "org.meowcat.edxposed", "top.canyie.dreamland"
    };

    private static final Map<String, Boolean> PATH_CACHE = new HashMap<>();

    private Env() {
    }

    public static void install(ClassLoader cl) {
        hideFiles();
        hideExec();
        hideStacks();
        hidePackages();
        hideDebugger();
        Logx.i("env: masking layer installed");
    }

    private static void hideFiles() {
        XC_MethodHook bool = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                File f = (File) param.thisObject;
                if (f != null && Boolean.TRUE.equals(param.getResult()) && suspicious(f)) {
                    param.setResult(Boolean.FALSE);
                }
            }
        };
        for (String name : new String[]{"exists", "isFile", "isDirectory", "canRead", "canExecute"}) {
            Hooks.all(File.class, name, bool);
        }
        Hooks.all(File.class, "length", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                File f = (File) param.thisObject;
                if (f != null && suspicious(f)) {
                    param.setResult(0L);
                }
            }
        });
        XC_MethodHook listing = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                File f = (File) param.thisObject;
                if (f != null && suspicious(f) && param.getResult() != null) {
                    param.setResult(null);
                }
            }
        };
        Hooks.all(File.class, "list", listing);
        Hooks.all(File.class, "listFiles", listing);
    }

    private static boolean suspicious(File f) {
        String path;
        try {
            path = f.getAbsolutePath().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return false;
        }
        Boolean cached = PATH_CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        boolean hit = false;
        for (String needle : ROOT_PATHS) {
            if (path.equals(needle) || path.startsWith(needle + "/")) {
                hit = true;
                break;
            }
        }
        synchronized (PATH_CACHE) {
            if (PATH_CACHE.size() > 512) {
                PATH_CACHE.clear();
            }
            PATH_CACHE.put(path, hit);
        }
        return hit;
    }

    private static void hideExec() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object cmd = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (isSu(cmd)) {
                    param.setThrowable(new IOException("Cannot run program: error=13, Permission denied"));
                }
            }
        };
        Hooks.all(Runtime.class, "exec", hook);
        Hooks.all(Rf.cls(null, "java.lang.ProcessBuilder"), "start", hook);
    }

    private static boolean isSu(Object cmd) {
        String exe = null;
        if (cmd instanceof String[]) {
            String[] arr = (String[]) cmd;
            if (arr.length > 0) {
                exe = arr[0];
            }
        } else if (cmd instanceof String) {
            String s = ((String) cmd).trim();
            int space = s.indexOf(' ');
            exe = space < 0 ? s : s.substring(0, space);
        }
        if (exe == null) {
            return false;
        }
        String lower = exe.toLowerCase(Locale.ROOT);
        return lower.equals("su") || lower.endsWith("/su");
    }

    private static void hideStacks() {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (!(result instanceof StackTraceElement[])) {
                    return;
                }
                StackTraceElement[] frames = (StackTraceElement[]) result;
                List<StackTraceElement> keep = new ArrayList<>(frames.length);
                for (StackTraceElement frame : frames) {
                    String cn = frame == null ? null : frame.getClassName();
                    if (cn == null || cn.startsWith("com.goutou.junshi")) {
                        continue;
                    }
                    boolean drop = false;
                    for (String mark : FRAMEWORK_FRAMES) {
                        if (cn.startsWith(mark)) {
                            drop = true;
                            break;
                        }
                    }
                    if (!drop) {
                        keep.add(frame);
                    }
                }
                if (keep.size() != frames.length) {
                    param.setResult(keep.toArray(new StackTraceElement[0]));
                }
            }
        };
        Hooks.all(Throwable.class, "getStackTrace", filter);
        Hooks.all(Thread.class, "getStackTrace", filter);
    }

    private static void hidePackages() {
        Class<?> apm = Rf.cls(null, "android.app.ApplicationPackageManager");
        if (apm == null) {
            return;
        }
        XC_MethodHook list = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (!(result instanceof List)) {
                    return;
                }
                List<?> src = (List<?>) result;
                List<Object> keep = new ArrayList<>(src.size());
                boolean removed = false;
                for (Object item : src) {
                    String pkg = pkgOf(item);
                    if (pkg != null && hiddenPackage(pkg)) {
                        removed = true;
                        continue;
                    }
                    keep.add(item);
                }
                if (removed) {
                    param.setResult(keep);
                }
            }
        };
        Hooks.all(apm, "getInstalledPackages", list);
        Hooks.all(apm, "getInstalledApplications", list);
        XC_MethodHook single = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object a0 = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (a0 instanceof String && hiddenPackage((String) a0)) {
                    param.setThrowable(new PackageManager.NameNotFoundException((String) a0));
                }
            }
        };
        for (String name : new String[]{"getPackageInfo", "getApplicationInfo", "getInstallerPackageName"}) {
            Hooks.all(apm, name, single);
        }
    }

    private static String pkgOf(Object item) {
        if (item instanceof PackageInfo) {
            return ((PackageInfo) item).packageName;
        }
        if (item instanceof ApplicationInfo) {
            return ((ApplicationInfo) item).packageName;
        }
        return null;
    }

    private static boolean hiddenPackage(String pkg) {
        if (pkg.startsWith("com.goutou.junshi")) {
            return true;
        }
        for (String p : ROOT_PACKAGES) {
            if (pkg.equals(p) || pkg.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    private static void hideDebugger() {
        XC_MethodHook no = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.FALSE);
            }
        };
        Hooks.all(android.os.Debug.class, "isDebuggerConnected", no);
        Hooks.all(android.os.Debug.class, "waitingForDebugger", no);
    }
}
