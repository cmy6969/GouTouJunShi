package com.goutou.junshi.ui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import com.goutou.junshi.core.Cfg;

/**
 * Persists the configuration and pushes it to a running host process.
 *
 * <p>The host process cannot read the module's private preferences (different uid, different
 * sandbox), so the configuration is a plain JSON file in {@code Download/Lumen/config.json}.  On
 * Android 10+ that file is written through MediaStore — which needs no storage permission — and on
 * older releases through the legacy file API.</p>
 */
public final class ConfigIO {

    public static final String DIR = "Lumen";

    private ConfigIO() {
    }

    public static JSONObject defaults() {
        JSONObject root = new JSONObject();
        try {
            JSONObject stealth = new JSONObject();
            stealth.put("on", true);
            stealth.put("detect_guard", true);
            stealth.put("hide_env", false);
            stealth.put("unblock_capture", true);
            stealth.put("proctor", true);
            stealth.put("webview_stealth", true);

            JSONObject ads = new JSONObject();
            ads.put("on", true);
            ads.put("block_activity", true);

            JSONObject answer = new JSONObject();
            answer.put("on", true);
            answer.put("bank_enabled", true);
            answer.put("mode", "auto");
            answer.put("volume_key", "down");
            answer.put("toast", true);
            answer.put("auto_fill", true);
            answer.put("auto_next", false);
            answer.put("ball", true);
            answer.put("delay_ms", 900);
            answer.put("banks", new JSONArray());
            answer.put("bank_file", "");
            answer.put("axe_token", "");
            answer.put("axe_base", "https://tk.wanjuantiku.com");
            answer.put("axe_ai", false);
            answer.put("axe_course", "");

            JSONObject ai = new JSONObject();
            ai.put("on", false);
            ai.put("vision_on", true);
            ai.put("vision_model", "");
            ai.put("base", "https://api.deepseek.com");
            ai.put("path", "/chat/completions");
            ai.put("model", "deepseek-chat");
            ai.put("key", "");
            ai.put("timeout_ms", 20000);
            ai.put("system", "");
            answer.put("ai", ai);

            root.put("v", 1);
            root.put("stealth", stealth);
            root.put("ads", ads);
            root.put("answer", answer);
        } catch (Throwable ignored) {
        }
        return root;
    }

    /** Reads the configuration this app stored (private preferences), or the defaults. */
    public static JSONObject read(Context ctx) {
        try {
            String text = null;
            if (ctx != null) {
                SharedPreferences own = ctx.getSharedPreferences(Cfg.PREFS_NAME, Context.MODE_PRIVATE);
                text = own.getString(Cfg.PREFS_KEY, null);
                if (text == null) {
                    text = PreferenceManager.getDefaultSharedPreferences(ctx)
                            .getString(Cfg.PREFS_KEY, null);
                }
            }
            if (text == null) {
                text = Cfg.readConfigText();
            }
            if (text != null && text.trim().startsWith("{")) {
                JSONObject o = new JSONObject(text);
                JSONObject d = defaults();
                JSONObject stealth = merge(d.optJSONObject("stealth"), o.optJSONObject("stealth"));
                JSONObject ads = merge(d.optJSONObject("ads"), o.optJSONObject("ads"));
                JSONObject answer = merge(d.optJSONObject("answer"), o.optJSONObject("answer"));
                JSONObject out = new JSONObject();
                out.put("v", 1);
                out.put("stealth", stealth);
                out.put("ads", ads);
                out.put("answer", answer);
                return out;
            }
        } catch (Throwable ignored) {
        }
        return defaults();
    }

    /** Backwards-compatible variant used when no context is at hand. */
    public static JSONObject read() {
        return read(null);
    }

    private static JSONObject merge(JSONObject base, JSONObject override) {
        if (base == null) {
            base = new JSONObject();
        }
        if (override == null) {
            return base;
        }
        try {
            java.util.Iterator<String> it = override.keys();
            while (it.hasNext()) {
                String k = it.next();
                base.put(k, override.opt(k));
            }
        } catch (Throwable ignored) {
        }
        return base;
    }

    /**
     * Stores the configuration in the module's private preferences and offers it to a running host.
     *
     * <p>Nothing is written to shared storage any more: the JSON contains the question-bank token and
     * the model API key, and {@code Download/} is readable by every app with storage access.  The
     * private preferences are the only copy this app keeps; the host keeps its own copy inside its
     * data directory after a successful push.</p>
     */
    public interface SaveCallback {
        /** Called on the main thread once the config was stored and the push was attempted. */
        void onSaved(Result result);
    }

