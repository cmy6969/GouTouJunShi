package com.goutou.junshi.ads;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Hooks;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Rf;

/**
 * Splash ad removal.
 *
 * <p>The host models its splash ad with a small value object plus a preloader that fills it.  Three
 * surgical hooks deal with it, ordered from the least to the most invasive:</p>
 *
 * <ol>
 *   <li><b>Click-through filter</b> — any activity whose name looks like an ad landing page is not
 *       started.  Cheap, reversible, and safe even on a version whose ad classes were renamed.</li>
 *   <li><b>Preloader</b> — a static {@code void (Context, ...)} entry that fetches the ad is skipped
 *       entirely: no request is made, nothing to render.</li>
 *   <li><b>Ad source</b> — a getter that returns the ad object still runs (so its side effects are
 *       kept) but the value is dropped, which puts the host on its own "no ad" branch.</li>
 * </ol>
 *
 * <p>What is deliberately <em>not</em> done: guessing "skip"/"cancel"/"close" methods by name.  That
 * kind of hook historically reached {@code System.exit()} and the privacy policy dialog, i.e. it
 * turned an ad into a crash.  The rule here is: fail open on ads, never fail closed on
 * splash → home.</p>
 */
public final class Ads {

    /** Splash activity candidates, in the order the host itself moved it around. */
    private static final String[] SPLASH_CANDIDATES = {
            "com.chaoxing.mobile.splash.SplashActivity",
            "com.chaoxing.mobile.SplashActivity",
            "com.chaoxing.mobile.activity.SplashActivity",
            "com.chaoxing.mobile.main.ui.SplashActivity",
            "com.chaoxing.mobile.app.SplashActivity",
            "com.chaoxing.mobile.login.SplashActivity"
    };

    /** Ad model / preloader anchors. */
    private static final String AD_CLASS = "com.chaoxing.mobile.activity.Ad";
    private static final String SPLASH_VIEW_MODEL = "com.chaoxing.mobile.activity.SplashViewModel";
    private static final String[] PRELOADER_CLASSES = {
            "com.chaoxing.mobile.activity.f",
            "com.chaoxing.mobile.activity.a"
    };

    /** Fragments of an ad landing page class name. */
    private static final String[] AD_ACTIVITY_MARKS = {
            "adactivity", "advert", "splashad", "nativead", "rewardad", "interstitial",
            "fullscreenad", "adfullscreen", "adwebview", "adclick", ".ads.", "adsdk"
    };

    private Ads() {
    }

    public static void install(ClassLoader cl, Cfg.Snapshot cfg) {
        Class<?> splash = resolveSplash(cl);
        Class<?> ad = resolveAd(cl, splash);
        int hooks = 0;
        if (ad != null && splash != null) {
            hooks += hookAdSource(cl, splash, ad);
        }
        hooks += hookPreloader(cl);
        if (cfg.adsBlockActivity) {
            hooks += hookStartActivity(splash);
        }
        Logx.i("ads: installed (splash=" + name(splash) + ", ad=" + name(ad) + ", hooks=" + hooks + ")");
    }

    private static String name(Class<?> c) {
        return c == null ? "?" : c.getSimpleName();
    }

    private static Class<?> resolveSplash(ClassLoader cl) {
        for (String candidate : SPLASH_CANDIDATES) {
            Class<?> c = Rf.cls(cl, candidate);
            if (c != null) {
                return c;
            }
        }
        Logx.w("ads: splash activity not found, using keyword filter only");
        return null;
    }

    private static Class<?> resolveAd(ClassLoader cl, Class<?> splash) {
        Class<?> direct = Rf.cls(cl, AD_CLASS);
        if (direct != null) {
            return direct;
        }
        if (splash == null) {
            return null;
        }
        // Structural fallback: the type of the ad getter on the splash activity (or its view model).
        for (Class<?> owner : candidateOwners(cl, splash)) {
            for (Method m : Rf.allMethods(owner)) {
                Class<?> rt = m.getReturnType();
                if (rt.isPrimitive()) {
                    continue;
                }
                if ("Ad".equals(rt.getSimpleName())
                        && rt.getPackage() != null
                        && "com.chaoxing.mobile.activity".equals(rt.getPackage().getName())) {
                    return rt;
                }
            }
        }
        return null;
    }

