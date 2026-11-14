package com.goutou.junshi.answer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Logx;

/**
 * Question bank client.
 *
 * <p>Resolution order: in-memory cache, the local text bank, then every configured HTTP endpoint in
 * order.  An endpoint is a URL template containing at least {@code {q}}; the other placeholders are
 * {@code {type}} (host type code), {@code {mtype}} (a readable type name) and {@code {opts}} (the
 * URL encoded option list, {@code A.选项|B.选项}).</p>
 *
 * <p>Nothing is sent anywhere unless the user explicitly added an endpoint, and every endpoint is
 * the user's own responsibility — the module ships without a default provider on purpose.</p>
 */
public final class Bank {

    private static final int TIMEOUT_MS = 4500;
    private static final int CACHE_LIMIT = 400;

    private static final String[] FAILURE_MARKS = {
            "凭证为空", "访问人数", "限流", "请求失败", "未找到", "不存在", "余额", "过期", "无效",
            "token", "unauthorized", "forbidden", "error", "failed", "too many"
    };

    private static final Map<String, Result> CACHE = new LinkedHashMap<String, Result>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private static volatile Map<String, String> localBank = Collections.emptyMap();
    private static volatile String localBankKey = "";

    public static final class Result {
        public final String answer;
        public final String source;
        /** Human readable reason when the lookup failed (e.g. AXE quota exhausted). */
        public final String note;

        Result(String answer, String source) {
            this(answer, source, "");
        }

        Result(String answer, String source, String note) {
            this.answer = answer == null ? "" : answer;
            this.source = source == null ? "" : source;
            this.note = note == null ? "" : note;
        }
    }

    private Bank() {
    }

    /**
     * Looks up one question; returns {@code null} when nothing was found.
     *
     * <p>Two independent switches: {@code answer.bank_enabled} gates the question banks (AXE, local
     * file, custom endpoints) and {@code answer.ai.on} gates the AI fallback.  Turning the banks off
     * is therefore a valid way to test the AI path with every other source silenced.</p>
     */
    public static Result lookup(Question q, Cfg.Snapshot cfg) {
        String key = q.key();
        synchronized (CACHE) {
            Result hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
        }
        String bankNote = "";
        if (cfg.bankAnswer) {
            if (cfg.axeToken != null && !cfg.axeToken.isEmpty()) {
                AxeBank.Hit axe = AxeBank.query(q, cfg);
                if (axe != null) {
                    if (!axe.answer.isEmpty()) {
                        Result r = new Result(axe.answer, "AXE");
                        remember(key, r);
                        Logx.i("axe: hit " + (axe.lefts >= 0 ? ("left=" + axe.lefts) : "") + " " + q.id);
                        return r;
                    }
                    bankNote = axe.note;
                    Logx.w("axe: miss " + q.id + " :: " + axe.note);
                }
            }
            Result local = fromLocal(q, cfg);
            if (local != null) {
                remember(key, local);
                return local;
            }
            for (String endpoint : cfg.banks) {
                Result r = fromEndpoint(q, endpoint);
                if (r != null) {
                    remember(key, r);
                    return r;
                }
            }
        } else {
            Logx.i("bank: banks disabled by switch (" + q.id + "), skipping AXE/local/custom");
        }
        if (cfg.aiOn) {
            AiBank.Hit ai = AiBank.query(q, cfg);
            if (ai != null) {
                if (!ai.answer.isEmpty()) {
                    Result r = new Result(ai.answer, "AI");
                    remember(key, r);
                    return r;
                }
                if (bankNote.isEmpty()) {
                    bankNote = ai.note;
                }
                Logx.w("ai: miss " + q.id + " :: " + ai.note);
            }
        }
        return bankNote.isEmpty() ? null : new Result("", "", bankNote);
    }

