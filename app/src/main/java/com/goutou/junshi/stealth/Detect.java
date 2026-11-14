package com.goutou.junshi.stealth;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Hooks;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Rf;

/**
 * Detection guard.
 *
 * <p>The host app is protected by a commercial packer and ships several independent environment
 * detectors (梆梆 everisk, 百度 mshield, coralline sea01, plus its own study.util helpers).  Instead
 * of trying to make the device look clean globally — which breaks unrelated code and is easy to
 * notice — this class pinpoints the detector methods and answers them with "nothing found", and it
 * neutralises the one behaviour that actually hurts the user: the packer calling
 * {@code System.exit()} once it believes the environment is hostile.</p>
 *
 * <p>Every hook is installed only if the owning class exists, so a new host release that renames a
 * detector simply skips that single entry.</p>
 */
public final class Detect {

    /** Detector classes and the value they are expected to answer with. */
    private static final String[] DETECTOR_CLASSES = {
            // 梆梆 everisk
            "com.bangcle.everisk.core.Type",
            "com.bangcle.everisk.core.Everisk",
            "com.coralline.sea01.y",
            "com.coralline.sea01.x",
            "com.coralline.sea01.z",
            "com.coralline.sea01.v",
            "com.coralline.sea01.u",
            "com.coralline.sea01.w",
            // 百度 mshield (xposed / root 检测)
            "com.baidu.mshield.x6.c.a",
            "com.baidu.mshield.x6.d.a",
            "com.baidu.mshield.x6.b.a",
            // 学习通自身的检测工具
            "com.chaoxing.mobile.study.util.a",
            "com.chaoxing.mobile.study.util.b",
            "com.chaoxing.mobile.study.util.c",
            "com.chaoxing.mobile.study.util.d",
            "com.chaoxing.mobile.p",
            "com.chaoxing.mobile.q",
            "com.chaoxing.mobile.o",
            // 运行环境综合检测
            "en5", "oj5", "fh.a", "gh.a", "eh.a", "fi.a"
    };

    /** Stack frames that mean "a detector is about to kill the process". */
    private static final String[] KILLER_FRAMES = {
            "jsprotocal", "everisk", "bangcle", "sea01", "mshield", "secneo", "coralline"
    };

    private Detect() {
    }

    public static void install(ClassLoader cl, Cfg.Snapshot cfg) {
        guardProcessExit();
        int hooked = 0;
        for (String name : DETECTOR_CLASSES) {
            Class<?> c = Rf.cls(cl, name);
            if (c == null) {
                continue;
            }
            hooked += patchDetector(c);
        }
        Logx.i("detect: guard active, detector classes patched=" + hooked);
    }

    /**
     * Neutralises every non-void static/instance detector entry of one class.
     *
     * <p>The replacement value depends on the declared return type, which is how a detector reports
     * "clean": {@code false} for boolean checks, {@code 0} for integer bit masks, an empty JSON object
     * for risk dispatchers that expect a payload.</p>
     */
    private static int patchDetector(Class<?> c) {
        int count = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Method m : Rf.allMethods(c)) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())
                    || m.getDeclaringClass() == Object.class) {
                continue;
            }
            // Detector entry points take at most a couple of arguments (a flag, a context, a path);
            // anything larger is business code of the same class and is left alone.
            if (m.getParameterTypes().length > 2) {
                continue;
            }
            if (!seen.add(m.getName())) {
                continue;
            }
            final Object clean = cleanValueFor(m.getReturnType());
            try {
                Hooks.all(c, m.getName(), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Cfg.get().detectGuard) {
                            return;
                        }
                        param.setResult(clean);
                    }
                });
                count++;
            } catch (Throwable t) {
                Logx.e("detect hook " + c.getName() + "#" + m.getName(), t);
            }
        }
        return count;
    }

    /** The value a "nothing suspicious here" answer takes for the given return type. */
    private static Object cleanValueFor(Class<?> type) {
        if (type == null || void.class.equals(type)) {
            return null;
        }
        if (boolean.class.equals(type) || Boolean.class.equals(type)) {
            return Boolean.FALSE;
        }
        if (int.class.equals(type) || Integer.class.equals(type)) {
            return 0;
        }
        if (long.class.equals(type) || Long.class.equals(type)) {
            return 0L;
        }
        if (short.class.equals(type)) {
            return (short) 0;
        }
        if (byte.class.equals(type)) {
            return (byte) 0;
        }
        if (String.class.equals(type) || CharSequence.class.equals(type)) {
            return "{}";
        }
        if (type.isArray()) {
            return null;
        }
        // Object / JSONObject / Map: an empty JSON object satisfies the risk dispatcher contract.
        return type.isAssignableFrom(org.json.JSONObject.class) ? new org.json.JSONObject() : null;
    }

    /**
     * Stops the packer from terminating the process when it dislikes the environment.
     *
     * <p>{@code System.exit(n)} forwards to {@code Runtime.exit(n)}, so one hook covers both.  Only
     * calls that originate from a detector frame are swallowed — an ordinary exit from the app's own
     * code (user pressed "退出登录", a crash handler) must keep working.</p>
     */
    private static void guardProcessExit() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Cfg.get().detectGuard) {
                    return;
                }
                String culprit = detectorFrame();
                if (culprit != null) {
                    param.setResult(null);
                    Logx.w("detect: blocked process exit from " + culprit);
                }
            }
        };
        Hooks.all(Runtime.class, "exit", hook);
        Hooks.all(Runtime.class, "halt", hook);
    }

    private static String detectorFrame() {
        try {
            StackTraceElement[] frames = Thread.currentThread().getStackTrace();
            for (int i = 3; i < frames.length && i < 40; i++) {
                StackTraceElement f = frames[i];
                String cls = f.getClassName();
                if (cls == null) {
                    continue;
                }
                String lower = cls.toLowerCase(java.util.Locale.ROOT);
                for (String mark : KILLER_FRAMES) {
                    if (lower.contains(mark)) {
                        return cls + "#" + f.getMethodName();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
