package com.goutou.junshi.answer;

import org.json.JSONArray;
import org.json.JSONObject;

import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Http;
import com.goutou.junshi.core.Logx;

/**
 * Answers a question from a screenshot with an OpenAI-compatible vision model.
 *
 * <p>Used for questions that are built out of pictures (a formula image as the stem, LaTeX rendered
 * as images as options): text extraction cannot describe them, and question banks tend to return an
 * option image URL that does not match the page.  The screenshot is captured by {@link Shot}, which
 * stitches several scroll positions when the question is taller than the screen.</p>
 */
public final class VisionBank {

    private static final String DEFAULT_PATH = "/chat/completions";
    private static final String DEFAULT_PROMPT =
            "你是答题助手。图片是一道题目（可能包含公式图片和选项）。"
                    + "不要解释、不要输出推理过程。单选只输出一个大写字母，多选输出连续大写字母"
                    + "（如 ABC），判断题 A=对 B=错，填空题按顺序输出每空答案并用 | 分隔，"
                    + "简答/论述/计算题输出完整的解答文字（可分段）。"
                    + "如果图片看不清或信息不足，只输出：未知";

    private static final int MAX_TOKENS = 2048;
    private static final int RETRY_TOKENS = 4096;
    private static final int TIMEOUT = 90000;

    private VisionBank() {
    }

    public static final class Hit {
        public final String answer;
        public final String note;

        Hit(String answer, String note) {
            this.answer = answer;
            this.note = note == null ? "" : note;
        }
    }

    /**
     * True when screenshot answering is switched on and some model can look at images.
     *
     * <p>{@code vision_model} may be left empty: the main model is used then, which is enough for
     * providers whose default model is multimodal (DeepSeek V4-Flash accepts {@code text+image}).</p>
     */
    public static boolean configured(Cfg.Snapshot cfg) {
        return cfg.visionOn && !model(cfg).isEmpty();
    }

    /** Model used for screenshots: the dedicated one, or the main chat model. */
    static String model(Cfg.Snapshot cfg) {
        if (cfg.visionModel != null && !cfg.visionModel.isEmpty()) {
            return cfg.visionModel;
        }
        return cfg.aiModel == null ? "" : cfg.aiModel;
    }

    public static Hit query(String imageBase64, Question q, Cfg.Snapshot cfg) {
        if (!configured(cfg)) {
            return new Hit("", "未配置视觉模型");
        }
        if (imageBase64 == null || imageBase64.isEmpty()) {
            return new Hit("", "截图失败");
        }
        String base = cfg.visionBase == null || cfg.visionBase.isEmpty() ? cfg.aiBase : cfg.visionBase;
        String key = cfg.visionKey == null || cfg.visionKey.isEmpty() ? cfg.aiKey : cfg.visionKey;
        String path = cfg.visionPath == null || cfg.visionPath.isEmpty() ? DEFAULT_PATH : cfg.visionPath;
        if (base == null || base.isEmpty()) {
            return new Hit("", "未配置视觉接口");
        }
        String url = join(base, path);
        String resp = Http.requestJson(url, "POST", build(q, cfg, imageBase64, MAX_TOKENS), key, TIMEOUT);
        if (resp == null) {
            return new Hit("", "视觉接口无响应");
        }
        String content = AiBank.parseReply(resp);
        if (content.isEmpty() && AiBank.isTruncated(resp)) {
            // Reasoning models burn the budget on chain of thought before writing the answer.
            Logx.w("vision: truncated at " + MAX_TOKENS + ", retrying with " + RETRY_TOKENS);
            String second = Http.requestJson(url, "POST",
                    build(q, cfg, imageBase64, RETRY_TOKENS), key, TIMEOUT);
            if (second != null) {
                resp = second;
                content = AiBank.parseReply(resp);
            }
        }
        if (content.isEmpty()) {
            return new Hit("", AiBank.isTruncated(resp) ? "视觉模型输出被截断" : "视觉模型返回为空");
        }
        String answer = AiBank.toWire(content, q);
        if (answer.isEmpty()) {
            return new Hit("", "视觉回答无法匹配选项：" + shortText(content));
        }
        return new Hit(answer, "");
    }

    /** Vision rules, narrowed to the question type (essays must not be answered with letters). */
    static String promptFor(Cfg.Snapshot cfg, Question q) {
        if (cfg.visionPrompt != null && !cfg.visionPrompt.isEmpty()) {
            return cfg.visionPrompt;
        }
        String base = "你是答题助手。图片是一道题目（可能包含公式图片和选项）。"
                + "不要解释、不要输出推理过程。";
        if (q != null && Norm.isEssay(q.type)) {
            return base + "本题是简答/论述/计算题：输出完整的解答文字，可分多段，"
                    + "不要只给字母，也不要用 | 分隔。如果图片看不清或信息不足，只输出：未知";
        }
        return base + "单选只输出一个大写字母，多选输出连续大写字母（如 ABC），判断题 A=对 B=错，"
                + "填空题按顺序输出每空答案并用 | 分隔。如果图片看不清或信息不足，只输出：未知";
    }

    static String build(Question q, Cfg.Snapshot cfg, String imageBase64) {
        return build(q, cfg, imageBase64, MAX_TOKENS);
    }

    static String build(Question q, Cfg.Snapshot cfg, String imageBase64, int maxTokens) {
        JSONObject root = new JSONObject();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", promptFor(cfg, q));

            JSONObject user = new JSONObject();
            user.put("role", "user");
            JSONArray content = new JSONArray();

            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", ask(q));
            content.put(text);

            JSONObject image = new JSONObject();
            image.put("type", "image_url");
            JSONObject url = new JSONObject();
            url.put("url", "data:image/jpeg;base64," + imageBase64);
            image.put("image_url", url);
            content.put(image);

            user.put("content", content);

            JSONArray messages = new JSONArray();
            messages.put(sys);
            messages.put(user);

            root.put("messages", messages);
            root.put("model", model(cfg));
            root.put("temperature", 0.1);
            root.put("max_tokens", MAX_TOKENS);
            root.put("stream", false);
            if (cfg.aiEffort != null && !cfg.aiEffort.isEmpty()) {
                root.put("reasoning_effort", cfg.aiEffort);
            }
        } catch (Throwable t) {
            Logx.e("vision: build request", t);
        }
        return root.toString();
    }

    private static String ask(Question q) {
        String kind;
        switch (q.type) {
            case 0:
                kind = "单选题";
                break;
            case 1:
                kind = "多选题";
                break;
            case 2:
                kind = "填空题";
                break;
            case 3:
                kind = "判断题";
                break;
            default:
                kind = "题目";
        }
        StringBuilder sb = new StringBuilder("题型：").append(kind).append('。');
        if (q.blanks > 0) {
            sb.append("共 ").append(q.blanks).append(" 个空。");
        }
        sb.append("请阅读图片作答：");
        return sb.toString();
    }

    private static String join(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    private static String shortText(String s) {
        String v = s == null ? "" : s.replace('\n', ' ').trim();
        return v.length() <= 40 ? v : v.substring(0, 40) + "…";
    }
}
