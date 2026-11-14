package com.goutou.junshi.answer;

import android.app.Activity;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Hooks;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Rf;
import com.goutou.junshi.core.Ui;

/**
 * Automatic answering — installation and WebView wiring.
 *
 * <p>The host renders every quiz page (作业 / 章节测验 / 考试 / 随堂小测) in a WebView, and it uses two
 * different WebView implementations in the same process: the platform one and Tencent X5.  Both are
 * instrumented identically:</p>
 *
 * <ul>
 *   <li>{@code loadUrl} — a new page is being loaded, remember the URL and queue a scan;</li>
 *   <li>{@code setWebViewClient} — remember the concrete client so its {@code onPageFinished} can be
 *       hooked (a subclass override is invisible to a hook placed on the base class alone);</li>
 *   <li>{@code onPageFinished} — the DOM is ready, inject the probe.</li>
 * </ul>
 */
public final class Answer {

    private static volatile boolean x5Hooked;

    private Answer() {
    }

    public static void install(ClassLoader cl, Cfg.Snapshot cfg) {
        int before = Hooks.count;
        installFamily(cl,
                "android.webkit.WebView",
                "android.webkit.WebViewClient");
        installFamily(cl,
                "com.tencent.smtt.sdk.WebView",
                "com.tencent.smtt.sdk.WebViewClient");
        hookActivity(cl);
        Ball.setAction(new Ball.Action() {
            @Override
            public void onTap() {
                WebRef web = Engine.currentWebView();
                if (web == null) {
                    Ui.toast("Lumen：没有找到网页");
                    return;
                }
                Ball.busy();
                Engine.scanCurrent(web);
            }

            @Override
            public void onLongPress() {
                boolean auto = Engine.toggleMode();
                Ball.refresh();
                Ui.toast(auto
                        ? "Lumen：已切换为「自动答题」，检测到题目就答"
                        : "Lumen：已切换为「手动答题」，点一下答当前题");
            }
        });
        Logx.i("answer: installed (" + (Hooks.count - before) + " hooks)");
    }

    /**
     * Hooks Tencent X5 on demand.
     *
     * <p>学习通 loads the X5 kernel lazily: at {@code handleLoadPackage} time the class may not exist
     * yet, so the first install attempt reports "absent".  Every activity resume retries once, which
     * is enough because X5 is loaded before the first X5 page can be created.</p>
     */
    static void ensureX5() {
        if (x5Hooked) {
            return;
        }
        ClassLoader cl = Hooks.host;
        if (cl == null) {
            return;
        }
        Class<?> x5 = Rf.cls(cl, "com.tencent.smtt.sdk.WebView");
        if (x5 == null) {
            return;
        }
        x5Hooked = true;
        int before = Hooks.count;
        installFamily(cl, "com.tencent.smtt.sdk.WebView", "com.tencent.smtt.sdk.WebViewClient");
        Logx.i("answer: X5 hooked late (" + (Hooks.count - before) + " hooks)");
    }

    private static void installFamily(ClassLoader cl, String webViewName, String clientName) {
        Class<?> webView = Rf.cls(cl, webViewName);
        if (webView == null) {
            Logx.i("answer: " + webViewName + " absent");
            return;
        }
        Class<?> client = Rf.cls(cl, clientName);

        Hooks.all(webView, "loadUrl", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Cfg.get().answer || param.args == null || param.args.length == 0) {
                    return;
                }
                Object url = param.args[0];
                if (url instanceof String) {
                    Engine.onPage(param.thisObject, (String) url);
                }
            }
        });

        Hooks.all(webView, "setWebViewClient", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                    return;
                }
                hookClientMethods(param.args[0].getClass());
            }
        });

        if (client != null) {
            Hooks.all(client, "onPageFinished", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Cfg.get().answer || param.args == null || param.args.length < 2) {
                        return;
                    }
                    Engine.onPage(param.args[0], String.valueOf(param.args[1]));
                }
            });
        }
    }

    private static void hookClientMethods(Class<?> concrete) {
        if (concrete == null) {
            return;
        }
        try {
            Method m = Rf.arity(concrete, "onPageFinished", 2);
            if (m == null) {
                return;
            }
            Class<?> owner = m.getDeclaringClass();
            Hooks.all(owner, "onPageFinished", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Cfg.get().answer || param.args == null || param.args.length < 2) {
                        return;
                    }
                    Engine.onPage(param.args[0], String.valueOf(param.args[1]));
                }
            });
        } catch (Throwable t) {
            Logx.e("answer: hook concrete client", t);
        }
    }

    private static void hookActivity(ClassLoader cl) {
        Hooks.all(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ensureX5();
                if (param.thisObject instanceof Activity) {
                    Ball.onActivityResumed((Activity) param.thisObject);
                }
            }
        });
        Hooks.all(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Activity) {
                    Ball.onActivityDestroyed((Activity) param.thisObject);
                }
            }
        });
    }
}
