package com.wbx.mdreader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown 渲染器，输出 HTML 给 WebView 显示。
 * 支持：标题、段落、粗斜体、删除线、行内代码、围栏代码块、引用、
 * 有序/无序/嵌套列表、任务列表、表格、分割线、链接、图片、自动链接。
 */
final class MarkdownRenderer {

    static final class Heading {
        final int level;
        final String text;
        final String id;

        Heading(int level, String text, String id) {
            this.level = level;
            this.text = text;
            this.id = id;
        }
    }

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*)$");
    private static final Pattern HR = Pattern.compile("^ {0,3}((\\*[ \\t]*){3,}|(-[ \\t]*){3,}|(_[ \\t]*){3,})$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>[ ]?");
    private static final Pattern LIST = Pattern.compile("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+(.*)$");
    private static final Pattern SETEXT_H1 = Pattern.compile("^ {0,3}=+[ \\t]*$");
    private static final Pattern SETEXT_H2 = Pattern.compile("^ {0,3}-+[ \\t]*$");

    private static final Pattern EMPH_BI = Pattern.compile("\\*\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*\\*");
    private static final Pattern EMPH_B = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");
    private static final Pattern EMPH_I = Pattern.compile("(?<![*A-Za-z0-9_])\\*(?=\\S)([^*]+?)(?<=\\S)\\*(?![*A-Za-z0-9_])");
    private static final Pattern EMPH_UB = Pattern.compile("__(?=\\S)(.+?)(?<=\\S)__");
    private static final Pattern EMPH_UI = Pattern.compile("(?<![A-Za-z0-9_])_(?=\\S)([^_]+?)(?<=\\S)_(?![A-Za-z0-9_])");
    private static final Pattern EMPH_DEL = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern BARE_URL = Pattern.compile("(?<![\"'=])(https?://[^\\s<>\"'\\uE000\\uE001]+)");
    private static final Pattern SAFE_URL = Pattern.compile("^(https?://|mailto:|tel:|#|/|\\.)", Pattern.CASE_INSENSITIVE);

    private final List<Heading> headings = new ArrayList<Heading>();
    private int seq = 0;

    List<Heading> getHeadings() {
        return headings;
    }

    String render(String markdown, String theme, int fontPx) {
        headings.clear();
        seq = 0;
        String src = markdown == null ? "" : markdown;
        if (src.length() > 0 && src.charAt(0) == '\uFEFF') {
            src = src.substring(1);
        }
        src = src.replace("\r\n", "\n").replace('\r', '\n');
        ArrayList<String> lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                lines.add(src.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(src.substring(start));

        StringBuilder body = new StringBuilder(src.length() * 2 + 256);
        parseBlocks(lines, body);

        String t = "light".equals(theme) ? "light" : "dark";
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=4\">"
                + "<style>" + css(fontPx) + "</style></head>"
                + "<body class=\"theme-" + t + "\"><article>" + body + "</article>"
                + "<script>"
                + "function setTheme(t){document.body.className='theme-'+t;}"
                + "function setFont(px){document.documentElement.style.setProperty('--fs',px+'px');}"
                + "function goTo(id){var e=document.getElementById(id);if(e){window.scrollTo(0,e.getBoundingClientRect().top+window.pageYOffset-12);}}"
                + "</script></body></html>";
    }

    // ---------------- 块级解析 ----------------

    private void parseBlocks(ArrayList<String> lines, StringBuilder out) {
        int i = 0;
        int n = lines.size();
        while (i < n) {
            String line = lines.get(i);
            if (line.trim().length() == 0) {
                i++;
                continue;
            }

            Matcher mf = FENCE.matcher(line);
            if (mf.find()) {
                String fence = mf.group(1);
                String info = mf.group(2).trim();
                char fc = fence.charAt(0);
                int flen = fence.length();
                i++;
                StringBuilder code = new StringBuilder();
                while (i < n) {
                    String l = lines.get(i);
                    if (isFenceClose(l, fc, flen)) {
                        i++;
                        break;
                    }
                    code.append(l).append('\n');
                    i++;
                }
                out.append("<pre>");
                if (info.length() > 0) {
                    String lang = info.split("\\s+")[0];
                    out.append("<span class=\"lang\">").append(escape(lang)).append("</span>");
                }
                out.append("<code>").append(escape(code.toString())).append("</code></pre>\n");
                continue;
            }

            Matcher mh = HEADING.matcher(line);
            if (mh.find()) {
                String raw = mh.group(2).trim();
                raw = raw.replaceAll("[ \\t]+#+[ \\t]*$", "");
                heading(mh.group(1).length(), raw, out);
                i++;
                continue;
            }

            if (HR.matcher(line).find()) {
                out.append("<hr/>\n");
                i++;
                continue;
            }

            if (QUOTE.matcher(line).find()) {
                ArrayList<String> inner = new ArrayList<String>();
                while (i < n) {
                    String l = lines.get(i);
                    if (QUOTE.matcher(l).find()) {
                        inner.add(l.replaceFirst("^ {0,3}> ?", ""));
                        i++;
                    } else if (l.trim().length() == 0 && i + 1 < n && QUOTE.matcher(lines.get(i + 1)).find()) {
                        inner.add("");
                        i++;
                    } else {
                        break;
                    }
                }
                out.append("<blockquote>\n");
                parseBlocks(inner, out);
                out.append("</blockquote>\n");
                continue;
            }

            if (line.indexOf('|') >= 0 && i + 1 < n && isTableSep(lines.get(i + 1))) {
                i = parseTable(lines, i, out);
                continue;
            }

            if (line.trim().startsWith("$$")) {
                String first = line.trim().substring(2);
                StringBuilder tex = new StringBuilder();
                if (first.indexOf("$$") >= 0) {
                    tex.append(first, 0, first.indexOf("$$"));
                    i++;
                } else {
                    tex.append(first);
                    i++;
                    while (i < n) {
                        String l = lines.get(i);
                        int close = l.indexOf("$$");
                        if (close >= 0) {
                            tex.append('\n').append(l, 0, close);
                            i++;
                            break;
                        }
                        tex.append('\n').append(l);
                        i++;
                    }
                }
                out.append("<div class=\"math math-block\">").append(miniTex(tex.toString().trim()))
                        .append("</div>\n");
                continue;
            }

            if (LIST.matcher(line).find()) {
                i = parseList(lines, i, out);
                continue;
            }

            if (i + 1 < n && SETEXT_H1.matcher(lines.get(i + 1)).find()) {
                heading(1, line.trim(), out);
                i += 2;
                continue;
            }
            if (i + 1 < n && SETEXT_H2.matcher(lines.get(i + 1)).find()) {
                heading(2, line.trim(), out);
                i += 2;
                continue;
            }

            StringBuilder para = new StringBuilder();
            while (i < n) {
                String l = lines.get(i);
                if (l.trim().length() == 0 || isBlockStart(lines, i)) {
                    break;
                }
                if (para.length() > 0) {
                    para.append('\n');
                }
                para.append(l.trim());
                i++;
            }
            if (para.length() > 0) {
                out.append("<p>").append(inline(para.toString())).append("</p>\n");
            }
        }
    }

    private boolean isBlockStart(ArrayList<String> lines, int i) {
        String l = lines.get(i);
        if (FENCE.matcher(l).find() || HEADING.matcher(l).find() || HR.matcher(l).find()
                || QUOTE.matcher(l).find() || LIST.matcher(l).find()) {
            return true;
        }
        return l.indexOf('|') >= 0 && i + 1 < lines.size() && isTableSep(lines.get(i + 1));
    }

    private void heading(int level, String raw, StringBuilder out) {
        seq++;
        String id = "h" + seq;
        headings.add(new Heading(level, stripMarks(raw), id));
        out.append("<h").append(level).append(" id=\"").append(id).append("\">")
                .append(inline(raw)).append("</h").append(level).append(">\n");
    }

    private boolean isFenceClose(String line, char fc, int minLen) {
        String t = line.trim();
        if (t.length() < minLen) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == fc) {
                continue;
            }
            if (c == ' ' || c == '\t') {
                continue;
            }
            return false;
        }
        int run = 0;
        while (run < t.length() && t.charAt(run) == fc) {
            run++;
        }
        return run >= minLen;
    }

    // ---------------- 列表 ----------------

    private static final class Item {
        int indent;
        boolean ordered;
        String start;
        boolean task;
        boolean checked;
        final ArrayList<String> content = new ArrayList<String>();
    }

    private int parseList(ArrayList<String> lines, int start, StringBuilder out) {
        ArrayList<Item> items = new ArrayList<Item>();
        int i = start;
        int n = lines.size();
        Item cur = null;
        int baseIndent = -1;

        while (i < n) {
            String line = lines.get(i);
            Matcher m = LIST.matcher(line);
            if (m.find()) {
                int indent = indentWidth(m.group(1));
                if (baseIndent < 0) {
                    baseIndent = indent;
                }
                if (indent < baseIndent) {
                    break;
                }
                Item it = new Item();
                it.indent = indent;
                it.ordered = Character.isDigit(m.group(2).charAt(0));
                it.start = it.ordered ? m.group(2).replaceAll("[^0-9]", "") : null;
                String rest = m.group(3);
                if (rest.startsWith("[ ] ") || rest.startsWith("[x] ") || rest.startsWith("[X] ")) {
                    it.task = true;
                    it.checked = !rest.startsWith("[ ] ");
                    rest = rest.substring(4);
                }
                it.content.add(rest);
                items.add(it);
                cur = it;
                i++;
                continue;
            }

            if (line.trim().length() == 0) {
                int j = i + 1;
                while (j < n && lines.get(j).trim().length() == 0) {
                    j++;
                }
                boolean moreList = j < n && (LIST.matcher(lines.get(j)).find()
                        || leadingSpaces(lines.get(j)) > (cur == null ? 0 : cur.indent));
                if (cur != null && moreList) {
                    cur.content.add("");
                    i++;
                    continue;
                }
                break;
            }

            if (cur != null && leadingSpaces(line) > cur.indent) {
                cur.content.add(stripLeading(line, cur.indent + 2));
                i++;
                continue;
            }
            break;
        }

        renderList(items, 0, out, items.isEmpty() ? 0 : items.get(0).indent);
        return i;
    }

    private int renderList(ArrayList<Item> items, int idx, StringBuilder out, int level) {
        if (idx >= items.size()) {
            return idx;
        }
        boolean ordered = items.get(idx).ordered;
        String tag = ordered ? "ol" : "ul";
        out.append('<').append(tag);
        if (ordered && items.get(idx).start != null && !"1".equals(items.get(idx).start)) {
            out.append(" start=\"").append(items.get(idx).start).append('"');
        }
        out.append(">\n");

        while (idx < items.size() && items.get(idx).indent >= level) {
            Item it = items.get(idx);
            if (it.indent > level) {
                break;
            }
            out.append("<li");
            if (it.task) {
                out.append(" class=\"task\"");
            }
            out.append('>');
            if (it.task) {
                out.append("<span class=\"cb").append(it.checked ? " on" : "").append("\">")
                        .append(it.checked ? "&#10003;" : "").append("</span>");
            }
            String content = join(it.content);
            if (content.indexOf("\n\n") >= 0) {
                ArrayList<String> sub = new ArrayList<String>();
                String[] parts = content.split("\n");
                for (String p : parts) {
                    sub.add(p);
                }
                parseBlocks(sub, out);
            } else {
                out.append(inline(content.trim()));
            }
            idx++;
            if (idx < items.size() && items.get(idx).indent > level) {
                out.append('\n');
                idx = renderList(items, idx, out, items.get(idx).indent);
            }
            out.append("</li>\n");
        }

        out.append("</").append(tag).append(">\n");
        return idx;
    }

    // ---------------- 表格 ----------------

    private boolean isTableSep(String line) {
        String t = line.trim();
        if (t.indexOf('-') < 0) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '-' && c != '|' && c != ':' && c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private int parseTable(ArrayList<String> lines, int start, StringBuilder out) {
        ArrayList<String> header = splitRow(lines.get(start));
        ArrayList<String> aligns = new ArrayList<String>();
        ArrayList<String> sep = splitRow(lines.get(start + 1));
        for (int c = 0; c < header.size(); c++) {
            String s = c < sep.size() ? sep.get(c).trim() : "";
            boolean left = s.startsWith(":");
            boolean right = s.endsWith(":");
            if (left && right) {
                aligns.add("center");
            } else if (right) {
                aligns.add("right");
            } else {
                aligns.add("left");
            }
        }

        out.append("<div class=\"tw\"><table><thead><tr>");
        for (int c = 0; c < header.size(); c++) {
            out.append("<th class=\"").append(aligns.get(c)).append("\">")
                    .append(inline(header.get(c).trim())).append("</th>");
        }
        out.append("</tr></thead><tbody>\n");

        int i = start + 2;
        while (i < lines.size()) {
            String l = lines.get(i);
            if (l.trim().length() == 0 || l.indexOf('|') < 0 || isTableSep(l)) {
                break;
            }
            ArrayList<String> cells = splitRow(l);
            out.append("<tr>");
            for (int c = 0; c < header.size(); c++) {
                String v = c < cells.size() ? cells.get(c).trim() : "";
                out.append("<td class=\"").append(aligns.get(c)).append("\">")
                        .append(inline(v)).append("</td>");
            }
            out.append("</tr>\n");
            i++;
        }
        out.append("</tbody></table></div>\n");
        return i;
    }

    private ArrayList<String> splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|") && !t.endsWith("\\|")) {
            t = t.substring(0, t.length() - 1);
        }
        ArrayList<String> cells = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    // ---------------- 行内解析 ----------------

    private String inline(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        StringBuilder work = new StringBuilder(s.length() + 32);
        ArrayList<String> tokens = new ArrayList<String>();
        int i = 0;
        int n = s.length();

        while (i < n) {
            char c = s.charAt(i);
            String tok = null;
            int next = -1;

            if (c == '`') {
                int run = 1;
                while (i + run < n && s.charAt(i + run) == '`') {
                    run++;
                }
                String fence = s.substring(i, i + run);
                int close = s.indexOf(fence, i + run);
                if (close > i + run) {
                    String code = s.substring(i + run, close);
                    tok = "<code>" + escape(code) + "</code>";
                    next = close + run;
                } else if (close == i + run) {
                    next = -1;
                }
            } else if (c == '!' && i + 1 < n && s.charAt(i + 1) == '[') {
                int cb = s.indexOf(']', i + 2);
                if (cb > 0 && cb + 1 < n && s.charAt(cb + 1) == '(') {
                    int cp = s.indexOf(')', cb + 2);
                    if (cp > 0) {
                        tok = imageHtml(s.substring(i + 2, cb), cleanUrl(s.substring(cb + 2, cp)));
                        next = cp + 1;
                    }
                }
            } else if (c == '[') {
                int cb = s.indexOf(']', i + 1);
                if (cb > 0 && cb + 1 < n && s.charAt(cb + 1) == '(') {
                    int cp = s.indexOf(')', cb + 2);
                    if (cp > 0) {
                        String text = s.substring(i + 1, cb);
                        tok = linkHtml(text, cleanUrl(s.substring(cb + 2, cp)));
                        next = cp + 1;
                    }
                }
            } else if (c == '$' && !(i + 1 < n && s.charAt(i + 1) == '$')) {
                int close = -1;
                for (int k = i + 1; k < n; k++) {
                    char ch = s.charAt(k);
                    if (ch == '\\') {
                        k++;
                        continue;
                    }
                    if (ch == '$') {
                        close = k;
                        break;
                    }
                }
                if (close > i + 1) {
                    String tex = s.substring(i + 1, close);
                    if (tex.length() > 0 && !tex.startsWith(" ") && !tex.endsWith(" ")) {
                        tok = "<span class=\"math\">" + miniTex(tex) + "</span>";
                        next = close + 1;
                    }
                }
            } else if (c == '<') {
                int gt = s.indexOf('>', i + 1);
                if (gt > 0) {
                    String inner = s.substring(i + 1, gt);
                    if (inner.matches("(?i)^(https?|mailto):[^\\s<>]+$")) {
                        String label = inner.replaceFirst("(?i)^mailto:", "");
                        tok = "<a href=\"" + escape(inner) + "\">" + escape(label) + "</a>";
                        next = gt + 1;
                    }
                }
            }

            if (tok != null && next > i) {
                work.append('\uE000').append(tokens.size()).append('\uE001');
                tokens.add(tok);
                i = next;
            } else {
                work.append(c);
                i++;
            }
        }

        String out = escape(work.toString());
        out = EMPH_BI.matcher(out).replaceAll("<strong><em>$1</em></strong>");
        out = EMPH_B.matcher(out).replaceAll("<strong>$1</strong>");
        out = EMPH_UB.matcher(out).replaceAll("<strong>$1</strong>");
        out = EMPH_I.matcher(out).replaceAll("<em>$1</em>");
        out = EMPH_UI.matcher(out).replaceAll("<em>$1</em>");
        out = EMPH_DEL.matcher(out).replaceAll("<del>$1</del>");
        out = BARE_URL.matcher(out).replaceAll("<a href=\"$1\">$1</a>");

        for (int k = 0; k < tokens.size(); k++) {
            out = out.replace("\uE000" + k + "\uE001", tokens.get(k));
        }
        return out;
    }

    // ---------------- 简易公式（LaTeX 子集） ----------------

    private static final String[][] SYMBOLS = {
            {"times", "\u00D7"}, {"div", "\u00F7"}, {"cdot", "\u00B7"}, {"pm", "\u00B1"},
            {"mp", "\u2213"}, {"le", "\u2264"}, {"leq", "\u2264"}, {"ge", "\u2265"},
            {"geq", "\u2265"}, {"ne", "\u2260"}, {"neq", "\u2260"}, {"approx", "\u2248"},
            {"infty", "\u221E"}, {"pi", "\u03C0"}, {"alpha", "\u03B1"}, {"beta", "\u03B2"},
            {"gamma", "\u03B3"}, {"theta", "\u03B8"}, {"angle", "\u2220"}, {"triangle", "\u25B3"},
            {"degree", "\u00B0"}, {"circ", "\u00B0"}, {"sum", "\u2211"}, {"in", "\u2208"},
            {"because", "\u2235"}, {"therefore", "\u2234"}, {"parallel", "\u2225"},
            {"perp", "\u22A5"}, {"sim", "\u223C"}, {"cong", "\u2245"}, {"propto", "\u221D"},
            {"leftarrow", "\u2190"}, {"rightarrow", "\u2192"}, {"prime", "\u2032"},
            {"ldots", "\u2026"}, {"cdots", "\u22EF"}, {"quad", "\u2003"},
            {"qquad", "\u2003\u2003"}, {"%", "%"}, {"$", "$"}, {"&", "&"},
            {"{", "{"}, {"}", "}"}, {"_", "_"}, {"#", "#"}, {",", "\u2009"}, {";", " "},
            {"!", ""}, {":", " "}, {" ", " "}
    };

    static String miniTex(String tex) {
        StringBuilder out = new StringBuilder();
        texRun(tex, 0, tex.length(), out);
        return out.toString();
    }

    private static void texRun(String s, int from, int to, StringBuilder out) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\\') {
                int j = i + 1;
                if (j >= to) {
                    break;
                }
                if (!Character.isLetter(s.charAt(j))) {
                    String one = symbolFor(String.valueOf(s.charAt(j)));
                    out.append(one != null ? one : escape(String.valueOf(s.charAt(j))));
                    i = j + 1;
                    continue;
                }
                while (j < to && Character.isLetter(s.charAt(j))) {
                    j++;
                }
                String cmd = s.substring(i + 1, j);
                if (cmd.equals("frac") || cmd.equals("dfrac") || cmd.equals("tfrac")) {
                    int[] a = new int[2];
                    int k = readGroup(s, j, to, a);
                    int[] b = new int[2];
                    k = readGroup(s, k, to, b);
                    out.append("<span class=\"frac\"><span class=\"num\">");
                    texRun(s, a[0], a[1], out);
                    out.append("</span><span class=\"den\">");
                    texRun(s, b[0], b[1], out);
                    out.append("</span></span>");
                    i = k;
                    continue;
                }
                if (cmd.equals("sqrt")) {
                    int[] a = new int[2];
                    int k = readGroup(s, j, to, a);
                    out.append("<span class=\"sqrt\"><span class=\"rad\">\u221A</span>"
                            + "<span class=\"body\">");
                    texRun(s, a[0], a[1], out);
                    out.append("</span></span>");
                    i = k;
                    continue;
                }
                if (cmd.equals("text") || cmd.equals("mathrm") || cmd.equals("operatorname")) {
                    int[] a = new int[2];
                    int k = readGroup(s, j, to, a);
                    out.append("<span class=\"mtext\">")
                            .append(escape(s.substring(a[0], a[1]))).append("</span>");
                    i = k;
                    continue;
                }
                if (cmd.equals("left") || cmd.equals("right") || cmd.equals("displaystyle")
                        || cmd.equals("limits") || cmd.equals("nolimits")) {
                    i = j;
                    continue;
                }
                String sym = symbolFor(cmd);
                if (sym != null) {
                    out.append(sym);
                } else {
                    out.append(escape("\\" + cmd));
                }
                i = j;
                continue;
            }
            if (c == '^' || c == '_') {
                int[] a = new int[2];
                int k = readGroup(s, i + 1, to, a);
                if (a[1] > a[0]) {
                    out.append(c == '^' ? "<sup>" : "<sub>");
                    texRun(s, a[0], a[1], out);
                    out.append(c == '^' ? "</sup>" : "</sub>");
                    i = k;
                    continue;
                }
                i++;
                continue;
            }
            if (c == '{' || c == '}' || c == '&') {
                i++;
                continue;
            }
            if (c == '~') {
                out.append(' ');
                i++;
                continue;
            }
            out.append(escape(String.valueOf(c)));
            i++;
        }
    }

    private static int readGroup(String s, int pos, int to, int[] range) {
        int i = pos;
        while (i < to && s.charAt(i) == ' ') {
            i++;
        }
        if (i >= to) {
            range[0] = range[1] = i;
            return i;
        }
        if (s.charAt(i) == '{') {
            int depth = 0;
            int start = i + 1;
            for (int k = i; k < to; k++) {
                char ch = s.charAt(k);
                if (ch == '\\') {
                    k++;
                    continue;
                }
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        range[0] = start;
                        range[1] = k;
                        return k + 1;
                    }
                }
            }
            range[0] = start;
            range[1] = to;
            return to;
        }
        if (s.charAt(i) == '\\') {
            int j = i + 1;
            int k = j;
            while (k < to && Character.isLetter(s.charAt(k))) {
                k++;
            }
            if (k == j) {
                k = Math.min(j + 1, to);
            }
            range[0] = i;
            range[1] = k;
            return k;
        }
        int j = i + 1;
        if (Character.isDigit(s.charAt(i))) {
            while (j < to && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                j++;
            }
        }
        range[0] = i;
        range[1] = j;
        return j;
    }

    private static String symbolFor(String cmd) {
        for (String[] pair : SYMBOLS) {
            if (pair[0].equals(cmd)) {
                return pair[1];
            }
        }
        return null;
    }

    private String cleanUrl(String raw) {
        String u = raw.trim();
        int sp = u.indexOf(' ');
        if (sp > 0) {
            u = u.substring(0, sp);
        }
        if (u.startsWith("<") && u.endsWith(">")) {
            u = u.substring(1, u.length() - 1);
        }
        return u;
    }

    private String linkHtml(String text, String url) {
        if (url.length() == 0 || !SAFE_URL.matcher(url).find()) {
            return escape(text);
        }
        return "<a href=\"" + escape(url) + "\">" + inline(text) + "</a>";
    }

    private String imageHtml(String alt, String url) {
        boolean web = url.toLowerCase().startsWith("http://") || url.toLowerCase().startsWith("https://");
        if (web) {
            return "<img src=\"" + escape(url) + "\" alt=\"" + escape(alt) + "\"/>";
        }
        return "<span class=\"img\">图片 " + escape(alt.length() > 0 ? alt : url) + "</span>";
    }

    // ---------------- 工具 ----------------

    private static int indentWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) == '\t' ? 4 : 1;
        }
        return w;
    }

    private static int leadingSpaces(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                w++;
            } else if (c == '\t') {
                w += 4;
            } else {
                break;
            }
        }
        return w;
    }

    private static String stripLeading(String s, int count) {
        int w = 0;
        int i = 0;
        while (i < s.length() && w < count) {
            char c = s.charAt(i);
            if (c == ' ') {
                w++;
                i++;
            } else if (c == '\t') {
                w += 4;
                i++;
            } else {
                break;
            }
        }
        return s.substring(i);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String stripMarks(String s) {
        String t = s.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("[`*_~]", "");
        t = t.replaceAll("<[^>]+>", "");
        return t.trim();
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private String css(int fontPx) {
        return "html{-webkit-text-size-adjust:100%;}"
                + ":root{--fs:" + fontPx + "px;}"
                + "*{box-sizing:border-box;}"
                + "body{margin:0;font-family:-apple-system,'Noto Sans SC','PingFang SC','Microsoft YaHei',sans-serif;"
                + "font-size:var(--fs);line-height:1.78;letter-spacing:.01em;"
                + "-webkit-font-smoothing:antialiased;word-wrap:break-word;overflow-wrap:break-word;}"
                + "body.theme-dark{background:#121418;color:#d9dee6;}"
                + "body.theme-light{background:#ffffff;color:#1f2328;}"
                + "article{padding:22px 20px 96px;max-width:860px;margin:0 auto;}"
                + "h1,h2,h3,h4,h5,h6{line-height:1.35;font-weight:700;margin:1.7em 0 .7em;}"
                + "h1{font-size:1.62em;margin-top:.6em;letter-spacing:-.01em;}"
                + "h2{font-size:1.32em;padding-bottom:.3em;border-bottom:1px solid var(--line);}"
                + "h3{font-size:1.14em;}h4{font-size:1em;}h5,h6{font-size:.94em;color:var(--muted);}"
                + "p{margin:.85em 0;}"
                + "a{color:var(--link);text-decoration:none;border-bottom:1px solid rgba(90,140,255,.35);}"
                + "strong{font-weight:700;color:var(--strong);}"
                + "em{font-style:italic;}"
                + "del{opacity:.62;}"
                + "code{font-family:'SF Mono',Menlo,Consolas,'Roboto Mono',monospace;font-size:.87em;"
                + "background:var(--code-bg);padding:.16em .38em;border-radius:5px;}"
                + "pre{background:var(--code-bg);border:1px solid var(--line);border-radius:10px;"
                + "padding:14px 16px;overflow-x:auto;margin:1em 0;position:relative;}"
                + "pre code{background:none;padding:0;font-size:.85em;line-height:1.62;"
                + "white-space:pre-wrap;word-break:break-word;}"
                + ".lang{position:absolute;top:6px;right:10px;font-size:.68em;color:var(--muted);"
                + "letter-spacing:.08em;text-transform:uppercase;}"
                + "blockquote{margin:1em 0;padding:.25em 0 .25em 16px;border-left:3px solid var(--quote);"
                + "color:var(--muted);background:var(--quote-bg);border-radius:0 8px 8px 0;}"
                + "blockquote p{margin:.4em 0;}"
                + "ul,ol{margin:.7em 0;padding-left:1.45em;}"
                + "li ul,li ol{padding-left:1.15em;margin:.35em 0;}"
                + "li{margin:.32em 0;}"
                + "li.task{list-style:none;margin-left:-1.4em;}"
                + "li.task .cb{display:inline-block;width:1.05em;height:1.05em;line-height:1.05em;"
                + "text-align:center;font-size:.8em;border:1.5px solid var(--muted);border-radius:4px;"
                + "margin-right:.5em;vertical-align:-1px;color:#fff;}"
                + "li.task .cb.on{background:var(--link);border-color:var(--link);}"
                + "hr{border:none;height:1px;background:var(--line);margin:1.8em 0;}"
                + ".tw{overflow-x:auto;margin:1.1em 0;}"
                + "table{border-collapse:collapse;width:100%;font-size:.92em;}"
                + "th,td{border:1px solid var(--line);padding:8px 12px;}"
                + "th{background:var(--th-bg);font-weight:600;white-space:nowrap;}"
                + "td.left,th.left{text-align:left;}td.center,th.center{text-align:center;}"
                + "td.right,th.right{text-align:right;}"
                + "img{max-width:100%;border-radius:8px;}"
                + ".img{display:inline-block;padding:.2em .5em;border:1px dashed var(--line);"
                + "border-radius:6px;font-size:.85em;color:var(--muted);}"
                + ".math{font-family:'Cambria Math',Cambria,'Times New Roman',serif;font-size:1.02em;}"
                + ".math-block{display:block;text-align:center;margin:1.2em 0;padding:.35em 0;font-size:1.08em;}"
                + ".frac{display:inline-flex;flex-direction:column;vertical-align:middle;"
                + "text-align:center;margin:0 .18em;font-size:.95em;}"
                + ".frac .num{border-bottom:1.2px solid currentColor;padding:0 .25em;line-height:1.3;}"
                + ".frac .den{padding:0 .25em;line-height:1.3;}"
                + ".sqrt{display:inline-block;white-space:nowrap;}"
                + ".sqrt .rad{margin-right:-.04em;}"
                + ".sqrt .body{display:inline-block;border-top:1.2px solid currentColor;padding:0 .18em;}"
                + ".mtext{font-family:inherit;}"
                + "sup,sub{font-size:.72em;line-height:0;}"
                + "body.theme-dark{--line:#282d36;--muted:#8d97a6;--link:#7aa2ff;--strong:#ffffff;"
                + "--code-bg:#1b1f26;--quote:#3b4757;--quote-bg:#171b21;--th-bg:#181c22;}"
                + "body.theme-light{--line:#e4e8ee;--muted:#6a737d;--link:#2563eb;--strong:#111418;"
                + "--code-bg:#f5f7fa;--quote:#d6dde7;--quote-bg:#fafbfc;--th-bg:#f7f9fb;}";
    }
}
