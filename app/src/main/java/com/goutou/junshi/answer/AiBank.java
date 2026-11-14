package com.goutou.junshi.answer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Http;
import com.goutou.junshi.core.Logx;

/**
 * AI fallback answering.
 *
 * <p>Used only when every configured question bank missed.  Speaks the OpenAI compatible
 * {@code chat/completions} protocol, so it works with DeepSeek, OpenAI, 通义/智谱/Kimi relays and a
 * local Ollama or LM Studio (just point the base URL at it).</p>
 *
 * <p>The prompt asks for the answer only, in the same wire format the page needs (letters for
 * choice, {@code 对/错} shortened to A/B, {@code |} separated blanks).  The reply is parsed
 * defensively: code fences, "答案是…" prefixes, explanations and refusal sentences are handled
 * before anything is written into the page.</p>
 */
public final class AiBank {

    public static final String DEFAULT_BASE = "https://api.deepseek.com";
    public static final String DEFAULT_PATH = "/chat/completions";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private static final int DEFAULT_TIMEOUT = 60000;
    /** Reasoning models ("thinking" models) spend the budget on chain of thought; 2k is the floor. */
    private static final int DEFAULT_MAX_TOKENS = 2048;
    /** One automatic retry with a bigger budget when the provider stops on the token limit. */
    private static final int RETRY_MAX_TOKENS = 4096;
    /** The retry generates far more text, so it needs a longer read timeout as well. */
    private static final int RETRY_MIN_TIMEOUT = 90000;
    private static final long MIN_INTERVAL_MS = 1200L;
    private static volatile long lastCallAt;

    private AiBank() {
    }

    public static final class Hit {
        public final String answer;
        public final String note;

        Hit(String answer, String note) {
            this.answer = answer == null ? "" : answer;
            this.note = note == null ? "" : note;
        }
    }

    // ------------------------------------------------------------------ query

