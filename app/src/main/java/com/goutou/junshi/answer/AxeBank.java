package com.goutou.junshi.answer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;

import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Http;
import com.goutou.junshi.core.Logx;

/**
 * AXE 题库 3.0 client.
 *
 * <p>Wire contract (from the public API docs):</p>
 * <ul>
 *   <li>{@code POST /api/query} — form fields {@code token, tm, type, options, answernum,
 *       questionnum, v=2, ai, coursename}.  With {@code v=2} the payload is
 *       {@code {code, data:{lefts, questions:[{tm, answer, percent}]}, msg}}; without it (or on the
 *       2.0 endpoint) {@code data} is the answer string and {@code left} holds the counter.</li>
 *   <li>Choice answers come back as the option <em>text</em> joined with {@code #}; fill-in answers
 *       use {@code #!#} between blanks.  Both are translated into the page wire format here:
 *       letters ({@code AB}) for choice, {@code a|b} for blanks.</li>
 *   <li>{@code POST /api/checkLeft} — {@code {code, data:<remaining>}}; {@code -1} for an unknown
 *       token.</li>
 *   <li>{@code PUT /api/register} — JSON {@code {token, md5(token)}}; the token then has to be
 *       activated by logging into the question bank web site once.</li>
 * </ul>
 *
 * <p>All requests are made from a background thread (see {@link Engine}) and nothing is sent unless
 * the user configured a token.</p>
 */
public final class AxeBank {

    public static final String DEFAULT_BASE = "https://tk.wanjuantiku.com";

    private static final int QUERY_TIMEOUT = 6500;
    private static final int LIGHT_TIMEOUT = 5000;
    private static final double TEXT_MATCH_THRESHOLD = 0.78;

    /** Left-over counter of the most recent query, {@code -2} when unknown. */
    private static volatile int lastLefts = -2;

    private AxeBank() {
    }

    public static int lastLefts() {
        return lastLefts;
    }

    /** Result of one question lookup. */
    public static final class Hit {
        public final String answer;
        public final int lefts;
        public final String note;

        Hit(String answer, int lefts, String note) {
            this.answer = answer == null ? "" : answer;
            this.lefts = lefts;
            this.note = note == null ? "" : note;
        }
    }

    /** Result of a counter probe, used by the settings screen. */
    public static final class Status {
        public final int lefts;
        public final String message;

        Status(int lefts, String message) {
            this.lefts = lefts;
            this.message = message;
        }
    }

    public static String base(Cfg.Snapshot cfg) {
        String b = cfg.axeBase == null ? "" : cfg.axeBase.trim();
        if (b.isEmpty()) {
            b = DEFAULT_BASE;
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    // ------------------------------------------------------------------ query

    public static Hit query(Question q, Cfg.Snapshot cfg) {
        if (cfg.axeToken == null || cfg.axeToken.isEmpty() || q.stem.isEmpty()) {
            return null;
        }
        int type = mapType(q.type);
        if (type < 0) {
            return null; // 阅读理解 / 组合题之类的复合题型，接口没有对应类型
        }
        String resp = Http.postForm(base(cfg) + "/api/query",
                buildBody(q, cfg.axeToken, cfg.axeAi, cfg.axeCourse), QUERY_TIMEOUT);
        if (resp == null) {
            return new Hit("", -2, "AXE 接口无响应");
        }
        return parseQuery(resp, q);
    }

    static Hit parseQuery(String resp, Question q) {
        try {
            JSONObject root = new JSONObject(resp.trim());
            int code = root.optInt("code", 0);
            String msg = strip(root.optString("msg", ""));
            int lefts = root.optInt("left", -2);
            if (lefts == -2) {
                lefts = root.optInt("lefts", -2);
            }
            if (code != 1) {
                lastLefts = lefts;
                return new Hit("", lefts, msg.isEmpty() ? "AXE 查询失败" : msg);
            }
            Object data = root.opt("data");
            String raw = null;
            if (data instanceof JSONObject) {
                JSONObject d = (JSONObject) data;
                lefts = d.optInt("lefts", lefts);
                JSONArray list = d.optJSONArray("questions");
                if (list != null) {
                    int bestPercent = -1;
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null) {
                            continue;
                        }
                        String answer = o.optString("answer", "");
                        int percent = o.optInt("percent", 0);
                        if (!answer.trim().isEmpty() && percent >= bestPercent) {
                            bestPercent = percent;
                            raw = answer;
                        }
                    }
                }
            } else if (data instanceof String) {
                raw = (String) data;
            } else if (data != null && !JSONObject.NULL.equals(data)) {
                raw = String.valueOf(data);
            }
            lastLefts = lefts;
            if (raw == null || raw.trim().isEmpty() || "null".equals(raw)) {
                return new Hit("", lefts, msg.isEmpty() ? "AXE 未收录该题" : msg);
            }
            String wire = toWire(raw, q);
            if (wire.isEmpty()) {
                return new Hit("", lefts, "AXE 答案无法匹配到选项");
            }
            return new Hit(wire, lefts, msg);
        } catch (Throwable t) {
            Logx.e("axe parse", t);
            return new Hit("", -2, "AXE 返回无法解析");
        }
    }

