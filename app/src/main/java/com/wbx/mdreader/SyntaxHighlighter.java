package com.wbx.mdreader;

import android.text.Editable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编辑区 Markdown 语法着色。
 * 关键约束：输入法组合输入（拼音未上屏）期间不改动 span，否则会丢光标。
 */
final class SyntaxHighlighter {

    static final class MdColor extends ForegroundColorSpan {
        MdColor(int color) {
            super(color);
        }
    }

    static final class MdBold extends StyleSpan {
        MdBold() {
            super(android.graphics.Typeface.BOLD);
        }
    }

    static final class MdBg extends BackgroundColorSpan {
        MdBg(int color) {
            super(color);
        }
    }

    private static final Pattern P_FENCE = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern P_HEADING = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t].*$");
    private static final Pattern P_QUOTE = Pattern.compile("(?m)^[ \\t]{0,3}>.*$");
    private static final Pattern P_LINK = Pattern.compile("\\[[^\\]\\n]*\\]\\([^)\\n]*\\)");
    private static final Pattern P_CODE = Pattern.compile("`[^`\\n]+`");
    private static final Pattern P_BOLD = Pattern.compile("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__");
    private static final Pattern P_ITALIC = Pattern.compile("(?<![*A-Za-z0-9_])\\*[^*\\n]+\\*(?!\\*)");
    private static final Pattern P_DEL = Pattern.compile("~~[^~\\n]+~~");
    private static final Pattern P_LIST = Pattern.compile("(?m)^[ \\t]*([-*+]|\\d{1,9}[.)])[ \\t]");

    private static final int MAX_LEN = 160000;

    private SyntaxHighlighter() {
    }

    static void apply(EditText et, Palette p) {
        Editable e = et.getText();
        if (e == null) {
            return;
        }
        if (e.length() > MAX_LEN) {
            return;
        }
        if (BaseInputConnection.getComposingSpanStart(e) >= 0) {
            return;
        }
        int len = e.length();
        for (MdColor s : e.getSpans(0, len, MdColor.class)) {
            e.removeSpan(s);
        }
        for (MdBold s : e.getSpans(0, len, MdBold.class)) {
            e.removeSpan(s);
        }
        for (MdBg s : e.getSpans(0, len, MdBg.class)) {
            e.removeSpan(s);
        }
        if (len == 0) {
            return;
        }
        String text = e.toString();

        color(e, P_FENCE, text, p.synCode, true);
        color(e, P_QUOTE, text, p.synQuote, false);
        color(e, P_LINK, text, p.synLink, false);
        color(e, P_CODE, text, p.synCode, false);
        color(e, P_BOLD, text, p.synMarker, false);
        color(e, P_ITALIC, text, p.synMarker, false);
        color(e, P_DEL, text, p.synDim, false);
        boldAndColor(e, P_HEADING, text, p.synHeading);

        Matcher m = P_LIST.matcher(text);
        while (m.find()) {
            span(e, new MdColor(p.synMarker), m.start(1), m.end(1));
        }
    }

    private static void color(Editable e, Pattern pat, String text, int color, boolean bg) {
        Matcher m = pat.matcher(text);
        while (m.find()) {
            if (bg) {
                span(e, new MdBg(0x14808080), m.start(), m.end());
            }
            span(e, new MdColor(color), m.start(), m.end());
        }
    }

    private static void boldAndColor(Editable e, Pattern pat, String text, int color) {
        Matcher m = pat.matcher(text);
        while (m.find()) {
            span(e, new MdBold(), m.start(), m.end());
            span(e, new MdColor(color), m.start(), m.end());
        }
    }

    private static void span(Editable e, Object s, int start, int end) {
        if (start < 0 || end > e.length() || start >= end) {
            return;
        }
        e.setSpan(s, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