    /** The splash activity itself plus every {@code *ViewModel} it holds. */
    private static List<Class<?>> candidateOwners(ClassLoader cl, Class<?> splash) {
        List<Class<?>> owners = new ArrayList<>();
        owners.add(splash);
        try {
            for (Field f : splash.getDeclaredFields()) {
                Class<?> t = f.getType();
                if (!t.isPrimitive() && !t.isArray() && t.getName().contains("ViewModel") && !owners.contains(t)) {
                    owners.add(t);
                }
            }
        } catch (Throwable ignored) {
        }
        Class<?> vm = Rf.cls(cl, SPLASH_VIEW_MODEL);
        if (vm != null && !owners.contains(vm)) {
            owners.add(vm);
        }
        return owners;
    }

    /** Hook #3: run the getter, keep its side effects, throw the ad away. */
    private static int hookAdSource(ClassLoader cl, Class<?> splash, final Class<?> ad) {
        int count = 0;
        for (Class<?> owner : candidateOwners(cl, splash)) {
            for (Method m : Rf.allMethods(owner)) {
                if (Modifier.isStatic(m.getModifiers()) || !ad.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length > 1) {
                    continue;
                }
                if (params.length == 1 && !Context.class.isAssignableFrom(params[0])) {
                    continue;
                }
                final String where = owner.getName() + "#" + m.getName();
                Hooks.all(owner, m.getName(), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!Cfg.get().ads) {
                            return;
                        }
                        Object result = param.getResult();
                        if (result != null) {
                            param.setResult(null);
                            Logx.i("ads: dropped splash ad from " + where);
                        }
                    }
                });
                count++;
            }
        }
        return count;
    }

    /** Hook #2: skip the preloader before it touches the network. */
    private static int hookPreloader(ClassLoader cl) {
        int count = 0;
        for (String className : PRELOADER_CLASSES) {
            Class<?> c = Rf.cls(cl, className);
            if (c == null) {
                continue;
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Method m : Rf.allMethods(c)) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length < 2 || !Context.class.isAssignableFrom(params[0])) {
                    continue;
                }
                if (params[1].isPrimitive() || params[1].getPackage() == null
                        || !"com.chaoxing.mobile.activity".equals(params[1].getPackage().getName())) {
                    continue;
                }
                if (!seen.add(m.getName())) {
                    continue;
                }
                final String where = className + "#" + m.getName();
                Hooks.all(c, m.getName(), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Cfg.get().ads) {
                            return;
                        }
                        param.setResult(null);
                        Logx.i("ads: skipped ad preload " + where);
                    }
                });
                count++;
            }
        }
        return count;
    }

    /** Hook #1: never open an ad landing page. */
    private static int hookStartActivity(final Class<?> splash) {
        XC_MethodHook block = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Cfg.get().ads || !Cfg.get().adsBlockActivity || param.args == null || param.args.length == 0) {
                    return;
                }
                Object target = param.thisObject;
                if (splash != null && target instanceof Activity && !splash.isInstance(target)) {
                    // Only the splash screen is filtered; a reward-ad button elsewhere is the user's call.
                    return;
                }
                Object raw = param.args[0];
                if (!(raw instanceof Intent)) {
                    return;
                }
                String cls = component((Intent) raw);
                if (cls == null) {
                    return;
                }
                String lower = cls.toLowerCase(Locale.ROOT);
                for (String mark : AD_ACTIVITY_MARKS) {
                    if (lower.contains(mark)) {
                        param.setResult(null);
                        Logx.i("ads: blocked ad activity " + cls);
                        return;
                    }
                }
            }
        };
        Hooks.all(Activity.class, "startActivity", block);
        Hooks.all(Activity.class, "startActivityForResult", block);
        Hooks.all(android.content.ContextWrapper.class, "startActivity", block);
        return 3;
    }

    private static String component(Intent intent) {
        try {
            if (intent.getComponent() != null) {
                return intent.getComponent().getClassName();
            }
            if (intent.getPackage() != null) {
                return intent.getPackage();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