    private static void remember(String key, Result r) {
        synchronized (CACHE) {
            CACHE.put(key, r);
        }
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    // ------------------------------------------------------------------ local

    private static Result fromLocal(Question q, Cfg.Snapshot cfg) {
        String path = cfg.bankFile;
        if (path == null || path.isEmpty()) {
            return null;
        }
        Map<String, String> bank = loadLocal(path);
        if (bank.isEmpty()) {
            return null;
        }
        String hit = bank.get(fold(q.stem));
        if (hit == null) {
            // Fuzzy fallback: the local bank may store a slightly different stem.
            String best = null;
            double bestScore = 0.82;
            for (Map.Entry<String, String> e : bank.entrySet()) {
                double score = Norm.similar(e.getKey(), fold(q.stem));
                if (score > bestScore) {
                    bestScore = score;
                    best = e.getValue();
                }
            }
            hit = best;
        }
        return hit == null ? null : new Result(hit, "本地题库");
    }

    private static Map<String, String> loadLocal(String path) {
        File f = new File(path);
        String stamp = path + ":" + f.length() + ":" + f.lastModified();
        if (stamp.equals(localBankKey)) {
            return localBank;
        }
        localBankKey = stamp;
        Map<String, String> map = new LinkedHashMap<>();
        // Entries carried inside the config work on every device: the host app usually has no
        // permission to read files under Download.
        Cfg.Snapshot cfg = Cfg.get();
        if (cfg != null) {
            for (String raw : cfg.bankInline) {
                putLine(map, raw);
            }
        }
        int inlineSize = map.size();
        BufferedReader reader = null;
        try {
            if (f.isFile()) {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    putLine(map, line);
                }
            }
        } catch (Throwable t) {
            Logx.e("local bank read", t);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        localBank = Collections.unmodifiableMap(map);
        Logx.i("local bank loaded: " + map.size() + " entries (inline=" + inlineSize
                + ", file=" + (f.isFile() ? "readable" : "unreadable") + ")");
        return localBank;
    }

    /** Parses one {@code stem<TAB>answer} (or {@code stem==answer}) line. */
    private static void putLine(Map<String, String> map, String line) {
        if (line == null) {
            return;
        }
        String raw = line.trim();
        if (raw.isEmpty() || raw.startsWith("#")) {
            return;
        }
        int tab = raw.indexOf('\t');
        boolean doubleEq = false;
        if (tab < 0) {
            tab = raw.indexOf("==");
            doubleEq = tab >= 0;
        }
        if (tab <= 0 || tab >= raw.length() - 1) {
            return;
        }
        String stem = fold(raw.substring(0, tab));
        String ans = raw.substring(tab + (doubleEq ? 2 : 1)).trim();
        if (!stem.isEmpty() && !ans.isEmpty()) {
            map.put(stem, ans);
        }
    }

    private static String fold(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- endpoint

    private static Result fromEndpoint(Question q, String template) {
        if (template == null || template.isEmpty() || !template.contains("{q}")) {
            return null;
        }
        String url = template
                .replace("{q}", enc(q.stem))
                .replace("{type}", String.valueOf(q.type))
                .replace("{mtype}", enc(typeName(q.type)))
                .replace("{opts}", enc(optionLine(q)));
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Lumen/1.0 (Android)");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            int code = conn.getResponseCode();
            if (code != 200) {
                Logx.w("bank http " + code + " " + host(url));
                return null;
            }
            String body = read(conn.getInputStream());
            String answer = extract(body);
            if (answer.isEmpty()) {
                return null;
            }
            return new Result(answer, host(url));
        } catch (Throwable t) {
            Logx.w("bank error " + host(url) + " :: " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder(256);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 256 * 1024) {
                    break;
                }
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return "endpoint";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String optionLine(Question q) {
        if (q.options.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Question.Option o : q.options) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(o.key).append('.').append(o.text);
        }
        return sb.toString();
    }

    static String typeName(int type) {
        switch (type) {
            case 0:
                return "单选题";
            case 1:
                return "多选题";
            case 2:
                return "填空题";
            case 3:
                return "判断题";
            case 4:
                return "简答题";
            case 5:
                return "名词解释";
            case 14:
                return "完型填空";
            case 15:
                return "阅读理解";
            default:
                return "问答题";
        }
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Pulls an answer out of an arbitrary JSON (or plain text) body.
     *
     * <p>Banks in the wild answer with {@code {"answer":"A"}}, {@code {"code":1000,"data":{...}}},
     * {@code {"result":"对"}} or just a bare string; the scan walks the tree and prefers the value of
     * the most answer-like key, then falls back to any short string leaf.</p>
     */
    static String extract(String body) {
        if (body == null) {
            return "";
        }
        String text = body.trim();
        if (text.isEmpty()) {
            return "";
        }
        if (!text.startsWith("{") && !text.startsWith("[")) {
            return text.length() <= 120 && !isFailure(text) ? text : "";
        }
        try {
            Object root = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
            List<String> candidates = new ArrayList<>();
            List<String> strong = new ArrayList<>();
            walk(root, candidates, strong, 0);
            if (!strong.isEmpty()) {
                return best(strong);
            }
            return best(candidates);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String best(List<String> values) {
        String best = "";
        for (String v : values) {
            String c = Norm.clean(v);
            if (c.isEmpty() || isFailure(c)) {
                continue;
            }
            if (best.isEmpty() || (c.length() < best.length() && !c.equals(best))) {
                best = c;
            }
        }
        return best;
    }

    private static void walk(Object node, List<String> weak, List<String> strong, int depth) {
        if (node == null || depth > 8) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = obj.opt(k);
                String kl = k.toLowerCase(Locale.ROOT);
                boolean isAnswerKey = kl.equals("answer") || kl.equals("answers") || kl.equals("answerkey")
                        || kl.equals("answertext") || kl.equals("答案") || kl.equals("correct")
                        || kl.equals("result") || kl.equals("value");
                if (v instanceof String) {
                    String s = (String) v;
                    if (!s.trim().isEmpty()) {
                        (isAnswerKey ? strong : weak).add(s);
                    }
                } else if (v != null) {
                    walk(v, weak, strong, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.opt(i);
                if (v instanceof String) {
                    if (!((String) v).trim().isEmpty()) {
                        weak.add((String) v);
                    }
                } else if (v != null) {
                    walk(v, weak, strong, depth + 1);
                }
            }
        }
    }

    private static boolean isFailure(String v) {
        String lower = v.toLowerCase(Locale.ROOT);
        for (String mark : FAILURE_MARKS) {
            if (lower.contains(mark)) {
                return true;
            }
        }
        return false;
    }
}
