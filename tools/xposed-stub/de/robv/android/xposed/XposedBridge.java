package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.HashSet;
import java.util.Set;

public final class XposedBridge {

    public static final int LOGLEVEL_NONE = 0;
    public static final int LOGLEVEL_ERROR = 1;
    public static final int LOGLEVEL_INFO = 2;
    public static final int LOGLEVEL_DEBUG = 4;

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static void log(int priority, String tag, String msg) {
    }

    public static void logError(String msg, Throwable t) {
    }

    public static void logError(Throwable t) {
    }

    public static void setLogLevel(int level) {
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return new HashSet<XC_MethodHook.Unhook>();
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        return new HashSet<XC_MethodHook.Unhook>();
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        return null;
    }
}
