package com.goutou.junshi.answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Answer string normalisation.
 *
 * <p>Question banks answer in wildly different formats: {@code A}, {@code a b}, {@code A,B},
 * {@code 正确答案：AB}, {@code 对}, {@code √}, {@code true}, or one line per blank.  Everything is
 * reduced to one of two wire formats:</p>
 * <ul>
 *   <li>choice questions — upper case letters, sorted, no separators: {@code AB}</li>
 *   <li>blank questions — the text of each blank joined with {@code |}</li>
 * </ul>
 */
public final class Norm {

    private static final String TRUE_WORDS = "对|正确|是|√|✓|true|t|yes|y|1|对的|正确的|是的|确定";
    private static final String FALSE_WORDS = "错|错误|否|×|✗|x|false|f|no|n|0|不对|不正确|不是|错的|错误的";
    private static final String NOISE = "答案是|正确答案|参考答案|标准答案|答案|选项|答:|答：|答案:|答案：|选|为|:：.。、,，;；";

    private Norm() {
    }

    /** Removes prefixes, quotes and trailing punctuation that banks like to add. */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        s = s.replace('\u3000', ' ').replace('\r', ' ');
        s = s.replaceAll("^(?:正确答案|参考答案|标准答案|答案|解析|答)[\\s:：]*", "");
        s = s.replaceAll("^[\\[【(（<《\"'`]+", "").replaceAll("[\\]】)）>》\"'`]+$", "");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 2 && s.endsWith("。")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** True when the payload looks like a choice answer ({@code A}/{@code AB}/{@code A,B}). */
    public static boolean looksLikeChoice(String s) {
        String c = clean(s);
        if (c.isEmpty() || c.length() > 16) {
            return false;
        }
        String letters = c.replaceAll("[^A-Za-z]", "");
        return !letters.isEmpty() && letters.length() == c.replaceAll("[\\s,，、;；/|#&+]", "").length();
    }

    /** {@code "a,b"} -> {@code "AB"}; returns {@code ""} when no letters exist. */
    public static String letters(String raw) {
        String c = clean(raw);
        if (c.isEmpty()) {
            return "";
        }
        Set<Character> set = new LinkedHashSet<>();
        for (int i = 0; i < c.length(); i++) {
            char ch = Character.toUpperCase(c.charAt(i));
            if (ch >= 'A' && ch <= 'Z') {
                set.add(ch);
            }
        }
        if (set.isEmpty()) {
            return "";
        }
        List<Character> list = new ArrayList<>(set);
        java.util.Collections.sort(list);
        StringBuilder sb = new StringBuilder(list.size());
        for (Character ch : list) {
            sb.append(ch);
        }
        return sb.toString();
    }

    /** {@code 对/√/true} -> {@code true}; {@code 错/×/false} -> {@code false}; else {@code null}. */
    public static Boolean truth(String raw) {
        String c = clean(raw).toLowerCase(Locale.ROOT);
        if (c.isEmpty()) {
            return null;
        }
        List<String> yes = Arrays.asList(TRUE_WORDS.split("\\|"));
        List<String> no = Arrays.asList(FALSE_WORDS.split("\\|"));
        if (yes.contains(c)) {
            return Boolean.TRUE;
        }
        if (no.contains(c)) {
            return Boolean.FALSE;
        }
        for (String w : yes) {
            if (!w.isEmpty() && c.equals(w)) {
                return Boolean.TRUE;
            }
        }
        for (String w : no) {
            if (!w.isEmpty() && c.equals(w)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** Splits a multi blank answer on any common separator. */
    public static List<String> blanks(String raw) {
        List<String> out = new ArrayList<>();
        String c = clean(raw);
        if (c.isEmpty()) {
            return out;
        }
        String[] parts = c.split("\\s*(?:\\||#|###|---|/|\\\\|;|；|、|，|,)\\s*");
        for (String p : parts) {
            String v = p.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        if (out.isEmpty()) {
            out.add(c);
        }
        return out;
    }

    /**
     * Rewrites a bank answer into the wire format expected by the page for this question type.
     *
     * @param type host question type ({@code 0}/{@code 1} choice, {@code 3} judge, {@code 2}/{@code 4} blank)
     */
    /** 简答 / 名词解释 / 论述 / 计算: free text, written into an editor instead of letters. */
    public static boolean isEssay(int type) {
        return type == 4 || type == 5 || type == 6 || type == 14;
    }

    /** Keeps an essay answer readable: newlines survive, prefixes and code fences are removed. */
    public static String essay(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        s = s.replaceAll("(?is)<(think|reasoning)>.*?</\\1>", " ");
        s = s.replaceAll("(?m)^\\s*```[a-zA-Z]*\\s*$", "");
        s = s.replace("```", "");
        s = s.replaceAll("^(?:正确答案|参考答案|标准答案|答案|解答|答)[\\s:：]*", "").trim();
        s = s.replaceAll("\n{3,}", "\n\n");
        if (s.length() > 2000) {
            s = s.substring(0, 2000);
        }
        return s.trim();
    }

    public static String forType(int type, String raw) {
        if (isEssay(type)) {
            return essay(raw);
        }
        String c = clean(raw);
        if (c.isEmpty()) {
            return "";
        }
        switch (type) {
            case 0:
            case 1: {
                String l = letters(c);
                if (l.length() > 8) {
                    l = l.substring(0, 8);
                }
                return l;
            }
            case 3: {
                Boolean b = truth(c);
                if (b != null) {
                    return b ? "A" : "B";
                }
                String l = letters(c);
                return l;
            }
            case 2:
            {
                List<String> parts = blanks(c);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) {
                        sb.append('|');
                    }
                    sb.append(parts.get(i));
                }
                return sb.toString();
            }
            default: {
                String l = letters(c);
                return l.isEmpty() ? c : l;
            }
        }
    }

    /** Human readable form for the overlay. */
    public static String display(String raw) {
        String c = clean(raw);
        return c.isEmpty() ? "?" : c;
    }

    /** Cheap similarity used to pick between two candidate bank answers. */
    public static double similar(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        String x = clean(a);
        String y = clean(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        if (x.equals(y)) {
            return 1;
        }
        String bigX = bigrams(x);
        String bigY = bigrams(y);
        if (bigX.isEmpty() || bigY.isEmpty()) {
            return 0;
        }
        int hit = 0;
        String rest = bigY;
        for (int i = 0; i + 1 < bigX.length(); i += 2) {
            String g = bigX.substring(i, i + 2);
            int at = rest.indexOf(g);
            if (at >= 0) {
                hit++;
                rest = rest.substring(0, at) + rest.substring(at + 2);
            }
        }
        return 2.0 * hit / (bigX.length() / 2 + bigY.length() / 2);
    }

    private static String bigrams(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < s.length(); i++) {
            sb.append(s, i, i + 2);
        }
        return sb.toString();
    }

    /** Utility kept package visible for tests. */
    static String stripNoise(String s) {
        return s.replaceAll(NOISE, "");
    }
}