    /** Builds the {@code /api/query} form body (pure, so it can be unit tested). */
    static String buildBody(Question q, String token, boolean ai, String course) {
        StringBuilder body = new StringBuilder(320);
        body.append("token=").append(Http.enc(token));
        body.append("&tm=").append(Http.enc(q.stem));
        body.append("&type=").append(mapType(q.type));
        body.append("&questionnum=1");
        body.append("&v=2");
        if (hasUsableOptions(q)) {
            body.append("&options=").append(Http.enc(optionsJson(q)));
        }
        if (isBlankType(q.type) && q.blanks > 0) {
            body.append("&answernum=").append(q.blanks);
        }
        if (ai) {
            body.append("&ai=1");
        }
        if (course != null && !course.isEmpty()) {
            body.append("&coursename=").append(Http.enc(course));
        }
        return body.toString();
    }

    private static String optionsJson(Question q) {
        JSONArray arr = new JSONArray();
        for (Question.Option o : q.options) {
            arr.put(o.text);
        }
        return arr.toString();
    }

    /**
     * True when the options carry real text.
     *
     * <p>Physics and maths papers often render every option as an image; sending
     * {@code ["[图片]","[图片]"]} would only confuse the matching, so the request falls back to a
     * stem-only query in that case (the bank answers with the correct option's image URL).</p>
     */
    static boolean hasUsableOptions(Question q) {
        if (q.options.size() < 2) {
            return false;
        }
        int usable = 0;
        for (Question.Option o : q.options) {
            String t = o.text == null ? "" : o.text.trim();
            if (!t.isEmpty() && !"[图片]".equals(t)) {
                usable++;
            }
        }
        return usable >= 2;
    }

    /** Lumen question type -> AXE {@code type}. {@code -1} = not supported by the API. */
    static int mapType(int lumenType) {
        switch (lumenType) {
            case 0:
                return 0; // 单选
            case 1:
                return 1; // 多选
            case 2:
                return 2; // 填空
            case 3:
                return 3; // 判断
            case 4:
            case 5:
            case 6:
            case 14:
                return 2; // 简答/名词解释/完型填空 -> 按填空查询
            default:
                return -1; // 15 阅读理解、20 组合题等
        }
    }

    static boolean isBlankType(int lumenType) {
        return lumenType == 2 || lumenType == 4 || lumenType == 5 || lumenType == 6 || lumenType == 14;
    }

    // ------------------------------------------------------- answer mapping

