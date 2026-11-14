package com.goutou.junshi.core;

import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of the user configuration.
 *
 * <p>The settings activity of the module and the hooked host process live in two different apps, so
 * the config travels as a plain JSON file in the public {@code Download/Lumen} folder plus an
 * optional broadcast for instant refresh.  A snapshot is reloaded at most once per
 * {@link #TTL_MS} to keep the hook path allocation free.</p>
 */
public final class Cfg {

    public static final String DIR_NAME = "Lumen";
    /** Canonical config file name inside {@code Download/Lumen}. */
    public static final String FILE_NAME = "lumen_config.json";
    /** Older name, still read so an existing installation keeps working. */
    public static final String FILE_NAME_LEGACY = "config.json";
    public static final String ACTION_PUSH = "com.goutou.junshi.PUSH";
    /** Sent by the settings screen to ask the host for the configuration it is actually running. */
    public static final String ACTION_PULL = "com.goutou.junshi.PULL";
    public static final String EXTRA_JSON = "json";

    private static final long TTL_MS = 1500L;

    public static final class Snapshot {

        public final boolean ads;
        public final boolean adsBlockActivity;
        public final boolean stealth;
        public final boolean detectGuard;
        public final boolean hideEnv;
        public final boolean unblockCapture;
        public final boolean proctor;
        public final boolean answer;
        public final boolean bankAnswer;
        public final String mode;
        /** Volume-key trigger: {@code off} / {@code down} / {@code up} / {@code both}. */
        public final String volumeKey;
        public final boolean autoFill;        public final boolean autoNext;
        public final boolean ball;
        /** Show toast feedback (answer summary, "no questions on this page", …). */
        public final boolean toastOn;
        public final boolean stealthJs;
        public final boolean debugDump;
        /** Saves the stitched question screenshot to the host's external files dir (troubleshooting). */
        public final boolean debugShot;
        public final int delayMs;
        public final List<String> banks;
        public final String bankFile;
        /** Local bank entries carried inside the config itself ("stem TAB answer"). */
        public final List<String> bankInline;
        public final String axeToken;
        public final String axeBase;
        public final boolean axeAi;
        public final String axeCourse;
        public final boolean aiOn;
        public final String aiBase;
        public final String aiPath;
        public final String aiModel;
        public final String aiKey;
        public final int aiTimeout;
        public final String aiSystem;
        public final int aiMaxTokens;
        /** Screenshot answering for picture questions. */
        public final boolean visionOn;
        public final String visionBase;
        public final String visionPath;
        public final String visionModel;
        public final String visionKey;
        public final String visionPrompt;
        /** Provider hint such as {@code low}/{@code high}/{@code max} ("low" keeps answers short). */
        public final String aiEffort;

        public Snapshot(JSONObject root) {
            JSONObject adsObj = obj(root, "ads");
            JSONObject stObj = obj(root, "stealth");
            JSONObject anObj = obj(root, "answer");

            this.ads = flag(adsObj, "on", true);
            this.adsBlockActivity = flag(adsObj, "block_activity", true);

            this.stealth = flag(stObj, "on", true);
            this.detectGuard = flag(stObj, "detect_guard", true);
            this.hideEnv = flag(stObj, "hide_env", false);
            this.unblockCapture = flag(stObj, "unblock_capture", true);
            this.proctor = flag(stObj, "proctor", true);
            this.stealthJs = flag(stObj, "webview_stealth", true);

            this.answer = flag(anObj, "on", true);
            this.bankAnswer = flag(anObj, "bank_enabled", true);
            this.mode = anObj == null ? "auto" : anObj.optString("mode", "auto");
            this.volumeKey = anObj == null ? "down" : anObj.optString("volume_key", "down");
            this.autoFill = flag(anObj, "auto_fill", true);
            this.autoNext = flag(anObj, "auto_next", false);
            this.ball = flag(anObj, "ball", true);
            this.toastOn = flag(anObj, "toast", true);
            this.debugDump = flag(anObj, "debug_dump", false);
            this.debugShot = flag(anObj, "debug_shot", false);
            this.delayMs = clamp(anObj == null ? 900 : anObj.optInt("delay_ms", 900), 0, 15000);

            List<String> list = new ArrayList<>();
            JSONArray arr = anObj == null ? null : anObj.optJSONArray("banks");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) {
                        list.add(s);
                    }
                }
            }
            this.banks = Collections.unmodifiableList(list);
            List<String> inlined = new ArrayList<>();
            JSONArray bankArr = anObj == null ? null : anObj.optJSONArray("bank_inline");
            if (bankArr != null) {
                for (int i = 0; i < bankArr.length(); i++) {
                    String s = bankArr.optString(i, "");
                    if (s != null && !s.trim().isEmpty()) {
                        inlined.add(s);
                    }
                }
            }
            this.bankInline = Collections.unmodifiableList(inlined);
            String bf = anObj == null ? "" : anObj.optString("bank_file", "").trim();
            this.bankFile = bf.isEmpty() ? defaultBankFile() : bf;

            this.axeToken = anObj == null ? "" : anObj.optString("axe_token", "").trim();
            String ab = anObj == null ? "" : anObj.optString("axe_base", "").trim();
            this.axeBase = ab;
            this.axeAi = anObj != null && anObj.optBoolean("axe_ai", false);
            this.axeCourse = anObj == null ? "" : anObj.optString("axe_course", "").trim();

            JSONObject ai = anObj == null ? null : anObj.optJSONObject("ai");
            this.aiOn = ai != null && ai.optBoolean("on", false);
            this.aiBase = ai == null ? "" : ai.optString("base", "").trim();
            this.aiPath = ai == null ? "" : ai.optString("path", "").trim();
            this.aiModel = ai == null ? "" : ai.optString("model", "").trim();
            this.aiKey = ai == null ? "" : ai.optString("key", "").trim();
            this.aiTimeout = ai == null ? 0 : ai.optInt("timeout_ms", 0);
            this.aiSystem = ai == null ? "" : ai.optString("system", "").trim();
            this.aiMaxTokens = ai == null ? 0 : ai.optInt("max_tokens", 0);
            this.visionOn = ai == null || ai.optBoolean("vision_on", true);
            this.visionBase = ai == null ? "" : ai.optString("vision_base", "").trim();
            this.visionPath = ai == null ? "" : ai.optString("vision_path", "").trim();
            this.visionModel = ai == null ? "" : ai.optString("vision_model", "").trim();
            this.visionKey = ai == null ? "" : ai.optString("vision_key", "").trim();
            this.visionPrompt = ai == null ? "" : ai.optString("vision_prompt", "").trim();
            this.aiEffort = ai == null ? "low" : ai.optString("effort", "low").trim();
        }

        /** {@code answer.mode}: "auto" answers detected questions, "manual" waits for the button. */
        public boolean isAutoMode() {
            return !"manual".equalsIgnoreCase(mode);
        }

        private static String defaultBankFile() {
            File dir = downloadRoot();
            return dir == null ? "" : new File(new File(dir, DIR_NAME), "bank.txt").getAbsolutePath();
        }
    }

    private static volatile Snapshot cached = new Snapshot(null);
    private static volatile long cachedAt;
    private static volatile String revision = "";
    private static volatile String inline;
    private static final java.util.concurrent.atomic.AtomicBoolean RELOADING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private Cfg() {
    }

    /**
     * Current snapshot.
     *
     * <p>Never performs file IO on the calling thread: a stale snapshot triggers a background reload
     * and the previously loaded values are returned meanwhile, so the hook path stays latency free.
     * </p>
     */
    public static Snapshot get() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > TTL_MS) {
            cachedAt = now;
            if (RELOADING.compareAndSet(false, true)) {
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            reload(true);
                        } catch (Throwable ignored) {
                        } finally {
                            RELOADING.set(false);
                        }
                    }
                }, "lumen-cfg");
                worker.setDaemon(true);
                try {
                    worker.start();
                } catch (Throwable t) {
                    RELOADING.set(false);
                }
            }
        }
        return cached;
    }

    public static void reload(boolean force) {
        cachedAt = System.currentTimeMillis();
        String json = readConfigText();
        if (json != null && json.equals(revision) && !force) {
            return;
        }
        revision = json == null ? "" : json;
        JSONObject root = null;
        if (json != null) {
            try {
                root = new JSONObject(json);
            } catch (Throwable t) {
                Logx.e("config parse error", t);
            }
        }
        if (root != null) {
            persist(json);
        }
        cached = new Snapshot(root);
    }

    /** Install a live configuration pushed by the settings activity (survives until process death). */
    public static void applyInline(String json) {
        inline = json;
        revision = null;
        reload(true);
    }

    /** Reads the config text from the first readable candidate location, or {@code null}. */
    public static String readConfigText() {
        String live = inline;
        if (live != null) {
            return live;
        }
        // The host's own copy is what the last applied configuration was, so it comes first: it is a
        // cheap file read and keeps this method quiet (it runs on every reload check).
        File store = storeFile();
        if (store != null && store.isFile() && store.length() > 2) {
            String text = readText(store);
            if (text != null && text.trim().startsWith("{")) {
                return text;
            }
        }
        String fromPrefs = readFromXPrefs();
        if (fromPrefs != null) {
            return fromPrefs;
        }
        return readNewestFile();
    }

    /** One-line digest of the effective configuration, for the startup log. */
    public static String digest() {
        Snapshot s = cached;
        return "mode=" + (s.isAutoMode() ? "auto" : "manual")
                + " bank=" + (s.bankAnswer ? 1 : 0) + "/" + (s.axeToken.isEmpty() ? "no-token" : "token")
                + " ai=" + (s.aiOn ? 1 : 0) + "/" + (s.aiModel.isEmpty() ? "?" : s.aiModel)
                + " vision=" + (s.visionOn ? 1 : 0) + "/" + (s.visionModel.isEmpty() ? "main" : s.visionModel)
                + " effort=" + (s.aiEffort.isEmpty() ? "-" : s.aiEffort)
                + " fill=" + (s.autoFill ? 1 : 0) + " next=" + (s.autoNext ? 1 : 0)
                + " ball=" + (s.ball ? 1 : 0) + " toast=" + (s.toastOn ? 1 : 0)
                + " shot=" + (s.debugShot ? 1 : 0);
    }

    /**
     * Writes one value into the configuration that is currently in effect and re-applies it.
     *
     * <p>Used by the runtime switches (long press on the floating button): the change has to survive a
     * restart and has to be visible to the settings screen, so it cannot live in a memory-only
     * override.</p>
     */
    public static boolean setAnswerValue(String key, Object value) {
        try {
            String json = readConfigText();
            if (json == null || !json.trim().startsWith("{")) {
                return false;
            }
            JSONObject root = new JSONObject(json);
            JSONObject answer = root.optJSONObject("answer");
            if (answer == null) {
                answer = new JSONObject();
                root.put("answer", answer);
            }
            answer.put(key, value);
            applyInline(root.toString());
            Logx.i("config: set answer." + key + "=" + value + " → " + digest());
            return true;
        } catch (Throwable t) {
            Logx.e("config: set " + key + " failed", t);
            return false;
        }
    }

    /**
     * Returns {@code json} with {@code answer.mode} replaced by the mode that is actually in effect.
     *
     * <p>The runtime switch (long press on the floating button) takes precedence over the stored value
     * until the next pushed configuration, so the settings screen must be told about it.</p>
     */
    public static String withRuntimeMode(String json, int override) {
        try {
            if (override == 0) {
                return json;
            }
            JSONObject root = new JSONObject(json);
            JSONObject answer = root.optJSONObject("answer");
            if (answer == null) {
                answer = new JSONObject();
                root.put("answer", answer);
            }
            answer.put("mode", override > 0 ? "auto" : "manual");
            return root.toString();
        } catch (Throwable t) {
            return json;
        }
    }

    /** Reads the newest readable config file.
     * <p>Newest instead of "first": the host keeps its own copy inside its private files directory
     * (see {@link #persist}), and a newer file in {@code Download} (only readable when the host has
     * "all files access") must win over that copy.</p>
     */
    private static String readNewestFile() {
        String best = null;
        long bestAt = -1;
        for (File f : configFiles()) {
            try {
                if (!f.isFile() || f.length() <= 0 || f.length() > 512 * 1024) {
                    continue;
                }
                long at = f.lastModified();
                if (at <= bestAt) {
                    continue;
                }
                String text = readText(f);
                if (text != null) {
                    best = text;
                    bestAt = at;
                }
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private static String readText(File f) {
        BufferedReader reader = null;
        try {
            StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 65536));
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Keeps a copy of the configuration inside the host's own private files directory.
     *
     * <p>This is what makes the settings survive a restart: the host usually cannot read the file in
     * {@code Download} (no storage permission) and the framework may not support
     * {@code XSharedPreferences}, but it can always read its own data directory.  One successful
     * push — the settings screen broadcasts while the host runs — is therefore enough forever.</p>
     */
    private static void persist(String json) {
        if (json == null || json.length() < 2) {
            return;
        }
        File target = storeFile();
        if (target == null) {
            return;
        }
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (target.isFile() && target.length() == bytes.length) {
                // Same size is not proof of the same content ("auto" vs "manual" can be padded out
                // elsewhere), so compare the actual text before skipping the write.
                String current = readText(target);
                if (current != null && current.trim().equals(json.trim())) {
                    return;
                }
            }
            File dir = target.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File tmp = new File(dir, target.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(bytes);
                out.flush();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable t) {
            Logx.w("config persist failed: " + t.getClass().getSimpleName());
        }
    }

    /** The host's own private copy, readable without any permission. */
    private static File storeFile() {
        try {
            android.content.Context ctx = Activities.anyContext();
            if (ctx != null) {
                return new File(ctx.getFilesDir(), FILE_NAME);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Reads the configuration the settings activity stored in the module's own preferences.
     *
     * <p>Fallback for frameworks that implement {@code XSharedPreferences}.</p>
     */
    private static String readFromXPrefs() {
        // 1) Default preferences file — the only one the framework can serve via XSharedPreferences
        //    (enabled by the "xposedsharedprefs" metadata in the manifest).
        try {
            de.robv.android.xposed.XSharedPreferences prefs =
                    new de.robv.android.xposed.XSharedPreferences(Self.PACKAGE);
            prefs.reload();
            String json = prefs.getString(PREFS_KEY, null);
            if (json != null && json.trim().startsWith("{")) {
                Logx.i("config: read from XSharedPreferences (default)");
                return json;
            }
        } catch (Throwable t) {
            Logx.w("config: XSharedPreferences (default) failed: " + t.getClass().getName()
                    + ": " + t.getMessage());
        }
        // 2) Legacy named file, kept for older installs.
        try {
            de.robv.android.xposed.XSharedPreferences prefs =
                    new de.robv.android.xposed.XSharedPreferences(Self.PACKAGE, PREFS_NAME);
            prefs.reload();
            String json = prefs.getString(PREFS_KEY, null);
            if (json != null && json.trim().startsWith("{")) {
                Logx.i("config: read from XSharedPreferences (" + PREFS_NAME + ")");
                return json;
            }
        } catch (Throwable t) {
            Logx.w("config: XSharedPreferences (" + PREFS_NAME + ") failed: " + t.getClass().getName());
        }
        return null;
    }

    public static final String PREFS_NAME = "lumen_conf";
    public static final String PREFS_KEY = "json";

    /**
     * The only file the configuration may live in: the host's own private copy.
     *
     * <p>Shared storage is deliberately not consulted — the JSON carries the question-bank token and
     * the model API key, and {@code Download/} is readable by every app with storage access.</p>
     */
    public static List<File> configFiles() {
        List<File> out = new ArrayList<>(2);
        File store = storeFile();
        if (store != null) {
            out.add(store);
        }
        return out;
    }

    private static File downloadRoot() {
        try {
            File f = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (f != null) {
                return f;
            }
        } catch (Throwable ignored) {
        }
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                return new File(ext, "Download");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONObject obj(JSONObject root, String key) {
        if (root == null) {
            return null;
        }
        JSONObject o = root.optJSONObject(key);
        return o;
    }

    private static boolean flag(JSONObject obj, String key, boolean def) {
        return obj == null ? def : obj.optBoolean(key, def);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
