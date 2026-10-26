package io.ohmvir.plugins.jenkinsocr.notes.content;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the inline content of a Markdown paragraph or heading.
 *
 * <p>Supports code spans, emphasis ({@code *}/{@code _}), strong emphasis, {@code ~~strikethrough~~}, links,
 * images (rendered as links), autolinks, backslash escapes and TeX math delimited by {@code $...$},
 * {@code $$...$$}, {@code \(...\)} or {@code \[...\]}. Emphasis follows the CommonMark delimiter rules. Math is
 * recognised before anything else so that {@code _}, {@code *} and backslashes inside formulas are never
 * mistaken for Markdown syntax.
 */
final class MarkdownInlineParser {
    private static final Pattern AUTOLINK = Pattern.compile("<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^<>\\s]*)>");
    private static final Inline.Style ITALIC = Inline.Style.PLAIN.withItalic();
    private static final Inline.Style BOLD = Inline.Style.PLAIN.withBold();
    private static final Inline.Style STRIKETHROUGH = Inline.Style.PLAIN.withStrikethrough();

    private final String src;
    private int pos;
    private final List<Node> nodes = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    private MarkdownInlineParser(String src) {
        this.src = src;
    }

    static List<Inline> parse(String source) {
        InlineListBuilder builder = new InlineListBuilder();
        flatten(new MarkdownInlineParser(source).parseNodes(), Inline.Style.PLAIN, builder);
        return builder.build();
    }

