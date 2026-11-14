package com.goutou.junshi.answer;

import java.util.ArrayList;
import java.util.List;

/** One question as extracted from the exam / homework page. */
public final class Question {

    /** Question bank type code of the host page (0 single, 1 multi, 2 blank, 3 judge, ...). */
    public int type = -1;
    public String id = "";
    public String stem = "";
    public final List<Option> options = new ArrayList<>();
    public int blanks;
    /** True when the stem or the options carry a picture: such questions are sent to the vision model. */
    public boolean hasImage;
    /** Page box of the container (CSS px), used to capture the whole question across screens. */
    public int pageTop;
    public int pageHeight;

    public static final class Option {
        public final String key;
        public final String text;
        /** Absolute URL of the option image; empty for text options. */
        public final String src;

        public Option(String key, String text) {
            this(key, text, "");
        }

        public Option(String key, String text, String src) {
            this.key = key;
            this.text = text;
            this.src = src == null ? "" : src;
        }
    }

    /** Resolved answer for this question. */
    public String answer = "";
    public String source = "";
    public boolean filled;

    public String key() {
        StringBuilder sb = new StringBuilder(stem.length() + 16);
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        sb.append('|').append(type);
        if (!options.isEmpty()) {
            sb.append('|');
            for (Option o : options) {
                sb.append(o.key).append('.').append(o.text).append(';');
            }
        }
        return sb.toString();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(id).append(" t=").append(type);
        if (!stem.isEmpty()) {
            sb.append(" ").append(stem.length() > 40 ? stem.substring(0, 40) + "…" : stem);
        }
        sb.append(" opts=").append(options.size()).append(" blanks=").append(blanks);
        sb.append(" img=").append(hasImage ? 1 : 0);
        if (!answer.isEmpty()) {
            sb.append(" => ").append(answer);
        }
        return sb.toString();
    }
}
