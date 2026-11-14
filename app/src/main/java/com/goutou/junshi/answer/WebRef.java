package com.goutou.junshi.answer;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Rf;

/**
 * Adapter over "a WebView" without depending on one concrete class.
 *
 * <p>The host app uses both the platform {@code android.webkit.WebView} and the Tencent X5
 * ({@code com.tencent.smtt.sdk.WebView}) implementation depending on the device and the page.  Both
 * expose {@code loadUrl}, {@code evaluateJavascript} and {@code addJavascriptInterface} with the same
 * shape but they are unrelated types, so all interaction goes through reflection and a dynamic proxy
 * for the value callback.</p>
 */
public final class WebRef {

    public interface JsResult {
        void onValue(String value);
    }

    /** Callback used when the caller does not care about the value but wants a real evaluation. */
    private static final JsResult NOOP = new JsResult() {
        @Override
        public void onValue(String value) {
        }
    };

    private final Object target;
    private final Class<?> type;
    private final Method eval1;
    private final Method eval2;
    private final Class<?> valueCallback;

    private WebRef(Object target, Class<?> type) {
        this.target = target;
        this.type = type;
        this.eval2 = Rf.arity(type, "evaluateJavascript", 2);
        this.eval1 = Rf.arity(type, "evaluateJavascript", 1);
        Class<?> cb = null;
        if (eval2 != null) {
            Class<?>[] params = eval2.getParameterTypes();
            if (params.length == 2) {
                cb = params[1];
            }
        }
        this.valueCallback = cb;
    }

    public static WebRef of(Object webView) {
        if (webView == null) {
            return null;
        }
        try {
            Class<?> c = webView.getClass();
            while (c != null) {
                if ("android.webkit.WebView".equals(c.getName())
                        || "com.tencent.smtt.sdk.WebView".equals(c.getName())) {
                    return new WebRef(webView, c);
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public Object raw() {
        return target;
    }

    public String url() {
        try {
            Method m = Rf.method(type, "getUrl");
            Object v = Rf.call(target, m);
            if (v == null) {
                v = Rf.call(target, Rf.arity(type, "getUrl", 0));
            }
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public void eval(String js, final JsResult result) {
        try {
            JsResult sink = result == null ? NOOP : result;
            if (eval2 != null && valueCallback != null && valueCallback.isInterface()) {
                ClassLoader loader = valueCallback.getClassLoader();
                if (loader == null) {
                    loader = WebRef.class.getClassLoader();
                }
                Object callback = Proxy.newProxyInstance(loader,
                        new Class<?>[]{valueCallback}, new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) {
                                if ("onReceiveValue".equals(method.getName()) && args != null && args.length == 1) {
                                    Object v = args[0];
                                    safeResult(sink, v == null ? "" : String.valueOf(v));
                                }
                                return null;
                            }
                        });
                Rf.call(target, eval2, js, callback);
                return;
            }
            if (eval1 != null) {
                Rf.call(target, eval1, js);
                return;
            }
            Method load = Rf.method(type, "loadUrl", String.class);
            if (load != null) {
                Rf.call(target, load, "javascript:" + js);
            }
        } catch (Throwable t) {
            Logx.e("eval failed", t);
        }
    }

    private static void safeResult(JsResult result, String value) {
        try {
            result.onValue(value);
        } catch (Throwable t) {
            Logx.e("js result handler", t);
        }
    }

    /** Adds a JavaScript interface object (used as an optional push channel). */
    public boolean addInterface(String name, Object bridge) {
        try {
            Method m = Rf.method(type, "addJavascriptInterface", Object.class, String.class);
            if (m == null) {
                return false;
            }
            Rf.call(target, m, bridge, name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Index of this instance, used as a session key. */
    public int id() {
        return System.identityHashCode(target);
    }
}
