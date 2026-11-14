package com.goutou.junshi.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Reflection helpers. Everything returns {@code null} instead of throwing. */
public final class Rf {

    private Rf() {
    }

    public static Class<?> cls(ClassLoader cl, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Class<?> first(ClassLoader cl, String... names) {
        for (String n : names) {
            Class<?> c = cls(cl, n);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    public static Method method(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        Class<?> c = owner;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** First declared method with the given name and arity, searched up the hierarchy. */
    public static Method arity(Class<?> owner, String name, int paramCount) {
        if (owner == null) {
            return null;
        }
        Class<?> c = owner;
        while (c != null) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static Constructor<?> ctor(Class<?> owner, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            Constructor<?> k = owner.getDeclaredConstructor(params);
            k.setAccessible(true);
            return k;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object call(Object target, Method m, Object... args) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object callStatic(Method m, Object... args) {
        return call(null, m, args);
    }

    public static Field field(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        Class<?> c = owner;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    public static Object get(Object target, Field f) {
        if (f == null || target == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object getStatic(Field f) {
        if (f == null) {
            return null;
        }
        try {
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean set(Object target, Field f, Object value) {
        if (f == null || target == null) {
            return false;
        }
        try {
            f.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Declared methods of the class and all super classes (duplicates removed by signature). */
    public static List<Method> allMethods(Class<?> owner) {
        List<Method> out = new ArrayList<>();
        if (owner == null) {
            return out;
        }
        List<String> seen = new ArrayList<>();
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    String key = m.getName() + java.util.Arrays.toString(m.getParameterTypes());
                    if (seen.contains(key)) {
                        continue;
                    }
                    seen.add(key);
                    out.add(m);
                }
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return out;
    }

    public static String describe(java.lang.reflect.Member m) {
        if (m == null) {
            return "null";
        }
        return m.getDeclaringClass().getSimpleName() + "#" + m.getName();
    }
}
