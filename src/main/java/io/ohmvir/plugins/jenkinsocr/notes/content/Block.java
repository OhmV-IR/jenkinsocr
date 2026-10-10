package io.ohmvir.plugins.jenkinsocr.notes.content;

import java.util.List;

/**
 * A block-level element of a {@link NoteDocument}. Every note provider renders these into its own native format.
 */
public sealed interface Block {

    /**
     * A section heading.
     *
     * @param level   1 for the most important heading; renderers clamp it to the levels they support
     * @param content the heading text
     */
    record Heading(int level, List<Inline> content) implements Block {
        public Heading {
            level = Math.max(1, level);
            content = List.copyOf(content);
        }
    }

    /**
     * A paragraph of text. Line breaks inside the paragraph are kept as {@code '\n'} characters in its text.
     */
    record Paragraph(List<Inline> content) implements Block {
        public Paragraph {
            content = List.copyOf(content);
        }
    }

    /**
     * One item of a bulleted or numbered list. Consecutive items with the same {@code ordered} flag form one list.
     *
     * @param ordered  {@code true} for a numbered list item
     * @param content  the first paragraph of the item
     * @param children nested blocks, such as sub-lists or further paragraphs
     */
    record ListItem(boolean ordered, List<Inline> content, List<Block> children) implements Block {
        public ListItem {
            content = List.copyOf(content);
            children = List.copyOf(children);
        }
    }

    /**
     * A block quote, used for side notes and margin annotations.
     */
    record Quote(List<Block> children) implements Block {
        public Quote {
            children = List.copyOf(children);
        }
    }

    /**
     * Preformatted source code or other verbatim text.
     *
     * @param language the language named by the source (e.g. {@code "python"}), or an empty string if unknown
     */
    record Code(String language, String code) implements Block {
        public Code {
            language = language == null ? "" : language;
        }
    }

    /**
     * A display (block) equation, written in TeX math syntax without surrounding delimiters.
     */
    record Equation(String expression) implements Block {}

    /**
     * A horizontal rule.
     */
    record Divider() implements Block {}
}
