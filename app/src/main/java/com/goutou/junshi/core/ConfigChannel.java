package com.goutou.junshi.core;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Live configuration channel.
 *
 * <p>The settings activity lives in the module package and the hooks live in the host process, so a
 * freshly saved configuration is pushed over an explicit broadcast.  When the host is not running
 * the same JSON is picked up from {@code Download/Lumen/config.json} on its next start.</p>
 */
public final class ConfigChannel {

    public interface Listener {
        void onConfigChanged();
    }

    private static volatile boolean installed;
    private static volatile Listener listener;

    private ConfigChannel() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void install(Application app) {
        if (installed || app == null) {
            return;
        }
        installed = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    // "Pull" request from the settings screen: hand back what is actually running so
                    // the UI can show the real mode instead of its own older copy.
                    if (Cfg.ACTION_PULL.equals(intent.getAction())) {
                        try {
                            String current = Cfg.readConfigText();
                            if (current != null) {
                                setResultData(Cfg.withRuntimeMode(current,
                                        com.goutou.junshi.answer.Engine.modeOverrideForConfig()));
                            }
                        } catch (Throwable t) {
                            Logx.w("config pull failed: " + t.getClass().getSimpleName());
                        }
                        return;
                    }
                    String json = intent.getStringExtra(Cfg.EXTRA_JSON);
                    try {
                        if (json != null && json.length() > 1) {
                            Cfg.applyInline(json);
                        } else {
                            Cfg.reload(true);
                        }
                        Logx.i("config channel: refreshed " + Cfg.digest());
                        // Tells the settings screen that a live host consumed the push
                        // (only meaningful for ordered broadcasts).
                        try {
                            setResultCode(1);
                        } catch (Throwable ignored) {
                        }
                        Listener l = listener;
                        if (l != null) {
                            l.onConfigChanged();
                        }
                    } catch (Throwable t) {
                        Logx.e("config channel apply", t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Cfg.ACTION_PUSH);
            filter.addAction(Cfg.ACTION_PULL);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            Logx.i("config channel: listening");
        } catch (Throwable t) {
            Logx.e("config channel install", t);
        }
    }
}
