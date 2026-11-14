package com.goutou.junshi.answer;

import android.app.Activity;
import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import com.goutou.junshi.core.Activities;
import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Hooks;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Ui;

/**
 * Answers the current question when a volume key is pressed.
 *
 * <p>The key is only swallowed when a quiz page is in front: on any other screen the event goes on
 * to the system, so volume control keeps working.  One press equals one tap on the floating button,
 * with a short debounce so a bouncing key cannot fire twice.</p>
 */
public final class VolumeKey {

    private static final long DEBOUNCE_MS = 800;
    private static volatile long lastFire;
    private static boolean installed;

    private VolumeKey() {
    }

    public static void install() {
        if (installed || !Activities.isMainProcess()) {
            return;
        }
        installed = true;
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.thisObject instanceof Activity) || param.args == null
                            || param.args.length == 0 || !(param.args[0] instanceof KeyEvent)) {
                        return;
                    }
                    if (handle((Activity) param.thisObject, (KeyEvent) param.args[0])) {
                        param.setResult(Boolean.TRUE);
                    }
                } catch (Throwable t) {
                    Logx.e("volume key", t);
                }
            }
        };
        int before = Hooks.count;
        // Some WebView hosts consume the event before dispatchKeyEvent returns; onKeyDown is a backstop.
        Hooks.all(Activity.class, "dispatchKeyEvent", hook);
        Hooks.all(Activity.class, "onKeyDown", hook);
        Logx.i("volume key: installed (" + (Hooks.count - before) + " hooks)");
    }

    /** @return true when the event was consumed */
    private static boolean handle(Activity activity, KeyEvent event) {
        String cfg = Cfg.get().volumeKey;
        if (cfg == null || cfg.isEmpty() || "off".equalsIgnoreCase(cfg)) {
            return false;
        }
        int code = event.getKeyCode();
        boolean down = code == KeyEvent.KEYCODE_VOLUME_DOWN;
        boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
        if (!down && !up) {
            return false;
        }
        if ("down".equalsIgnoreCase(cfg) && !down) {
            return false;
        }
        if ("up".equalsIgnoreCase(cfg) && !up) {
            return false;
        }
        // Only steal the key on a page where answering makes sense.
        if (!isQuizActivity(activity) || Engine.currentWebView() == null) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true; // consume the matching UP as well
        }
        if (event.getRepeatCount() > 0) {
            return true; // keep holding: do not let the volume panel appear
        }
        long now = System.currentTimeMillis();
        if (now - lastFire < DEBOUNCE_MS) {
            return true;
        }
        lastFire = now;
        Ui.run(new Runnable() {
            @Override
            public void run() {
                WebRef web = Engine.currentWebView();
                if (web == null) {
                    return;
                }
                Ball.busy();
                Logx.i("volume key: answer current page");
                Engine.scanCurrent(web);
            }
        });
        return true;
    }

    /** Web pages of the host app only (作业/考试/自测 all run inside these viewers). */
    private static boolean isQuizActivity(Activity activity) {
        String name;
        try {
            name = activity.getClass().getName();
        } catch (Throwable t) {
            return false;
        }
        return name.contains("webapp") || name.contains("exam") || name.contains("homework")
                || name.contains("work.ui");
    }
}
