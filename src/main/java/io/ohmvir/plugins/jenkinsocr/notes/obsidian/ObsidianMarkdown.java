package io.ohmvir.plugins.jenkinsocr.notes.obsidian;

import io.ohmvir.plugins.jenkinsocr.notes.content.Block;
import io.ohmvir.plugins.jenkinsocr.notes.content.Inline;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces the Markdown that Obsidian stores and renders. Obsidian renders TeX math written as {@code $...$} and
 * {@code $$...$$} with MathJax, so formulas display as equations.
 */
final class ObsidianMarkdown {
    private static final Pattern FENCE_OPENING = Pattern.compile("^ {0,3}(`{3,}|~{3,})");
    private static final Pattern ORDERED_MARKER = Pattern.compile("^(\\d{1,9})([.)])");

    private ObsidianMarkdown() {}

    /**
     * Rewrites {@code \(...\)} and {@code \[...\]} formulas, which Obsidian does not recognise, to {@code $...$} and
     * {@code $$...$$}. Code blocks and code spans are left untouched.
     */
    static String normalizeMathDelimiters(String markdown) {
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (i == 0 || text.charAt(i - 1) == '\n') {
                Matcher fence = FENCE_OPENING.matcher(text).region(i, text.length());
                if (fence.lookingAt()) {
                    int end = endOfFencedBlock(text, i, fence.group(1));
                    out.append(text, i, end);
                    i = end;
                    continue;
                }
            }
            char c = text.charAt(i);
            if (c == '`') {
                int end = endOfCodeSpan(text, i);
                out.append(text, i, end);
                i = end;
            } else if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                int close = next == '(' || next == '[' ? text.indexOf(next == '(' ? "\\)" : "\\]", i + 2) : -1;
                if (close >= 0) {
                    String delimiter = next == '(' ? "$" : "$$";
                    out.append(delimiter)
                            .append(text.substring(i + 2, close).strip())
                            .append(delimiter);
                    i = close + 2;
                } else {
                    out.append(c).append(next);
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int endOfFencedBlock(String text, int start, String fence) {
        Pattern closing = Pattern.compile(
                "^ {0,3}" + Pattern.quote(fence.substring(0, 1)) + "{" + fence.length() + ",}[ \\t]*$",
                Pattern.MULTILINE);
        int firstLineEnd = text.indexOf('\n', start);
        if (firstLineEnd < 0) {
            return text.length();
        }
        Matcher matcher = closing.matcher(text);
        if (!matcher.find(firstLineEnd + 1)) {
            return text.length();
        }
        return matcher.end();
    }

    private static int endOfCodeSpan(String text, int start) {
        int run = 0;
        while (start + run < text.length() && text.charAt(start + run) == '`') {
            run++;
        }
        String delimiter = "`".repeat(run);
        int search = start + run;
        while (true) {
            int candidate = text.indexOf(delimiter, search);
            if (candidate < 0) {
                return start + run;
            }
            int end = candidate + run;
            if (end >= text.length() || text.charAt(end) != '`') {
                return end;
            }
            while (end < text.length() && text.charAt(end) == '`') {
                end++;
            }
            search = end;
        }
    }

    /**
     * Renders a document as Markdown.
     */
    static String render(NoteDocument document) {
        return renderBlocks(document.blocks());
    }

    private static String renderBlocks(List<Block> blocks) {
        StringBuilder out = new StringBuilder();
        Block previous = null;
        int number = 0;
        for (Block block : blocks) {
            boolean sameList = previous instanceof Block.ListItem p
                    && block instanceof Block.ListItem c
                    && p.ordered() == c.ordered();
            if (previous != null) {
                out.append(sameList ? "\n" : "\n\n");
            }
            number = sameList ? number + 1 : 1;
            out.append(renderBlock(block, number));
            previous = block;
        }
        return out.toString();
    }

    private static String renderBlock(Block block, int number) {
        if (block instanceof Block.Heading heading) {
            String text = renderInlines(heading.content()).replace('\n', ' ');
            return "#".repeat(Math.min(heading.level(), 6)) + " " + text;
        }
        if (block instanceof Block.Paragraph paragraph) {
            return renderInlines(paragraph.content());
        }
        if (block instanceof Block.ListItem item) {
            String marker = item.ordered() ? number + ". " : "- ";
            String indent = " ".repeat(marker.length());
            String content = renderInlines(item.content());
            StringBuilder out = new StringBuilder();
            if (content.isEmpty()) {
                out.append(marker.strip());
            } else {
                out.append(marker).append(content.replace("\n", "\n" + indent));
            }
            if (!item.children().isEmpty()) {
                // A sub-list directly follows the item's text; any other block needs a blank line, or it would be
                // read as a continuation of that text.
                boolean subList = item.children().get(0) instanceof Block.ListItem;
                out.append(subList || content.isEmpty() ? "\n" : "\n\n")
                        .append(indentLines(renderBlocks(item.children()), indent));
            }
            return out.toString();
        }
        if (block instanceof Block.Quote quote) {
            return prefixLines(renderBlocks(quote.children()), ">");
        }
        if (block instanceof Block.Code code) {
            String fence = "`".repeat(Math.max(3, longestRun(code.code(), '`') + 1));
            return fence + code.language() + "\n" + code.code() + "\n" + fence;
        }
        if (block instanceof Block.Equation equation) {
            return "$$\n" + equation.expression() + "\n$$";
        }
        if (block instanceof Block.Divider) {
            return "---";
        }
        throw new IllegalArgumentException(
                "Unknown block type: " + block.getClass().getName());
    }

    private static String indentLines(String text, String indent) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            if (!line.isEmpty()) {
                out.append(indent).append(line);
            }
        }
        return out.toString();
    }

