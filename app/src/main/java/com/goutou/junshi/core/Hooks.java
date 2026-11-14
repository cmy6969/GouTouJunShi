package com.goutou.junshi.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Thin, fault tolerant wrapper around the hook API.
 *
 * <p>Game rule of this module: one failing hook must never take down the host application, and a
 * missing class is a normal, expected event (the host app is closed source and changes between
 * releases).  Every entry point therefore logs and swallows.</p>
 */
public final class Hooks {

    private Hooks() {
    }

    public static int count;

    /** Class loader of the host process; set once during boot. */
    public static volatile ClassLoader host;

    public static Class<?> hostClass(String name) {
        ClassLoader cl = host;
        return cl == null ? Rf.cls(null, name) : Rf.cls(cl, name);
    }

    public static void all(Class<?> owner, String name, XC_MethodHook cb) {
        if (owner == null) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(owner, name, cb);
            count++;
        } catch (Throwable t) {
            Logx.e("hookAll " + owner.getSimpleName() + "#" + name, t);
        }
    }

    public static void ctor(Class<?> owner, XC_MethodHook cb) {
        if (owner == null) {
            return;
        }
        try {
            XposedBridge.hookAllConstructors(owner, cb);
            count++;
        } catch (Throwable t) {
            Logx.e("hookCtor " + owner.getSimpleName(), t);
        }
    }

    public static void method(Class<?> owner, String name, Class<?>[] params, XC_MethodHook cb) {
        if (owner == null) {
            return;
        }
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            XposedBridge.hookAllMethods(owner, name, cb);
            count++;
        } catch (Throwable t) {
            Logx.e("hook " + owner.getSimpleName() + "#" + name, t);
        }
    }

    public static void exact(Member member, XC_MethodHook cb) {
        if (member == null) {
            return;
        }
        try {
            if (member instanceof Method) {
                XposedBridge.hookAllMethods(((Method) member).getDeclaringClass(), member.getName(), cb);
            } else if (member instanceof Constructor) {
                XposedBridge.hookAllConstructors(((Constructor<?>) member).getDeclaringClass(), cb);
            }
            count++;
        } catch (Throwable t) {
            Logx.e("hookMember " + Rf.describe(member), t);
        }
    }

    public static Class<?> load(ClassLoader cl, String name) {
        Class<?> c = Rf.cls(cl, name);
        if (c == null) {
            Logx.i("absent: " + name);
        }
        return c;
    }
}