    /**
     * Converts an AXE answer into the wire format the page expects.
     *
     * <p>The API returns option <em>text</em> for choice questions, so each segment is matched back
     * to an option letter (exact, then containment, then similarity).  Segments that already are
     * single letters are used as-is.</p>
     */
    static String toWire(String raw, Question q) {
        String a = raw == null ? "" : raw.trim();
        if (a.isEmpty()) {
            return "";
        }
        if (Norm.isEssay(q.type)) {
            // Essay answers are free text: keep the whole thing (multi-line, no letter mapping).
            String text = a.contains("#!#") ? a.replace("#!#", "\n") : a;
            if (text.contains("<")) {
                text = strip(text);
            }
            return Norm.essay(text);
        }
        if (isBlankType(q.type)) {
            String[] parts = a.contains("#!#") ? a.split("#!#", -1) : new String[]{a};
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                String v = p.trim();
                if (v.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(v);
            }
            return sb.toString();
        }
        String[] parts = a.split("#");
        if (q.type == 3) {
            String first = parts.length > 0 ? parts[0].trim() : a;
            String letter = letterOf(first, q);
            if (letter != null) {
                String wire = Norm.forType(3, letter);
                if (!wire.isEmpty()) {
                    return wire;
                }
            }
            return Norm.forType(3, first);
        }
        StringBuilder letters = new StringBuilder();
        for (String p : parts) {
            String seg = p.trim();
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.matches("^[A-Ha-h]$")) {
                letters.append(seg.toUpperCase());
                continue;
            }
            String letter = letterOf(seg, q);
            if (letter != null) {
                letters.append(letter);
            }
        }
        return dedupeSorted(letters.toString());
    }

    private static String dedupeSorted(String letters) {
        java.util.TreeSet<Character> set = new java.util.TreeSet<>();
        for (int i = 0; i < letters.length(); i++) {
            char c = Character.toUpperCase(letters.charAt(i));
            if (c >= 'A' && c <= 'H') {
                set.add(c);
            }
        }
        StringBuilder sb = new StringBuilder(set.size());
        for (Character c : set) {
            sb.append(c);
        }
        return sb.toString();
    }

    /** Maps one answer segment back to an option key of this question. */
    static String letterOf(String text, Question q) {
        if (text == null || text.isEmpty() || q.options.isEmpty()) {
            return null;
        }
        String target = key(text);
        if (!target.isEmpty()) {
            for (Question.Option o : q.options) {
                if (key(o.text).equals(target)) {
                    return o.key;
                }
            }
            for (Question.Option o : q.options) {
                String k = key(o.text);
                if (!k.isEmpty() && (k.contains(target) || target.contains(k))) {
                    return o.key;
                }
            }
        }
        // Image based papers: the bank answers with the URL of the correct option image, so match
        // the file name (or its hash prefix) against the option images on the page.
        String file = imageName(text);
        if (file != null) {
            for (Question.Option o : q.options) {
                String of = imageName(o.src);
                if (of != null && sameImage(of, file)) {
                    return o.key;
                }
            }
        }
        String bestKey = null;
        double best = TEXT_MATCH_THRESHOLD;
        for (Question.Option o : q.options) {
            double score = Norm.similar(o.text, text);
            if (score > best) {
                best = score;
                bestKey = o.key;
            }
        }
        return bestKey;
    }

    /** Last {@code name.ext} found in a string, or {@code null}. */
    static String imageName(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Za-z0-9_%\\-]{6,}\\.[A-Za-z]{2,5})")
                .matcher(s);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    /** Same image: identical name, or the same leading hash from different CDN hosts. */
    static boolean sameImage(String a, String b) {
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        int dotA = a.lastIndexOf('.');
        int dotB = b.lastIndexOf('.');
        if (dotA <= 0 || dotB <= 0) {
            return false;
        }
        String ha = a.substring(0, dotA);
        String hb = b.substring(0, dotB);
        int n = Math.min(10, Math.min(ha.length(), hb.length()));
        return n >= 6 && ha.regionMatches(true, 0, hb, 0, n);
    }

    /** Normalises option text for comparison: letters, digits and CJK only, lower case. */
    static String key(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- utility

    public static Status check(String rawBase, String token) {
        String b = rawBase == null || rawBase.trim().isEmpty() ? DEFAULT_BASE : rawBase.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (token == null || token.trim().isEmpty()) {
            return new Status(-2, "请先填写 token");
        }
        String resp = Http.get(b + "/api/checkLeft?token=" + Http.enc(token.trim()), LIGHT_TIMEOUT);
        if (resp == null) {
            return new Status(-2, "接口无响应");
        }
        try {
            JSONObject root = new JSONObject(resp.trim());
            int code = root.optInt("code", 0);
            Object data = root.opt("data");
            int lefts = -2;
            if (data instanceof Number) {
                lefts = ((Number) data).intValue();
            } else if (data instanceof String) {
                try {
                    lefts = Integer.parseInt(((String) data).trim());
                } catch (Throwable ignored) {
                }
            }
            String msg = strip(root.optString("msg", ""));
            if (code != 1) {
                return new Status(lefts, msg.isEmpty() ? "查询失败" : msg);
            }
            if (lefts == -1) {
                return new Status(-1, "token 不存在，请先注册");
            }
            if (lefts == 0) {
                return new Status(0, "Token 未激活或次数已用完，请到官网登录/充值");
            }
            return new Status(lefts, msg.isEmpty() ? ("剩余 " + lefts + " 次") : msg);
        } catch (Throwable t) {
            return new Status(-2, "返回无法解析");
        }
    }

    /** Registers a temporary token; the user still has to log into the site once to activate it. */
    public static String register(String rawBase, String token) {
        String b = rawBase == null || rawBase.trim().isEmpty() ? DEFAULT_BASE : rawBase.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String t = token == null ? "" : token.trim();
        if (!t.matches("^[0-9a-z]{32}$")) {
            return "token 必须是 32 位小写字母/数字";
        }
        JSONObject body = new JSONObject();
        try {
            body.put("token", t);
            body.put("md5", md5(t));
        } catch (Throwable ignored) {
        }
        String resp = Http.requestJson(b + "/api/register", "PUT", body.toString(), LIGHT_TIMEOUT);
        if (resp == null) {
            return "接口无响应";
        }
        try {
            JSONObject root = new JSONObject(resp.trim());
            String msg = strip(root.optString("msg", ""));
            return root.optInt("code", 0) == 1
                    ? ("注册成功：" + msg)
                    : ("注册失败：" + (msg.isEmpty() ? "未知原因" : msg));
        } catch (Throwable t2) {
            return "返回无法解析";
        }
    }

    static String md5(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** The API embeds HTML links in {@code msg}; keep only the readable text. */
    static String strip(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        return html.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Never used at runtime; kept so the pairing of describe() with Hit stays documented. */
    static String describeShort(Hit hit) {
        if (hit == null) {
            return "未配置 AXE";
        }
        if (!hit.answer.isEmpty()) {
            return hit.lefts >= 0 ? (hit.answer + "（剩 " + hit.lefts + " 次）") : hit.answer;
        }
        return hit.note.isEmpty() ? "未命中" : hit.note;
    }
}
