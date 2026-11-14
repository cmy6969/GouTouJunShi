package com.goutou.junshi.answer;

import org.json.JSONArray;
import org.json.JSONObject;

import com.goutou.junshi.core.Cfg;

/**
 * Offline checks for the AI fallback: request shape, response parsing and answer extraction.
 *
 * <pre>
 *   javac -encoding UTF-8 -cp "tools/lib/json.jar;android.jar;app/libs/xposed-api-stub.jar" -d build/ai \
 *         test/AiBankTest.java app/src/main/java/io/lumen/cx/answer/{AiBank,AxeBank,Norm,Question}.java \
 *         app/src/main/java/io/lumen/cx/core/{Http,Logx,Cfg}.java
 *   java -cp "build/ai;tools/lib/json.jar;android.jar;app/libs/xposed-api-stub.jar" com.goutou.junshi.answer.AiBankTest
 * </pre>
 */
public final class AiBankTest {

    private static int failures;

    public static void main(String[] args) {
        endpoint();
        request();
        responses();
        extraction();
        refusals();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL AI CHECKS PASSED");
            return;
        }
        System.out.println(failures + " AI CHECK(S) FAILED");
        System.exit(1);
    }

    // ------------------------------------------------------------------ cases

    private static void endpoint() {
        System.out.println("AI: endpoint");
        Cfg.Snapshot plain = snapshot("https://api.deepseek.com", "", "");
        eq("https://api.deepseek.com/chat/completions", AiBank.endpoint(plain), "base + default path");

        Cfg.Snapshot slashed = snapshot("https://api.deepseek.com/", "", "");
        eq("https://api.deepseek.com/chat/completions", AiBank.endpoint(slashed), "trailing slash trimmed");

        Cfg.Snapshot full = snapshot("https://relay.local/v1/chat/completions", "", "");
        eq("https://relay.local/v1/chat/completions", AiBank.endpoint(full), "full url kept as is");

        Cfg.Snapshot custom = snapshot("https://open.bigmodel.cn/api/paas/v4", "/chat/completions", "");
        eq("https://open.bigmodel.cn/api/paas/v4/chat/completions", AiBank.endpoint(custom),
                "custom base + path");

        Cfg.Snapshot noSlash = snapshot("http://127.0.0.1:11434/v1", "chat/completions", "");
        eq("http://127.0.0.1:11434/v1/chat/completions", AiBank.endpoint(noSlash),
                "missing leading slash fixed");

        check(AiBank.isLocal(noSlash), "local endpoint detected", "127.0.0.1");
        check(!AiBank.isLocal(plain), "public endpoint not local", "api.deepseek.com");
    }

    private static void request() {
        System.out.println("AI: request body");
        Question q = choiceQuestion();
        Cfg.Snapshot cfg = snapshot(AiBank.DEFAULT_BASE, "", "sk-test");
        String body = AiBank.buildRequest(q, cfg);
        try {
            JSONObject root = new JSONObject(body);
            eq("deepseek-chat", root.optString("model"), "default model used");
            check(!root.optBoolean("stream", true), "streaming disabled", body);
            JSONArray messages = root.optJSONArray("messages");
            check(messages != null && messages.length() == 2, "system + user message", body);
            String system = messages.optJSONObject(0).optString("content");
            String user = messages.optJSONObject(1).optString("content");
            check(system.contains("只输出答案"), "system prompt constrains output", system);
            check(user.contains("单选题"), "user prompt carries type", user);
            check(user.contains("下列哪个是 Java 的访问修饰符"), "user prompt carries stem", user);
            check(user.contains("A. final") && user.contains("C. private"), "user prompt carries options", user);
        } catch (Throwable t) {
            fail("request is valid json", String.valueOf(t));
        }

        Question blank = new Question();
        blank.type = 2;
        blank.stem = "三大特性";
        blank.blanks = 3;
        String blankBody = AiBank.buildRequest(blank, cfg);
        check(blankBody.contains("空的个数"), "blank count in prompt", blankBody);
    }

    private static void responses() {
        System.out.println("AI: response shapes");
        String openai = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"C\"}}]}";
        eq("C", AiBank.parseReply(openai), "openai chat completion");

        String legacy = "{\"choices\":[{\"text\":\"\\nABC\\n\"}]}";
        eq("\nABC\n", AiBank.parseReply(legacy), "legacy completions text");

        String anthropic = "{\"content\":[{\"type\":\"text\",\"text\":\"对\"}]}";
        eq("对", AiBank.parseReply(anthropic), "anthropic content array");

        String parts = "{\"choices\":[{\"message\":{\"content\":[{\"text\":\"B\"},{\"text\":\"D\"}]}}]}";
        eq("BD", AiBank.parseReply(parts), "content parts concatenated");

        eq("C", AiBank.parseReply("C"), "plain text body");
        eq("", AiBank.parseReply("{\"error\":{\"message\":\"bad key\"}}"), "error object yields nothing");
        eq("", AiBank.parseReply(""), "empty body");
    }

    private static void extraction() {
        System.out.println("AI: answer extraction");
        Question q = choiceQuestion();
        eq("C", AiBank.toWire("C", q), "bare letter");
        eq("C", AiBank.toWire("c", q), "lower case letter");
        eq("C", AiBank.toWire("答案：C", q), "answer prefix");
        eq("C", AiBank.toWire("正确答案是 C。", q), "sentence form");
        eq("C", AiBank.toWire("```\nC\n```", q), "code fence");
        eq("D", AiBank.toWire("private 才是访问修饰符，所以选 D", q), "option text wins");
        eq("ABC", AiBank.toWire("ABC", q), "multi letters");
        eq("AB", AiBank.toWire("A、B", q), "multi with separator");

        Question multi = choiceQuestion();
        multi.type = 1;
        eq("AC", AiBank.toWire("答案：AC（A 和 C 都符合）", multi), "multi with explanation");

        Question judge = new Question();
        judge.type = 3;
        judge.options.add(new Question.Option("A", "对"));
        judge.options.add(new Question.Option("B", "错"));
        eq("A", AiBank.toWire("对", judge), "judge 对");
        eq("A", AiBank.toWire("A", judge), "judge letter");
        eq("B", AiBank.toWire("错", judge), "judge 错");
        eq("B", AiBank.toWire("B", judge), "judge letter B");

        Question blank = new Question();
        blank.type = 2;
        blank.blanks = 3;
        eq("封装|继承|多态", AiBank.toWire("封装|继承|多态", blank), "blank pipe");
        eq("封装|继承|多态", AiBank.toWire("封装、继承、多态", blank), "blank ideographic comma");

        Question explain = new Question();
        explain.type = 0;
        explain.options.add(new Question.Option("A", "final"));
        explain.options.add(new Question.Option("B", "static"));
        eq("", AiBank.toWire("我不确定这道题，可能是 A 也可能是 B", explain),
                "uncertain reply is not turned into a random letter");
    }

    private static void refusals() {
        System.out.println("AI: refusals");
        check(AiBank.isRefusal("未知"), "未知 is a refusal", "未知");
        check(AiBank.isRefusal("抱歉，我无法确定"), "apology is a refusal", "抱歉");
        check(!AiBank.isRefusal("C"), "a letter is not a refusal", "C");

        Question q = choiceQuestion();
        eq("", AiBank.toWire("抱歉，我无法确定", q), "refusal produces no answer");
        eq("", AiBank.toWire("", q), "empty reply produces no answer");
    }

    // ---------------------------------------------------------------- helpers

    private static Question choiceQuestion() {
        Question q = new Question();
        q.id = "881";
        q.type = 0;
        q.stem = "下列哪个是 Java 的访问修饰符？";
        q.options.add(new Question.Option("A", "final"));
        q.options.add(new Question.Option("B", "static"));
        q.options.add(new Question.Option("C", "private"));
        q.options.add(new Question.Option("D", "extends"));
        return q;
    }

    private static Cfg.Snapshot snapshot(String base, String path, String key) {
        try {
            JSONObject ai = new JSONObject();
            ai.put("on", true);
            ai.put("base", base);
            ai.put("path", path);
            ai.put("key", key);
            ai.put("model", "deepseek-chat");
            JSONObject answer = new JSONObject();
            answer.put("on", true);
            answer.put("ai", ai);
            JSONObject root = new JSONObject();
            root.put("answer", answer);
            return new Cfg.Snapshot(root);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void eq(String expected, String actual, String name) {
        if (expected.equals(actual)) {
            System.out.println("  ok   " + name);
        } else {
            fail(name, "expected <" + expected + "> got <" + actual + ">");
        }
    }

    private static void check(boolean ok, String name, String detail) {
        if (ok) {
            System.out.println("  ok   " + name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        failures++;
        System.out.println("  FAIL " + name + " -> " + detail);
    }
}
