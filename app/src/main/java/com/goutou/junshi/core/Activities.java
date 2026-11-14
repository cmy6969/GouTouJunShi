package com.goutou.junshi.core;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Keeps a weak reference to the foreground activity and to the application context.
 *
 * <p>Every feature that needs a window (overlay, toast, view scan) reads it from here instead of
 * walking the activity stack through hidden APIs.</p>
 */
public final class Activities {

    private static volatile WeakReference<Activity> current = new WeakReference<>(null);
    private static volatile Application app;
    private static volatile boolean installed;

    private Activities() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object self = param.thisObject;
                if (self instanceof Activity) {
                    current = new WeakReference<>((Activity) self);
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", hook);
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", hook);
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = current.get();
                    if (a == param.thisObject) {
                        current = new WeakReference<>(null);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static Activity current() {
        return current.get();
    }

    public static void setApplication(Application application) {
        app = application;
    }

    public static Application app() {
        Application a = app;
        if (a != null) {
            return a;
        }
        Activity act = current.get();
        if (act != null) {
            Application fromAct = act.getApplication();
            if (fromAct != null) {
                app = fromAct;
                return fromAct;
            }
        }
        try {
            Object o = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (o instanceof Application) {
                app = (Application) o;
                return (Application) o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String processName() {
        try {
            return Application.getProcessName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isMainProcess() {
        Application a = app();
        String proc = processName();
        if (a == null || proc == null || proc.isEmpty()) {
            return true;
        }
        return proc.equals(a.getPackageName());
    }

    public static Context anyContext() {
        Activity a = current.get();
        if (a != null) {
            return a;
        }
        return app();
    }
}