    public static Hit query(Question q, Cfg.Snapshot cfg) {
        if (!cfg.aiOn || q.stem.isEmpty()) {
            return null;
        }
        if (cfg.aiKey.isEmpty() && !isLocal(cfg)) {
            return new Hit("", "未填写 AI key");
        }
        long now = System.currentTimeMillis();
        if (now - lastCallAt < MIN_INTERVAL_MS) {
            try {
                Thread.sleep(MIN_INTERVAL_MS - (now - lastCallAt));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallAt = System.currentTimeMillis();

        String endpoint = endpoint(cfg);
        // A reasoning model needs tens of seconds even for a short answer; never allow a shorter
        // read timeout than this, otherwise the first call dies before we can even see "truncated".
        int timeout = Math.max(cfg.aiTimeout > 0 ? cfg.aiTimeout : DEFAULT_TIMEOUT, RETRY_MIN_TIMEOUT);
        int budget = cfg.aiMaxTokens > 0 ? cfg.aiMaxTokens : DEFAULT_MAX_TOKENS;
        String resp = Http.requestJson(endpoint, "POST", buildRequest(q, cfg, budget), cfg.aiKey, timeout);
        if (resp == null) {
            return new Hit("", "AI 接口无响应");
        }
        String content = parseReply(resp);
        if (content.isEmpty() && isTruncated(resp)) {
            // Reasoning models can burn the whole budget on chain of thought: retry once with more
            // room and a longer timeout (generating ~2.5k tokens easily passes 20 s).
            int retry = Math.max(budget * 2, RETRY_MAX_TOKENS);
            int retryTimeout = Math.max(timeout, RETRY_MIN_TIMEOUT);
            Logx.w("ai: truncated at " + budget + " tokens, retrying with " + retry
                    + " (timeout " + retryTimeout + "ms)");
            String second = Http.requestJson(endpoint, "POST",
                    buildRequest(q, cfg, retry), cfg.aiKey, retryTimeout);
            if (second == null) {
                return new Hit("", "AI 超时（推理模型耗时较长，可换 deepseek-chat 或调大 timeout_ms）");
            }
            resp = second;
            content = parseReply(resp);
            if (content.isEmpty()) {
                return new Hit("", isTruncated(resp)
                        ? ("AI 输出仍被截断（已按 " + retry + " tokens 重试）")
                        : "AI 返回为空");
            }
        }
        if (content.isEmpty()) {
            if (isTruncated(resp)) {
                return new Hit("", "AI 输出被截断（推理模型会先消耗 token，可调大 max_tokens）");
            }
            return new Hit("", "AI 返回为空");
        }
        String wire = toWire(content, q);
        if (wire.isEmpty()) {
            return new Hit("", "AI 未能给出可用答案");
        }
        Logx.i("ai: answer for " + q.id + " = " + wire);
        return new Hit(wire, "");
    }

    static boolean isLocal(Cfg.Snapshot cfg) {
        String base = cfg.aiBase == null ? "" : cfg.aiBase.toLowerCase(java.util.Locale.ROOT);
        return base.contains("127.0.0.1") || base.contains("localhost") || base.contains("0.0.0.0")
                || base.contains("192.168.") || base.contains("10.0.") || base.contains(":11434");
    }

    static String endpoint(Cfg.Snapshot cfg) {
        String base = cfg.aiBase == null || cfg.aiBase.trim().isEmpty() ? DEFAULT_BASE : cfg.aiBase.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = cfg.aiPath == null || cfg.aiPath.trim().isEmpty() ? DEFAULT_PATH : cfg.aiPath.trim();
        if (base.endsWith("/chat/completions") || base.endsWith("/completions")) {
            return base;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    // ------------------------------------------------------------ request

    static String buildRequest(Question q, Cfg.Snapshot cfg) {
        return buildRequest(q, cfg, cfg.aiMaxTokens > 0 ? cfg.aiMaxTokens : DEFAULT_MAX_TOKENS);
    }

    static String buildRequest(Question q, Cfg.Snapshot cfg, int maxTokens) {
        JSONObject root = new JSONObject();
        try {
            JSONArray messages = new JSONArray();
            messages.put(message("system", systemPrompt(cfg, q)));
            messages.put(message("user", userPrompt(q)));
            root.put("messages", messages);
            root.put("model", cfg.aiModel == null || cfg.aiModel.isEmpty() ? DEFAULT_MODEL : cfg.aiModel);
            root.put("temperature", 0.2);
            root.put("max_tokens", maxTokens);
            root.put("stream", false);
            if (cfg.aiEffort != null && !cfg.aiEffort.isEmpty()) {
                // Reasoning-capable providers accept this; others ignore unknown fields.
                root.put("reasoning_effort", cfg.aiEffort);
            }
        } catch (Throwable ignored) {
        }
        return root.toString();
    }

    private static JSONObject message(String role, String content) {
        JSONObject m = new JSONObject();
        try {
            m.put("role", role);
            m.put("content", content);
        } catch (Throwable ignored) {
        }
        return m;
    }

    static String systemPrompt(Cfg.Snapshot cfg) {
        return systemPrompt(cfg, null);
    }

    /**
     * Rules for the model, kept type specific: an essay must not be answered with letters and a
     * choice question must not be answered with a paragraph.
     */
    static String systemPrompt(Cfg.Snapshot cfg, Question q) {
        if (cfg.aiSystem != null && !cfg.aiSystem.trim().isEmpty()) {
            return cfg.aiSystem.trim();
        }
        String base = "你是一个只输出答案的答题助手。不要解释、不要 reasoning、不要 markdown。";
        if (q != null && Norm.isEssay(q.type)) {
            return base + "本题是简答/论述/计算题：直接输出完整的解答文字，可分多段，"
                    + "不要只给字母，也不要用 | 分隔。如果确实无法作答，只输出：未知";
        }
        String rules = "单选只输出一个大写字母；多选输出连续的大写字母（例如 ABC）；"
                + "判断题输出 A 表示对、B 表示错；填空题按空的顺序输出，每空一个答案，用 | 分隔。"
                + "如果确实无法确定，只输出：未知";
        return base + rules;
    }

    static String userPrompt(Question q) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("题型：").append(typeName(q.type)).append('\n');
        sb.append("题目：").append(q.stem).append('\n');
        if (!q.options.isEmpty()) {
            sb.append("选项：\n");
            for (Question.Option o : q.options) {
                sb.append(o.key).append(". ").append(o.text).append('\n');
            }
        }
        if (AxeBank.isBlankType(q.type) && q.blanks > 0) {
            sb.append("空的个数：").append(q.blanks).append('\n');
        }
        if (Norm.isEssay(q.type)) {
            sb.append("请给出完整的解答文字（可分段），不要只输出选项字母。");
        } else {
            sb.append("请直接输出答案。");
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
            default:
                return "问答题";
        }
    }

    // ------------------------------------------------------------- response

    /** Extracts the assistant text from an OpenAI compatible (or Anthropic) payload. */
    static String parseReply(String body) {
        if (body == null) {
            return "";
        }
        String text = body.trim();
        if (text.isEmpty()) {
            return "";
        }
        if (!text.startsWith("{")) {
            return text;
        }
        try {
            JSONObject root = new JSONObject(text);
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject first = choices.optJSONObject(0);
                if (first != null) {
                    JSONObject message = first.optJSONObject("message");
                    if (message != null) {
                        Object content = message.opt("content");
                        if (content instanceof String && !((String) content).isEmpty()) {
                            return (String) content;
                        }
                        if (content instanceof JSONArray) {
                            String joined = joinParts((JSONArray) content);
                            if (!joined.isEmpty()) {
                                return joined;
                            }
                        }
                    }
                    String legacy = first.optString("text", "");
                    if (!legacy.isEmpty()) {
                        return legacy;
                    }
                }
            }
            // Anthropic style
            JSONArray content = root.optJSONArray("content");
            if (content != null) {
                String joined = joinParts(content);
                if (!joined.isEmpty()) {
                    return joined;
                }
            }
            String direct = root.optString("content", "");
            if (!direct.isEmpty()) {
                return direct;
            }
            String err = root.optString("error", "");
            if (!err.isEmpty()) {
                return "";
            }
        } catch (Throwable t) {
            Logx.e("ai parse", t);
        }
        return "";
    }

    private static String joinParts(JSONArray parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            Object item = parts.opt(i);
            if (item instanceof JSONObject) {
                sb.append(((JSONObject) item).optString("text", ""));
            } else if (item instanceof String) {
                sb.append((String) item);
            }
        }
        return sb.toString();
    }

    /** True when the provider stopped because the token budget ran out. */
    static boolean isTruncated(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("\"finish_reason\":\"length\"")
                || lower.contains("\"finish_reason\": \"length\"");
    }

    // ---------------------------------------------------------- answer mapping

    /** Turns whatever the model replied into the page wire format. */
    static String toWire(String reply, Question q) {
        if (Norm.isEssay(q.type)) {
            String text = stripPrefix(stripFencesLines(reply));
            if (text.isEmpty() || isRefusal(text)) {
                return "";
            }
            return Norm.essay(text);
        }
        String fenced = stripFences(reply);
        String cleaned = stripPrefix(fenced);
        if (cleaned.isEmpty() || isRefusal(cleaned)) {
            return "";
        }
        if (AxeBank.isBlankType(q.type)) {
            return blanks(cleaned, q);
        }
        if (q.type == 3) {
            return judge(cleaned, q);
        }
        return choice(fenced, cleaned, q);
    }

    /** Fence/think stripping that keeps line breaks (essay answers are multi-line). */
    static String stripFencesLines(String reply) {
        if (reply == null) {
            return "";
        }
        String s = reply.replace("\r\n", "\n").replace('\r', '\n').trim();
        s = s.replaceAll("(?is)<(think|reasoning)>.*?</\\1>", " ");
        s = s.replaceAll("(?m)^\\s*```[a-zA-Z]*\\s*$", "");
        s = s.replace("```", "");
        return s.trim();
    }

    static String stripFences(String reply) {        if (reply == null) {
            return "";
        }
        String s = reply.trim();
        s = s.replaceAll("(?is)```[a-z]*", " ").replace("```", " ");
        s = s.replaceAll("(?is)<(think|reasoning)>.*?</\\1>", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    static String stripPrefix(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?s)^\\s*(?:正确答案|答案|answer|答)\\s*[:：]\\s*", "").trim();
    }

    static String clean(String reply) {
        return stripPrefix(stripFences(reply));
    }

    static boolean isRefusal(String text) {
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.equals("未知") || t.equals("unknown") || t.equals("无") || t.equals("null")) {
            return true;
        }
        String[] marks = {"无法确定", "无法回答", "不能确定", "抱歉", "sorry", "cannot", "无法判断", "无法提供"};
        for (String m : marks) {
            if (t.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private static String blanks(String text, Question q) {
        List<String> parts = new ArrayList<>();
        for (String seg : text.split("\\s*[|｜]\\s*")) {
            String v = seg.trim();
            if (!v.isEmpty()) {
                parts.add(v);
            }
        }
        if (parts.size() <= 1) {
            parts.clear();
            for (String seg : text.split("\\s*(?:、|;|；|,|，|/)\\s*")) {
                String v = seg.trim();
                if (!v.isEmpty()) {
                    parts.add(v);
                }
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String judge(String text, Question q) {
        if (text.matches("^[A-Ha-h]$")) {
            // The model answered with the option letter itself.
            return text.toUpperCase(java.util.Locale.ROOT);
        }
        String letters = AxeBank.letterOf(text, q);
        if (letters != null) {
            String wire = Norm.forType(3, letters);
            if (!wire.isEmpty()) {
                return wire;
            }
        }
        String t = text.toLowerCase(java.util.Locale.ROOT);
        Boolean yes = Norm.truth(t);
        if (yes == null) {
            if (t.contains("对") || t.contains("是") || t.contains("正确") || t.contains("true")) {
                yes = Boolean.TRUE;
            } else if (t.contains("错") || t.contains("否") || t.contains("不正确") || t.contains("false")) {
                yes = Boolean.FALSE;
            }
        }
        return yes == null ? "" : (yes ? "A" : "B");
    }

    private static final java.util.regex.Pattern ANSWER_LINE = java.util.regex.Pattern.compile(
            "(?:正确答案|答案|应选|选)\\s*(?:是|为)?\\s*[:：]?\\s*([A-H](?:\\s*[,，、]?\\s*[A-H]){0,7})");

    private static final java.util.regex.Pattern SINGLE_LETTER = java.util.regex.Pattern.compile(
            "(?:^|[\\s（(【\\[])([A-H])(?:$|[\\s）)】\\].,，。])");

    private static String choice(String fenced, String cleaned, Question q) {
        // 1) explicit answer segment: "答案：C" / "选 C" / "正确答案是 AB"
        java.util.regex.Matcher m = ANSWER_LINE.matcher(fenced);
        if (m.find()) {
            String letters = Norm.letters(m.group(1));
            if (!letters.isEmpty()) {
                return letters;
            }
        }
        // 2) bare letters, e.g. "C" / "ABC" / "A,C"
        if (cleaned.length() <= 16) {
            String letters = Norm.letters(cleaned);
            if (!letters.isEmpty()
                    && cleaned.replaceAll("[^A-Za-z]", "").length()
                    == cleaned.replaceAll("[\\s,，、;；/|#&+]", "").length()) {
                return letters.length() > 8 ? "" : letters;
            }
        }
        // 3) the model quoted an option instead of a letter
        String byText = AxeBank.letterOf(cleaned, q);
        if (byText != null) {
            return byText;
        }
        // 4) a single isolated letter… but never guess when the model hedged
        if (uncertain(cleaned)) {
            return "";
        }
        java.util.regex.Matcher single = SINGLE_LETTER.matcher(cleaned);
        if (single.find()) {
            return single.group(1);
        }
        return "";
    }

    static boolean uncertain(String text) {
        if (text == null) {
            return false;
        }
        String[] marks = {"不确定", "可能是", "也可能是", "也许", "大概", "猜测", "存疑", "无法确定"};
        for (String m : marks) {
            if (text.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** Never used at runtime; keeps the parse of a whole choice list testable. */
    static String debugExtract(String reply, Question q) {
        return toWire(reply, q);
    }
}
