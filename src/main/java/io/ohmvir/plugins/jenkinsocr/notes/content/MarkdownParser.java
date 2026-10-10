package io.ohmvir.plugins.jenkinsocr.notes.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Markdown with TeX math (the {@link io.ohmvir.plugins.jenkinsocr.FormulaOutputType#KATEX} output) into a
 * {@link NoteDocument}.
 *
 * <p>Supported blocks: ATX and setext headings, paragraphs, bulleted and numbered lists (nesting is detected
 * leniently, since models often indent sub-items by two spaces), block quotes, fenced code blocks (a {@code math}
 * fence is treated as an equation), thematic breaks and display math delimited by {@code $$...$$} or
 * {@code \[...\]}. Anything else, such as tables, is kept as paragraph text. Every line break inside a paragraph is
 * preserved because handwritten notes are line-oriented.
 */
final class MarkdownParser {
    private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})\\s*([^`\\s]*)[^`]*$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$");
    private static final Pattern SETEXT_UNDERLINE = Pattern.compile("^ {0,3}(=+|-+)[ \\t]*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^( *)([-*+]|\\d{1,9}[.)])([ \\t]+|$)(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}> ?(.*)$");

    private final List<String> warnings = new ArrayList<>();

    private MarkdownParser() {}

    static NoteDocument parse(String markdown) {
        MarkdownParser parser = new MarkdownParser();
        List<Block> blocks = parser.parseBlocks(splitLines(markdown));
        return new NoteDocument(blocks, parser.warnings);
    }

    static List<String> splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            lines.add(expandLeadingTabs(line));
        }
        return lines;
    }

    private static String expandLeadingTabs(String line) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        for (; i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'); i++) {
            if (line.charAt(i) == '\t') {
                result.append(" ".repeat(4 - result.length() % 4));
            } else {
                result.append(' ');
            }
        }
        return result.append(line, i, line.length()).toString();
    }

    private List<Block> parseBlocks(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) {
                i++;
                continue;
            }
            Matcher m;
            if ((m = FENCE.matcher(line)).matches()) {
                i = parseFence(lines, i, m, blocks);
            } else if (isDisplayMathStart(line)) {
                i = parseDisplayMath(lines, i, blocks);
            } else if ((m = HEADING.matcher(line)).matches()) {
                String content = m.group(2) == null ? "" : m.group(2);
                blocks.add(new Block.Heading(m.group(1).length(), MarkdownInlineParser.parse(content)));
                i++;
            } else if (THEMATIC_BREAK.matcher(line).matches()) {
                blocks.add(new Block.Divider());
                i++;
            } else if (QUOTE.matcher(line).matches()) {
                i = parseQuote(lines, i, blocks);
            } else if ((m = LIST_ITEM.matcher(line)).matches()) {
                i = parseListItem(lines, i, m, blocks);
            } else {
                i = parseParagraph(lines, i, blocks);
            }
        }
        return blocks;
    }

    private int parseFence(List<String> lines, int start, Matcher opening, List<Block> blocks) {
        int indent = opening.group(1).length();
        String fence = opening.group(2);
        String language = opening.group(3).toLowerCase(Locale.ROOT);
        Pattern closing =
                Pattern.compile("^ {0,3}" + Pattern.quote(fence.substring(0, 1)) + "{" + fence.length() + ",}[ \\t]*$");
        List<String> content = new ArrayList<>();
        int i = start + 1;
        while (i < lines.size() && !closing.matcher(lines.get(i)).matches()) {
            content.add(stripIndent(lines.get(i), indent));
            i++;
        }
        String code = String.join("\n", content);
        if (language.equals("math")) {
            if (!code.isBlank()) {
                blocks.add(new Block.Equation(code.strip()));
            }
        } else {
            blocks.add(new Block.Code(language, code));
        }
        return Math.min(i + 1, lines.size());
    }

    private static boolean isDisplayMathStart(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("$$") || trimmed.startsWith("\\[");
    }

    private int parseDisplayMath(List<String> lines, int start, List<Block> blocks) {
        String first = lines.get(start).strip();
        String close = first.startsWith("$$") ? "$$" : "\\]";
        StringBuilder expression = new StringBuilder();
        String remainder = first.substring(2);
        int i = start;
        while (true) {
            int end = remainder.indexOf(close);
            if (end >= 0) {
                expression.append(remainder, 0, end);
                remainder = remainder.substring(end + close.length()).strip();
                break;
            }
            expression.append(remainder).append('\n');
            i++;
            if (i >= lines.size()) {
                warnings.add(
                        "A display equation was not closed with " + close + "; it was ended at the end of the note.");
                remainder = "";
                break;
            }
            remainder = lines.get(i);
        }
        String trimmed = expression.toString().strip();
        if (!trimmed.isEmpty()) {
            blocks.add(new Block.Equation(trimmed));
        }
        if (!remainder.isEmpty()) {
            // Text after the closing delimiter on the same line is parsed like a line of its own.
            List<String> rest = new ArrayList<>();
            rest.add(remainder);
            rest.addAll(lines.subList(i + 1, lines.size()));
            blocks.addAll(parseBlocks(rest));
            return lines.size();
        }
        return i + 1;
    }

    private int parseQuote(List<String> lines, int start, List<Block> blocks) {
        List<String> content = new ArrayList<>();
        int i = start;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher m = QUOTE.matcher(line);
            if (m.matches()) {
                content.add(m.group(1));
            } else if (!line.isBlank()
                    && !content.isEmpty()
                    && !content.get(content.size() - 1).isBlank()
                    && !startsBlock(line)) {
                content.add(line); // lazy continuation of a quoted paragraph
            } else {
                break;
            }
            i++;
        }
        blocks.add(new Block.Quote(parseBlocks(content)));
        return i;
    }

    private int parseListItem(List<String> lines, int start, Matcher marker, List<Block> blocks) {
        int markerIndent = marker.group(1).length();
        String bullet = marker.group(2);
        String spacing = marker.group(3);
        String firstLine = marker.group(4);
        int contentOffset;
        if (spacing.isEmpty() || spacing.length() > 4) {
            contentOffset = markerIndent + bullet.length() + 1;
            if (spacing.length() > 4) {
                firstLine = spacing.substring(1) + firstLine;
            }
        } else {
            contentOffset = markerIndent + bullet.length() + spacing.length();
        }
        List<String> content = new ArrayList<>();
        content.add(firstLine);
        int i = start + 1;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) {
                int next = i + 1;
                while (next < lines.size() && lines.get(next).isBlank()) {
                    next++;
                }
                if (next < lines.size() && indentOf(lines.get(next)) > markerIndent) {
                    content.add("");
                    i++;
                    continue;
                }
                break;
            }
            int indent = indentOf(line);
            if (indent > markerIndent) {
                content.add(stripIndent(line, Math.min(indent, contentOffset)));
            } else if (!content.get(content.size() - 1).isBlank() && !startsBlock(line)) {
                content.add(line.strip()); // lazy continuation of the item's paragraph
            } else {
                break;
            }
            i++;
        }
        List<Block> itemBlocks = parseBlocks(content);
        List<Inline> itemContent = List.of();
        if (!itemBlocks.isEmpty() && itemBlocks.get(0) instanceof Block.Paragraph paragraph) {
            itemContent = paragraph.content();
            itemBlocks = itemBlocks.subList(1, itemBlocks.size());
        }
        boolean ordered = Character.isDigit(bullet.charAt(0));
        blocks.add(new Block.ListItem(ordered, itemContent, itemBlocks));
        return i;
    }

    private int parseParagraph(List<String> lines, int start, List<Block> blocks) {
        List<String> content = new ArrayList<>();
        int i = start;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) {
                break;
            }
            if (!content.isEmpty()) {
                Matcher setext = SETEXT_UNDERLINE.matcher(line);
                if (setext.matches()) {
                    int level = setext.group(1).charAt(0) == '=' ? 1 : 2;
                    blocks.add(new Block.Heading(level, MarkdownInlineParser.parse(String.join("\n", content))));
                    return i + 1;
                }
                if (startsBlock(line)) {
                    break;
                }
            }
            content.add(stripHardBreakMarker(line.strip()));
            i++;
        }
        blocks.add(new Block.Paragraph(MarkdownInlineParser.parse(String.join("\n", content))));
        return i;
    }

    /**
     * Whether the line starts a block that interrupts a paragraph.
     */
    private static boolean startsBlock(String line) {
        return FENCE.matcher(line).matches()
                || isDisplayMathStart(line)
                || HEADING.matcher(line).matches()
                || THEMATIC_BREAK.matcher(line).matches()
                || QUOTE.matcher(line).matches()
                || startsNonEmptyListItem(line);
    }

    private static boolean startsNonEmptyListItem(String line) {
        Matcher m = LIST_ITEM.matcher(line);
        return m.matches() && !m.group(4).isBlank();
    }

    /**
     * Removes the trailing backslash that marks a hard line break; every line break is kept anyway.
     */
    private static String stripHardBreakMarker(String line) {
        if (line.endsWith("\\") && !line.endsWith("\\\\")) {
            return line.substring(0, line.length() - 1).stripTrailing();
        }
        return line;
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    private static String stripIndent(String line, int amount) {
        int strip = Math.min(amount, indentOf(line));
        return line.substring(strip);
    }
}
