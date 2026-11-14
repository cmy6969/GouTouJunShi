import com.goutou.junshi.answer.Norm;

/**
 * Plain JVM checks for the answer normaliser.
 *
 * <p>{@code Norm} has no Android dependency, so it can be compiled and executed with a bare JDK:</p>
 *
 * <pre>
 *   javac -d build/test test/NormTest.java app/src/main/java/io/lumen/cx/answer/Norm.java
 *   java -cp build/test NormTest
 * </pre>
 */
public final class NormTest {

    private static int failures;

    public static void main(String[] args) {
        // ---- choice answers
        eq("A", Norm.forType(0, "A"), "single letter");
        eq("AB", Norm.forType(0, "a,b"), "lower case with comma");
        eq("AB", Norm.forType(1, "B、A"), "multi with ideographic comma");
        eq("AB", Norm.forType(1, "正确答案：B A"), "prefix + space");
        eq("ABC", Norm.forType(1, "C#A#B"), "hash separated");
        eq("", Norm.forType(0, "不知道"), "no letters at all");

        // ---- judgment
        eq("A", Norm.forType(3, "对"), "对 -> A");
        eq("A", Norm.forType(3, "正确"), "正确 -> A");
        eq("A", Norm.forType(3, "√"), "√ -> A");
        eq("A", Norm.forType(3, "true"), "true -> A");
        eq("B", Norm.forType(3, "错"), "错 -> B");
        eq("B", Norm.forType(3, "×"), "× -> B");
        eq("B", Norm.forType(3, "false"), "false -> B");

        // ---- blanks
        eq("封装|继承|多态", Norm.forType(2, "封装|继承|多态"), "pipe separated");
        eq("封装|继承|多态", Norm.forType(2, "封装、继承、多态"), "ideographic comma");
        eq("封装|继承", Norm.forType(2, "封装; 继承"), "semicolon + space");
        eq("热力学第一定律 Q=ΔU+W", Norm.forType(4, "热力学第一定律 Q=ΔU+W"), "essay keeps text");
        eq("第一行\n第二行", Norm.forType(5, "第一行\n第二行"), "essay keeps line breaks");
        eq("复述内容", Norm.forType(4, "答案：复述内容"), "essay strips the 答案 prefix");
        eq("单一", Norm.forType(2, "单一"), "single blank");

        // ---- cleaning
        eq("A", Norm.clean("答案：A"), "clean prefix");
        eq("A", Norm.clean("【A】"), "clean brackets");
        eq("访问修饰符", Norm.clean("访问修饰符。"), "trailing period");

        // ---- truth
        check(Norm.truth("是") == Boolean.TRUE, "是 is true");
        check(Norm.truth("否") == Boolean.FALSE, "否 is false");
        check(Norm.truth("A") == null, "A is not a boolean");

        // ---- similarity
        check(Norm.similar("下列哪个是关键字", "下列哪个是关键字") == 1.0, "identical = 1.0");
        check(Norm.similar("下列哪个是关键字", "下面哪个是关键字") > 0.5, "near match > 0.5");
        check(Norm.similar("完全不同的题目", "ABC") < 0.3, "unrelated < 0.3");

        // ---- choice detection
        check(Norm.looksLikeChoice("A,B"), "A,B looks like choice");
        check(!Norm.looksLikeChoice("封装"), "chinese text is not a choice");

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL NORM CHECKS PASSED");
            return;
        }
        System.out.println(failures + " CHECK(S) FAILED");
        System.exit(1);
    }

    private static void eq(String expected, String actual, String name) {
        check(expected.equals(actual), name + " expected <" + expected + "> got <" + actual + ">");
    }

    private static void check(boolean ok, String name) {
        if (ok) {
            System.out.println("  ok   " + name);
        } else {
            failures++;
            System.out.println("  FAIL " + name);
        }
    }
}
