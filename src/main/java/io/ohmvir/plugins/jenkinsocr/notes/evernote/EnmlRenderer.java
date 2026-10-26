package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import io.ohmvir.plugins.jenkinsocr.notes.content.Block;
import io.ohmvir.plugins.jenkinsocr.notes.content.Inline;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteDocument;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders a {@link NoteDocument} as ENML, the XHTML subset Evernote stores notes in.
 *
 * <p>Evernote cannot render TeX, so formulas are kept as their TeX source in monospace: inline formulas as
 * {@code <code>$...$</code>} and display formulas as preformatted {@code $$...$$} blocks.
 */
final class EnmlRenderer {
    /** Evernote's maximum note content size, in bytes. */
    static final int MAX_CONTENT_LENGTH = 5 * 1024 * 1024;

    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE en-note SYSTEM \"http://xml.evernote.com/pub/enml2.dtd\">\n";
    private static final String END_TAG = "</en-note>";
    private static final Pattern SUPPORTED_LINK = Pattern.compile("^(?i)(?:https?://|mailto:)\\S+$");

    private EnmlRenderer() {}

    /**
     * A complete ENML document with the given body.
     */
    static String document(String body) {
        return HEADER + "<en-note>" + body + END_TAG;
    }

    /**
     * Appends a body to an existing ENML document, separated by a horizontal rule.
     */
    static String append(String existingDocument, String body) throws IOException {
        int end = existingDocument.lastIndexOf(END_TAG);
        if (end < 0) {
            throw new IOException("The existing Evernote note is not valid ENML: it has no " + END_TAG + " tag");
        }
        return existingDocument.substring(0, end) + "<hr/>" + body + existingDocument.substring(end);
    }

    /**
     * Renders the document's blocks as an ENML fragment.
     */
    static String render(NoteDocument document) {
        StringBuilder out = new StringBuilder();
        renderBlocks(document.blocks(), out);
        return out.toString();
    }

    private static void renderBlocks(List<Block> blocks, StringBuilder out) {
        int i = 0;
        while (i < blocks.size()) {
            Block block = blocks.get(i);
            if (block instanceof Block.ListItem first) {
                String tag = first.ordered() ? "ol" : "ul";
                out.append('<').append(tag).append('>');
                while (i < blocks.size()
                        && blocks.get(i) instanceof Block.ListItem item
                        && item.ordered() == first.ordered()) {
                    out.append("<li>");
                    renderInlines(item.content(), out);
                    renderBlocks(item.children(), out);
                    out.append("</li>");
                    i++;
                }
                out.append("</").append(tag).append('>');
                continue;
            }
            renderBlock(block, out);
            i++;
        }
    }

    private static void renderBlock(Block block, StringBuilder out) {
        if (block instanceof Block.Heading heading) {
            int level = Math.min(heading.level(), 6);
            out.append("<h").append(level).append('>');
            renderInlines(heading.content(), out);
            out.append("</h").append(level).append('>');
        } else if (block instanceof Block.Paragraph paragraph) {
            out.append("<p>");
            renderInlines(paragraph.content(), out);
            out.append("</p>");
        } else if (block instanceof Block.Quote quote) {
            out.append("<blockquote>");
            renderBlocks(quote.children(), out);
            out.append("</blockquote>");
        } else if (block instanceof Block.Code code) {
            out.append("<pre>").append(escape(code.code())).append("</pre>");
        } else if (block instanceof Block.Equation equation) {
            out.append("<pre>")
                    .append(escape("$$" + equation.expression() + "$$"))
                    .append("</pre>");
        } else if (block instanceof Block.Divider) {
            out.append("<hr/>");
        } else {
            throw new IllegalArgumentException(
                    "Unknown block type: " + block.getClass().getName());
        }
    }

    private static void renderInlines(List<Inline> content, StringBuilder out) {
        for (Inline inline : content) {
            if (inline instanceof Inline.Text text) {
                renderText(text, out);
            } else if (inline instanceof Inline.Equation equation) {
                out.append("<code>")
                        .append(escape("$" + equation.expression() + "$"))
                        .append("</code>");
            }
        }
    }

    private static void renderText(Inline.Text text, StringBuilder out) {
        Inline.Style style = text.style();
        String link = style.link() == null ? null : style.link().strip();
        boolean linked = link != null && SUPPORTED_LINK.matcher(link).matches();
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        if (linked) {
            open.append("<a href=\"").append(escape(link)).append("\">");
            close.insert(0, "</a>");
        }
        wrap(style.bold(), "b", open, close);
        wrap(style.italic(), "i", open, close);
        wrap(style.underline(), "u", open, close);
        wrap(style.strikethrough(), "s", open, close);
        wrap(style.code(), "code", open, close);
        out.append(open).append(escape(text.text()).replace("\n", "<br/>")).append(close);
    }

    private static void wrap(boolean enabled, String tag, StringBuilder open, StringBuilder close) {
        if (enabled) {
            open.append('<').append(tag).append('>');
            close.insert(0, "</" + tag + ">");
        }
    }

    /**
     * Escapes text for XML and drops the characters XML 1.0 does not allow.
     */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    boolean allowed = c == 0x9
                            || c == 0xA
                            || c == 0xD
                            || (c >= 0x20 && c <= 0xD7FF)
                            || (c >= 0xE000 && c <= 0xFFFD)
                            || c >= 0x10000;
                    if (allowed) {
                        out.appendCodePoint(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
