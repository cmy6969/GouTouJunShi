package com.goutou.junshi.stealth;

import android.app.Activity;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import de.robv.android.xposed.XC_MethodHook;
import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Hooks;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Rf;
import com.goutou.junshi.core.Ui;

/**
 * Window-level countermeasures.
 *
 * <p>{@code FLAG_SECURE} is what makes screenshots and screen recordings come out black.  The host
 * applies it through {@link Window#addFlags}, {@link Window#setFlags} and
 * {@link Window#setAttributes}; the concrete implementation is {@code PhoneWindow}, which overrides
 * part of that surface, so both classes are hooked.  As a last resort the flag is also wiped
 * directly from the window attributes whenever an activity becomes visible — that path does not
 * depend on any hook being reached.</p>
 */
public final class Capture {

    private static final int FLAG_SECURE = WindowManager.LayoutParams.FLAG_SECURE;
    private static final String PHONE_WINDOW = "com.android.internal.policy.PhoneWindow";

    private Capture() {
    }

    public static void install(ClassLoader cl, Cfg.Snapshot cfg) {
        try {
            hookWindow(Window.class);
            hookWindow(Rf.cls(cl, PHONE_WINDOW));
            hookSurface();
            hookLifecycle();
            Logx.i("capture: FLAG_SECURE stripped (window + phoneWindow + scrub)");
        } catch (Throwable t) {
            Logx.e("capture install", t);
        }
    }

    private static void hookWindow(Class<?> type) {
        if (type == null) {
            return;
        }
        XC_MethodHook addFlags = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Cfg.get().unblockCapture || param.args == null || param.args.length == 0) {
                    return;
                }
                Object a0 = param.args[0];
                if (a0 instanceof Integer && (((Integer) a0) & FLAG_SECURE) != 0) {
                    param.args[0] = ((Integer) a0) & ~FLAG_SECURE;
                }
            }
        };
        Hooks.all(type, "addFlags", addFlags);
        Hooks.all(type, "addSystemFlags", addFlags);

        Hooks.all(type, "setFlags", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Cfg.get().unblockCapture || param.args == null || param.args.length < 2) {
                    return;
                }
                Object flags = param.args[0];
                Object mask = param.args[1];
                if (flags instanceof Integer && mask instanceof Integer) {
                    int f = (Integer) flags;
                    int m = (Integer) mask;
                    if ((f & FLAG_SECURE) != 0) {
                        param.args[0] = f & ~FLAG_SECURE;
                        param.args[1] = m & ~FLAG_SECURE;
                    }
                }
            }
        });

        Hooks.all(type, "setAttributes", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Cfg.get().unblockCapture || param.args == null || param.args.length == 0) {
                    return;
                }
                Object a0 = param.args[0];
                if (a0 instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) a0;
                    if ((lp.flags & FLAG_SECURE) != 0) {
                        lp.flags &= ~FLAG_SECURE;
                    }
                }
            }
        });
    }

    private static void hookSurface() {
        Hooks.all(SurfaceView.class, "setSecure", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Cfg.get().unblockCapture && param.args != null && param.args.length == 1) {
                    param.args[0] = Boolean.FALSE;
                }
            }
        });
    }

    /**
     * Walks the current window of every resumed activity and drops the flag directly.
     *
     * <p>Runs slightly delayed so that it also catches pages that set the flag inside
     * {@code onResume} or after the first layout pass.</p>
     */
    private static void hookLifecycle() {
        Hooks.all(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof Activity) || !Cfg.get().unblockCapture) {
                    return;
                }
                final Activity activity = (Activity) param.thisObject;
                Ui.later(new Runnable() {
                    @Override
                    public void run() {
                        scrub(activity);
                    }
                }, 400L);
            }
        });
        Hooks.all(Activity.class, "onWindowFocusChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof Activity) || !Cfg.get().unblockCapture) {
                    return;
                }
                scrub((Activity) param.thisObject);
            }
        });
    }

    /** Removes FLAG_SECURE from the window attributes of the given activity. */
    public static void scrub(Activity activity) {
        if (activity == null || !Cfg.get().unblockCapture) {
            return;
        }
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp == null || (lp.flags & FLAG_SECURE) == 0) {
                return;
            }
            lp.flags &= ~FLAG_SECURE;
            window.setAttributes(lp);
            Logx.i("capture: scrubbed " + activity.getClass().getSimpleName());
        } catch (Throwable t) {
            Logx.e("capture scrub", t);
        }
    }
}