    private static String prefixLines(String text, String prefix) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(prefix);
            if (!line.isEmpty()) {
                out.append(' ').append(line);
            }
        }
        return out.toString();
    }

    /**
     * Renders inline content. Line breaks are kept as single newlines, which Obsidian displays as line breaks.
     */
    static String renderInlines(List<Inline> content) {
        StringBuilder out = new StringBuilder();
        for (Inline inline : content) {
            if (inline instanceof Inline.Text text) {
                out.append(renderText(text));
            } else if (inline instanceof Inline.Equation equation) {
                out.append('$').append(equation.expression().replace('\n', ' ')).append('$');
            }
        }
        List<String> lines = new ArrayList<>();
        for (String line : out.toString().split("\n", -1)) {
            lines.add(escapeLineStart(line));
        }
        return String.join("\n", lines);
    }

    private static String renderText(Inline.Text text) {
        Inline.Style style = text.style();
        String raw = text.text();
        int start = 0;
        int end = raw.length();
        while (start < end && Character.isWhitespace(raw.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(raw.charAt(end - 1))) {
            end--;
        }
        if (start == end) {
            return raw;
        }
        // Delimiters must touch the text they wrap, so surrounding whitespace stays outside of them.
        String core = style.code() ? codeSpan(raw.substring(start, end)) : escape(raw.substring(start, end));
        if (style.strikethrough()) {
            core = "~~" + core + "~~";
        }
        if (style.italic()) {
            core = "*" + core + "*";
        }
        if (style.bold()) {
            core = "**" + core + "**";
        }
        if (style.underline()) {
            core = "<u>" + core + "</u>";
        }
        if (style.link() != null) {
            core = "[" + core + "](" + linkDestination(style.link()) + ")";
        }
        return raw.substring(0, start) + core + raw.substring(end);
    }

    private static String codeSpan(String code) {
        String content = code.replace('\n', ' ');
        String fence = "`".repeat(longestRun(content, '`') + 1);
        boolean pad = content.startsWith("`") || content.endsWith("`");
        return fence + (pad ? " " + content + " " : content) + fence;
    }

    private static String linkDestination(String url) {
        String encoded = url.strip().replace(" ", "%20");
        return encoded.contains("(") || encoded.contains(")") ? "<" + encoded + ">" : encoded;
    }

    /**
     * Escapes characters that Markdown or Obsidian would interpret: emphasis, code, links, HTML, math, tags
     * ({@code #tag}), tables, and the doubled characters of highlights ({@code ==}), strikethrough ({@code ~~}) and
     * comments ({@code %%}).
     */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean doubled = i + 1 < text.length() && text.charAt(i + 1) == c;
            switch (c) {
                // ']' needs no escape once '[' is escaped, and an escaped pair would read as \[...\] math
                case '\\', '`', '*', '_', '[', '<', '>', '#', '$', '|', '&' ->
                    out.append('\\').append(c);
                case '=', '~', '%' -> {
                    if (doubled) {
                        out.append('\\');
                    }
                    out.append(c);
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Prevents a line of text from being read as a list item, and keeps its indentation without turning it into
     * an indented code block.
     */
    private static String escapeLineStart(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        String rest = line.substring(indent);
        if (rest.startsWith("-") || rest.startsWith("+")) {
            rest = "\\" + rest;
        } else {
            Matcher ordered = ORDERED_MARKER.matcher(rest);
            if (ordered.lookingAt()) {
                rest = ordered.group(1) + "\\" + rest.substring(ordered.group(1).length());
            }
        }
        return " ".repeat(indent) + rest;
    }

    private static int longestRun(String text, char c) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            current = text.charAt(i) == c ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return longest;
    }
}