    private List<Node> parseNodes() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            switch (c) {
                case '\\' -> backslash();
                case '`' -> codeSpan();
                case '$' -> dollarMath();
                case '*', '_', '~' -> delimiterRun(c);
                case '[' -> {
                    if (!link(false)) {
                        literal("[");
                    }
                }
                case '!' -> {
                    if (!(pos + 1 < src.length() && src.charAt(pos + 1) == '[' && link(true))) {
                        literal("!");
                    }
                }
                case '<' -> {
                    if (!autolink()) {
                        literal("<");
                    }
                }
                default -> {
                    text.append(c);
                    pos++;
                }
            }
        }
        flushText();
        processEmphasis(nodes);
        return nodes;
    }

    private void literal(String value) {
        text.append(value);
        pos += value.length();
    }

    private void flushText() {
        if (!text.isEmpty()) {
            nodes.add(new TextNode(text.toString()));
            text.setLength(0);
        }
    }

    private void emit(Node node) {
        flushText();
        nodes.add(node);
    }

    private void emitMath(String expression) {
        String trimmed = expression.strip();
        if (!trimmed.isEmpty()) {
            emit(new MathNode(trimmed));
        }
    }

    private void backslash() {
        if (pos + 1 >= src.length()) {
            literal("\\");
            return;
        }
        char next = src.charAt(pos + 1);
        if (next == '(' || next == '[') {
            String close = next == '(' ? "\\)" : "\\]";
            int end = src.indexOf(close, pos + 2);
            if (end >= 0) {
                emitMath(src.substring(pos + 2, end));
                pos = end + 2;
            } else {
                text.append(next);
                pos += 2;
            }
        } else if (isAsciiPunctuation(next)) {
            text.append(next);
            pos += 2;
        } else if (next == '\n') {
            text.append('\n');
            pos += 2;
        } else {
            literal("\\");
        }
    }

    private void codeSpan() {
        int runLength = runLength(pos, '`');
        int searchFrom = pos + runLength;
        while (true) {
            int candidate = src.indexOf('`', searchFrom);
            if (candidate < 0) {
                literal("`".repeat(runLength));
                return;
            }
            int candidateLength = runLength(candidate, '`');
            if (candidateLength == runLength) {
                String code = src.substring(pos + runLength, candidate).replace('\n', ' ');
                if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank()) {
                    code = code.substring(1, code.length() - 1);
                }
                emit(new CodeNode(code));
                pos = candidate + candidateLength;
                return;
            }
            searchFrom = candidate + candidateLength;
        }
    }

    private void dollarMath() {
        if (pos + 1 < src.length() && src.charAt(pos + 1) == '$') {
            int end = indexOfUnescaped("$$", pos + 2);
            if (end > pos + 2) {
                emitMath(src.substring(pos + 2, end));
                pos = end + 2;
            } else {
                literal("$$");
            }
            return;
        }
        // Pandoc's rules for $...$: the opening $ must be followed by a non-space character, the closing $ must
        // follow a non-space character and must not be followed by a digit. This keeps prices like "$5" literal.
        if (pos + 1 >= src.length() || Character.isWhitespace(src.charAt(pos + 1))) {
            literal("$");
            return;
        }
        int close = indexOfUnescaped("$", pos + 1);
        if (close < 0
                || Character.isWhitespace(src.charAt(close - 1))
                || (close + 1 < src.length() && Character.isDigit(src.charAt(close + 1)))) {
            literal("$");
            return;
        }
        emitMath(src.substring(pos + 1, close));
        pos = close + 1;
    }

    private void delimiterRun(char c) {
        int start = pos;
        int count = runLength(start, c);
        pos += count;
        if (c == '~' && count != 2) {
            text.append(String.valueOf(c).repeat(count));
            return;
        }
        char before = start == 0 ? ' ' : src.charAt(start - 1);
        char after = pos >= src.length() ? ' ' : src.charAt(pos);
        boolean beforeSpace = Character.isWhitespace(before);
        boolean afterSpace = Character.isWhitespace(after);
        boolean beforePunctuation = isPunctuation(before);
        boolean afterPunctuation = isPunctuation(after);
        boolean leftFlanking = !afterSpace && (!afterPunctuation || beforeSpace || beforePunctuation);
        boolean rightFlanking = !beforeSpace && (!beforePunctuation || afterSpace || afterPunctuation);
        boolean canOpen;
        boolean canClose;
        if (c == '_') {
            canOpen = leftFlanking && (!rightFlanking || beforePunctuation);
            canClose = rightFlanking && (!leftFlanking || afterPunctuation);
        } else {
            canOpen = leftFlanking;
            canClose = rightFlanking;
        }
        emit(new DelimiterNode(c, count, canOpen, canClose));
    }

    private boolean link(boolean image) {
        int open = image ? pos + 1 : pos;
        int close = closingBracket(open);
        if (close < 0 || close + 1 >= src.length() || src.charAt(close + 1) != '(') {
            return false;
        }
        int p = skipSpaces(close + 2);
        String url;
        if (p < src.length() && src.charAt(p) == '<') {
            int end = src.indexOf('>', p + 1);
            if (end < 0) {
                return false;
            }
            url = src.substring(p + 1, end);
            p = end + 1;
        } else {
            int start = p;
            int depth = 0;
            while (p < src.length()) {
                char ch = src.charAt(p);
                if (ch == '\\' && p + 1 < src.length()) {
                    p += 2;
                    continue;
                }
                if (Character.isWhitespace(ch)) {
                    break;
                }
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                }
                p++;
            }
            url = unescape(src.substring(start, p));
        }
        p = skipSpaces(p);
        if (p < src.length() && (src.charAt(p) == '"' || src.charAt(p) == '\'' || src.charAt(p) == '(')) {
            char titleEnd = src.charAt(p) == '(' ? ')' : src.charAt(p);
            int end = src.indexOf(titleEnd, p + 1);
            if (end < 0) {
                return false;
            }
            p = skipSpaces(end + 1);
        }
        if (p >= src.length() || src.charAt(p) != ')') {
            return false;
        }
        String label = src.substring(open + 1, close);
        List<Node> children;
        if (label.isBlank()) {
            children = List.of(new TextNode(url));
        } else if (image) {
            children = List.of(new TextNode(label));
        } else {
            children = new MarkdownInlineParser(label).parseNodes();
        }
        emit(new LinkNode(children, url));
        pos = p + 1;
        return true;
    }

    private boolean autolink() {
        Matcher matcher = AUTOLINK.matcher(src).region(pos, src.length());
        if (!matcher.lookingAt()) {
            return false;
        }
        String url = matcher.group(1);
        emit(new LinkNode(List.of(new TextNode(url)), url));
        pos = matcher.end();
        return true;
    }

    private int closingBracket(int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int skipSpaces(int p) {
        while (p < src.length() && (src.charAt(p) == ' ' || src.charAt(p) == '\t' || src.charAt(p) == '\n')) {
            p++;
        }
        return p;
    }

    private int runLength(int start, char c) {
        int end = start;
        while (end < src.length() && src.charAt(end) == c) {
            end++;
        }
        return end - start;
    }

    private int indexOfUnescaped(String needle, int from) {
        for (int i = from; i <= src.length() - needle.length(); i++) {
            if (src.charAt(i) == '\\') {
                i++;
            } else if (src.startsWith(needle, i)) {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && isAsciiPunctuation(value.charAt(i + 1))) {
                result.append(value.charAt(++i));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    static boolean isAsciiPunctuation(char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }

    private static boolean isPunctuation(char c) {
        if (isAsciiPunctuation(c)) {
            return true;
        }
        return switch (Character.getType(c)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION,
                    Character.MATH_SYMBOL,
                    Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    /**
     * Matches emphasis delimiters following the CommonMark "process emphasis" procedure, replacing each matched
     * opener/closer pair and the nodes between them with a {@link StyledNode}.
     */
    private static void processEmphasis(List<Node> nodes) {
        int closerIndex = 0;
        while (closerIndex < nodes.size()) {
            if (!(nodes.get(closerIndex) instanceof DelimiterNode closer) || !closer.canClose || closer.count == 0) {
                closerIndex++;
                continue;
            }
            int openerIndex = -1;
            for (int i = closerIndex - 1; i >= 0; i--) {
                if (nodes.get(i) instanceof DelimiterNode opener
                        && opener.character == closer.character
                        && opener.canOpen
                        && opener.count > 0
                        && canPair(opener, closer)) {
                    openerIndex = i;
                    break;
                }
            }
            if (openerIndex < 0) {
                closerIndex++;
                continue;
            }
            DelimiterNode opener = (DelimiterNode) nodes.get(openerIndex);
            int used = closer.character == '~' || (opener.count >= 2 && closer.count >= 2) ? 2 : 1;
            Inline.Style style = closer.character == '~' ? STRIKETHROUGH : used == 2 ? BOLD : ITALIC;
            List<Node> between = nodes.subList(openerIndex + 1, closerIndex);
            StyledNode styled = new StyledNode(style, new ArrayList<>(between));
            between.clear();
            nodes.add(openerIndex + 1, styled);
            closerIndex = openerIndex + 2;
            opener.count -= used;
            closer.count -= used;
            if (opener.count == 0) {
                nodes.remove(openerIndex);
                closerIndex--;
            }
            if (closer.count == 0) {
                nodes.remove(closerIndex);
            }
        }
    }

    private static boolean canPair(DelimiterNode opener, DelimiterNode closer) {
        if (opener.character == '~') {
            return true;
        }
        // CommonMark's "rule of 3" for runs that can both open and close.
        boolean ambiguous = opener.canClose || closer.canOpen;
        int sum = opener.originalCount + closer.originalCount;
        return !(ambiguous && sum % 3 == 0 && !(opener.originalCount % 3 == 0 && closer.originalCount % 3 == 0));
    }

    private static void flatten(List<Node> nodes, Inline.Style style, InlineListBuilder builder) {
        for (Node node : nodes) {
            if (node instanceof TextNode t) {
                builder.text(t.text, style);
            } else if (node instanceof DelimiterNode d) {
                builder.text(String.valueOf(d.character).repeat(d.count), style);
            } else if (node instanceof CodeNode c) {
                builder.text(c.code, style.withCode());
            } else if (node instanceof MathNode m) {
                builder.add(new Inline.Equation(m.expression));
            } else if (node instanceof LinkNode l) {
                flatten(l.children, style.withLink(l.url), builder);
            } else if (node instanceof StyledNode s) {
                flatten(s.children, style.merge(s.style), builder);
            }
        }
    }

    private abstract static class Node {}

    private static final class TextNode extends Node {
        final String text;

        TextNode(String text) {
            this.text = text;
        }
    }

    private static final class CodeNode extends Node {
        final String code;

        CodeNode(String code) {
            this.code = code;
        }
    }

    private static final class MathNode extends Node {
        final String expression;

        MathNode(String expression) {
            this.expression = expression;
        }
    }

    private static final class LinkNode extends Node {
        final List<Node> children;
        final String url;

        LinkNode(List<Node> children, String url) {
            this.children = children;
            this.url = url;
        }
    }

    private static final class StyledNode extends Node {
        final Inline.Style style;
        final List<Node> children;

        StyledNode(Inline.Style style, List<Node> children) {
            this.style = style;
            this.children = children;
        }
    }

    private static final class DelimiterNode extends Node {
        final char character;
        final int originalCount;
        final boolean canOpen;
        final boolean canClose;
        int count;

        DelimiterNode(char character, int count, boolean canOpen, boolean canClose) {
            this.character = character;
            this.originalCount = count;
            this.count = count;
            this.canOpen = canOpen;
            this.canClose = canClose;
        }
    }
}
