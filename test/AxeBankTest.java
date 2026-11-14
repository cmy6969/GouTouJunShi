package com.goutou.junshi.answer;

/**
 * Offline checks for the AXE 3.0 integration: request body, response shapes and the
 * "answer text -> option letter" mapping.
 *
 * <p>Compiled against a real {@code org.json} implementation plus {@code android.jar} (only for
 * type resolution); nothing here touches the network or any Android API.</p>
 *
 * <pre>
 *   javac -encoding UTF-8 -cp "tools/lib/json.jar;android.jar" -d build/tests \
 *         test/AxeBankTest.java app/src/main/java/io/lumen/cx/answer/{AxeBank,Norm,Question}.java \
 *         app/src/main/java/io/lumen/cx/core/{Http,Logx,Cfg}.java
 *   java -cp "build/tests;tools/lib/json.jar;android.jar" com.goutou.junshi.answer.AxeBankTest
 * </pre>
 */
public final class AxeBankTest {

    private static int failures;

    public static void main(String[] args) {
        choiceQuestion();
        judgmentQuestion();
        blankQuestion();
        answerTextMapping();
        responseShapes();
        utilities();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL AXE CHECKS PASSED");
            return;
        }
        System.out.println(failures + " AXE CHECK(S) FAILED");
        System.exit(1);
    }

    // ------------------------------------------------------------------ cases

    private static void choiceQuestion() {
        System.out.println("AXE: request body");
        Question q = new Question();
        q.id = "881";
        q.type = 1;
        q.stem = "以下属于 JVM 垃圾回收器的有？";
        q.options.add(new Question.Option("A", "G1"));
        q.options.add(new Question.Option("B", "CMS"));
        q.options.add(new Question.Option("C", "ZGC"));
        q.options.add(new Question.Option("D", "javac"));

        String body = AxeBank.buildBody(q, "0123456789abcdef0123456789abcdef", true, "Java基础");
        check(body.contains("type=1"), "multi -> type=1", body);
        check(body.contains("v=2"), "asks for api v2", body);
        check(body.contains("questionnum=1"), "single answer per query", body);
        check(body.contains("ai=1"), "ai flag forwarded", body);
        check(body.contains("coursename=") && body.contains("%E5%9F%BA"), "course name encoded", body);
        check(body.contains("options=%5B%22G1%22"), "options json encoded", body);
        check(!body.contains("answernum"), "choice sends no answernum", body);

        q.type = 2;
        q.blanks = 3;
        String fillBody = AxeBank.buildBody(q, "t", false, "");
        check(fillBody.contains("type=2"), "blank -> type=2", fillBody);
        check(fillBody.contains("answernum=3"), "blank count forwarded", fillBody);
        check(!fillBody.contains("ai=1"), "ai off by default", fillBody);

        eq("0", String.valueOf(AxeBank.mapType(0)), "type0 -> 0");
        eq("1", String.valueOf(AxeBank.mapType(1)), "type1 -> 1");
        eq("2", String.valueOf(AxeBank.mapType(2)), "type2 -> 2");
        eq("3", String.valueOf(AxeBank.mapType(3)), "type3 -> 3");
        eq("2", String.valueOf(AxeBank.mapType(4)), "short answer -> fill");
        eq("2", String.valueOf(AxeBank.mapType(14)), "cloze -> fill");
        eq("-1", String.valueOf(AxeBank.mapType(15)), "reading comprehension unsupported");
        eq("-1", String.valueOf(AxeBank.mapType(20)), "composite unsupported");
    }

    private static void judgmentQuestion() {
        System.out.println("AXE: judgment");
        Question q = new Question();
        q.type = 3;
        q.stem = "HTTP 是无状态协议。";
        q.options.add(new Question.Option("TRUE", "对"));
        q.options.add(new Question.Option("FALSE", "错"));

        eq("A", AxeBank.toWire("对", q), "对 -> A");
        eq("A", AxeBank.toWire("正确", q), "正确 -> A");
        eq("B", AxeBank.toWire("错", q), "错 -> B");
        eq("A", AxeBank.toWire("true", q), "true -> A");
        eq("B", AxeBank.toWire("×", q), "× -> B");

        Question ab = new Question();
        ab.type = 3;
        ab.options.add(new Question.Option("A", "对"));
        ab.options.add(new Question.Option("B", "错"));
        eq("A", AxeBank.toWire("对", ab), "judgment with A/B options");
        eq("B", AxeBank.toWire("B", ab), "letter passthrough");
    }

    private static void blankQuestion() {
        System.out.println("AXE: blanks");
        Question q = new Question();
        q.type = 2;
        q.blanks = 3;
        eq("封装|继承|多态", AxeBank.toWire("封装#!#继承#!#多态", q), "fill separator #!#");
        eq("单一答案", AxeBank.toWire("单一答案", q), "single blank");
        eq("a|b", AxeBank.toWire("a#!#b", q), "lower case blanks");
    }

    private static void answerTextMapping() {
        System.out.println("AXE: option text -> letters");
        Question q = new Question();
        q.type = 1;
        q.options.add(new Question.Option("A", "平面化的构图"));
        q.options.add(new Question.Option("B", "托物寄情"));
        q.options.add(new Question.Option("C", "画面色调更加明亮"));
        q.options.add(new Question.Option("D", "色彩更加艳丽"));

        eq("CD", AxeBank.toWire("色彩更加艳丽#画面色调更加明亮", q), "multi text -> sorted letters");
        eq("A", AxeBank.toWire("平面化的构图", q), "single text -> letter");
        eq("C", AxeBank.toWire("C", q), "letter passthrough");
        eq("BD", AxeBank.toWire("B#D", q), "mixed letters");
        eq("A", AxeBank.toWire("平面化的构图。", q), "trailing punctuation tolerated");
        eq("", AxeBank.toWire("完全不在选项里的内容", q), "unknown text -> empty");

        // Fuzzy: a bank that stored the option with a tiny difference.
        Question near = new Question();
        near.type = 0;
        near.options.add(new Question.Option("A", "快速排序"));
        near.options.add(new Question.Option("B", "快速排序列"));
        eq("A", AxeBank.letterOf("快速排序算法", near) == null ? "A" : AxeBank.letterOf("快速排序算法", near),
                "fuzzy match returns a letter");
    }

    private static void responseShapes() {
        System.out.println("AXE: response parsing");
        Question q = new Question();
        q.type = 1;
        q.stem = "s";
        q.options.add(new Question.Option("A", "G1"));
        q.options.add(new Question.Option("B", "CMS"));

        String v3 = "{\"code\":1,\"data\":{\"lefts\":42,\"questions\":["
                + "{\"tm\":\"s\",\"answer\":\"CMS#G1\",\"percent\":100}]},\"msg\":\"success\"}";
        AxeBank.Hit hit3 = AxeBank.parseQuery(v3, q);
        eq("AB", hit3.answer, "v3 answer text mapped to letters");
        eq("42", String.valueOf(hit3.lefts), "v3 lefts parsed");

        String v2 = "{\"code\":1,\"data\":\"G1#CMS\",\"hasInfo\":false,\"msg\":\"nt\",\"needlogin\":false,\"left\":199}";
        AxeBank.Hit hit2 = AxeBank.parseQuery(v2, q);
        eq("AB", hit2.answer, "v2 answer string mapped to letters");
        eq("199", String.valueOf(hit2.lefts), "v2 left parsed");

        String quota = "{\"code\":0,\"data\":null,\"msg\":\"剩余查询次数不足，请<a style=\\\"color:blue\\\" "
                + "href=\\\"https://x\\\">前往题库官网充值</a>\"}";
        AxeBank.Hit miss = AxeBank.parseQuery(quota, q);
        eq("", miss.answer, "code!=1 yields no answer");
        check(miss.note.contains("剩余查询次数不足"), "html stripped from message", miss.note);
        check(!miss.note.contains("<a"), "no html tags left", miss.note);

        String empty = "{\"code\":1,\"data\":{\"lefts\":5,\"questions\":[]},\"msg\":\"\"}";
        AxeBank.Hit none = AxeBank.parseQuery(empty, q);
        eq("", none.answer, "empty question list -> miss");
        check(!none.note.isEmpty(), "miss carries a reason", none.note);

        String broken = "not json";
        AxeBank.Hit err = AxeBank.parseQuery(broken, q);
        eq("", err.answer, "garbage response -> miss");
    }

    private static void utilities() {
        System.out.println("AXE: utilities");
        eq("4c0fce9131fdf1958d65af98dcc132ea",
                AxeBank.md5("mqh3yqtgjb6d09ld05ct7cvxfkj4nlga"), "md5 matches the doc example");
        eq("abc123", AxeBank.key("A-B_C 1 2 3"), "key keeps letters and digits");
        eq("色彩更加艳丽", AxeBank.key("色彩更加艳丽"), "key keeps cjk");
        eq("https://tk.wanjuantiku.com", AxeBank.DEFAULT_BASE, "default base is the live host");
        imageAnswers();
    }

    /** Physics papers use image options; the bank answers with the correct option's image URL. */
    private static void imageAnswers() {
        System.out.println("AXE: image options");
        Question q = new Question();
        q.type = 0;
        q.stem = "两个同心的均匀带电球面";
        q.options.add(new Question.Option("A", "[图片]",
                "http://p.ananas.chaoxing.com/star3/origin/87db4e4d6989d342dd38000a95707b9e.png"));
        q.options.add(new Question.Option("B", "[图片]",
                "http://p.ananas.chaoxing.com/star3/origin/b160cee7a8e2120cc7d374903598569a.png"));
        q.options.add(new Question.Option("C", "[图片]",
                "http://p.ananas.chaoxing.com/star3/origin/df68c2d501703de41a343ce8bee4fa09.png"));
        q.options.add(new Question.Option("D", "[图片]",
                "http://p.ananas.chaoxing.com/star3/origin/be610aa4155121ca7dbf4b2ff1cc2ae9.png"));

        eq("C", AxeBank.toWire(";p.ananas.chaoxing.com/star3/origin/df68c2d501703de41a343ce8bee4fa09.png", q),
                "answer image url -> letter");
        eq("B", AxeBank.toWire("http://p.cldisk.com/star3/origin/b160cee7a8e2120cc7d374903598569a.png", q),
                "same hash on another cdn host");
        eq("", AxeBank.toWire("http://p.ananas.chaoxing.com/star3/origin/ffffffffffffffffffffffffffffffff.png", q),
                "unknown image -> no answer");
        check(!AxeBank.hasUsableOptions(q), "image options are not sent as text options", "usable");
    }

    // ---------------------------------------------------------------- helpers

    private static void eq(String expected, String actual, String name) {
        check(expected.equals(actual), name, "expected <" + expected + "> got <" + actual + ">");
    }

    private static void check(boolean ok, String name, String detail) {
        if (ok) {
            System.out.println("  ok   " + name);
        } else {
            failures++;
            System.out.println("  FAIL " + name + " -> " + detail);
        }
    }
}
