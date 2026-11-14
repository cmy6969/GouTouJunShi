package com.goutou.junshi.core;

import android.app.Application;

import com.goutou.junshi.ads.Ads;
import com.goutou.junshi.answer.Answer;
import com.goutou.junshi.answer.VolumeKey;
import com.goutou.junshi.stealth.Capture;
import com.goutou.junshi.stealth.Detect;
import com.goutou.junshi.stealth.Env;
import com.goutou.junshi.stealth.Proctor;

/**
 * Boot sequence for the hooked process.
 *
 * <p>{@code handleLoadPackage} runs before the host's {@code Application.onCreate}, which is exactly
 * where the detection guard has to be: the packer probes the environment while the application is
 * still starting up, and a hook installed afterwards would be too late.  Everything that needs a
 * {@link android.content.Context} (config channel, log file) is finalised from a hook on
 * {@code Application.onCreate}.</p>
 */
public final class Boot {

    public static final String TARGET_PACKAGE = "com.chaoxing.mobile";

    private static volatile boolean started;

    private Boot() {
    }

    public static void start(ClassLoader cl) {
        if (started) {
            return;
        }
        started = true;
        try {
            Cfg.reload(true);
            Cfg.Snapshot cfg = Cfg.get();
            Hooks.host = cl;

            // Nothing here needs a context: install immediately, before the host wakes up.
            Activities.install();
            if (cfg.stealth) {
                Detect.install(cl, cfg);
                if (cfg.hideEnv) {
                    Env.install(cl);
                }
            }
            if (cfg.stealth && cfg.proctor) {
                Proctor.install(cl, cfg);
            }
            if (cfg.stealth && cfg.unblockCapture) {
                Capture.install(cl, cfg);
            }
            if (cfg.ads) {
                Ads.install(cl, cfg);
            }
            if (cfg.answer) {
                Answer.install(cl, cfg);
                VolumeKey.install();
            }
            Logx.i("boot: hooks=" + Hooks.count + " (stealth=" + cfg.stealth
                    + " ads=" + cfg.ads + " answer=" + cfg.answer + ")");

            hookApplicationStart(cl);
        } catch (Throwable t) {
            Logx.e("boot failed", t);
        }
    }

    /**
     * Finishes the initialisation once the host application exists.
     *
     * <p>Deliberately {@code after} the original call: the host's own startup work must not observe a
     * half-initialised module, and a failure here must not abort {@code onCreate}.</p>
     */
    private static void hookApplicationStart(ClassLoader cl) {
        Hooks.all(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.thisObject instanceof Application)) {
                        return;
                    }
                    Application app = (Application) param.thisObject;
                    Activities.setApplication(app);
                    Logx.bind(app.getCacheDir());
                    ConfigChannel.setListener(new ConfigChannel.Listener() {
                        @Override
                        public void onConfigChanged() {
                            // A fresh config must win over runtime toggles (long press on the ball).
                            com.goutou.junshi.answer.Engine.resetOverrides();
                            com.goutou.junshi.answer.Ball.refresh();
                        }
                    });
                    ConfigChannel.install(app);
                    Cfg.reload(true);
                    Logx.i("host ready: " + app.getPackageName() + " proc=" + Activities.processName()
                            + " sdk=" + android.os.Build.VERSION.SDK_INT
                            + " cfg=" + (Cfg.readConfigText() == null ? "none" : "ok")
                            + " " + Cfg.digest());
                } catch (Throwable t) {
                    Logx.e("application started hook", t);
                }
            }
        });
    }
}