    /**
     * Stores the configuration and pushes it, off the main thread.
     *
     * <p>The push waits for the broadcast result, and that result is delivered on the main looper —
     * so doing the wait on the main thread would block the very callback it is waiting for (that made
     * a running host look like it was "not running").  Everything blocking therefore happens here.</p>
     */
    public static void saveAsync(final Context ctx, final JSONObject config, final SaveCallback cb) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final Result result = save(ctx, config);
                if (cb != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onSaved(result);
                        }
                    });
                }
            }
        }, "lumen-config-save");
        worker.setDaemon(true);
        worker.start();
    }

    public static Result save(Context ctx, JSONObject config) {
        String json;
        try {
            json = config.toString(2);
        } catch (Throwable t) {
            json = config.toString();
        }
        boolean prefsOk = writePrefs(ctx, json);
        boolean pushed = push(ctx, json);
        return new Result(prefsOk, pushed, json);
    }

    /**
     * Mirrors the JSON into the module's own preferences.
     *
     * <p>The host process reads it through {@code XSharedPreferences}, which is the only channel
     * that works on a cold start without giving 学习通 storage permission.</p>
     */
    private static boolean writePrefs(Context ctx, String json) {
        try {
            // The default preferences file is the one the framework hands to the hooked app through
            // XSharedPreferences (see the "xposedsharedprefs" metadata), so it must be written too.
            SharedPreferences main = PreferenceManager.getDefaultSharedPreferences(ctx);
            SharedPreferences own =
                    ctx.getSharedPreferences(Cfg.PREFS_NAME, Context.MODE_PRIVATE);
            return main.edit().putString(Cfg.PREFS_KEY, json).commit()
                    & own.edit().putString(Cfg.PREFS_KEY, json).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Result of a save: which channels worked. */
    public static final class Result {
        public final boolean written;
        /** True when a running host actually consumed the push. */
        public final boolean pushed;
        public final String json;

        Result(boolean written, boolean pushed, String json) {
            this.written = written;
            this.pushed = pushed;
            this.json = json;
        }
    }

    /** Package name of the hooked app. */
    public static final String HOST_PACKAGE = "com.chaoxing.mobile";

    /**
     * Asks a running host for the configuration it is actually using.
     *
     * <p>Blocking — call it from a worker thread.  Returns {@code null} when nothing answered (host
     * not running or frozen by the ROM).</p>
     */
    public static String pull(Context ctx, long timeoutMs) {
        try {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String[] data = {null};
            android.content.BroadcastReceiver reply = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        data[0] = getResultData();
                    } catch (Throwable ignored) {
                    }
                    latch.countDown();
                }
            };
            Intent intent = new Intent(Cfg.ACTION_PULL);
            intent.setPackage(HOST_PACKAGE);
            ctx.sendOrderedBroadcast(intent, null, reply, new android.os.Handler(
                    android.os.Looper.getMainLooper()), android.app.Activity.RESULT_CANCELED, null, null);
            latch.await(Math.max(200L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            return data[0];
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Sends the configuration to a running host process.
     *
     * <p>Ordered broadcast with a result code from the receiver: that is the only reliable way to know
     * whether anything was listening (the ROM silently drops broadcasts to frozen processes).  A few
     * attempts are made because a just-backgrounded app may not be cached yet.</p>
     */
    private static boolean push(Context ctx, String json) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (pushOnce(ctx, json)) {
                return true;
            }
            try {
                Thread.sleep(350);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean pushOnce(Context ctx, String json) {
        try {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final boolean[] delivered = {false};
            android.content.BroadcastReceiver reply = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    delivered[0] = getResultCode() == 1;
                    latch.countDown();
                }
            };
            Intent intent = new Intent(Cfg.ACTION_PUSH);
            intent.setPackage(HOST_PACKAGE);
            intent.putExtra(Cfg.EXTRA_JSON, json);
            ctx.sendOrderedBroadcast(intent, null, reply, new android.os.Handler(
                    android.os.Looper.getMainLooper()), android.app.Activity.RESULT_CANCELED, null, null);
            latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
            return delivered[0];
        } catch (Throwable t) {
            return false;
        }
    }

}
