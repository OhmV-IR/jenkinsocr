package io.ohmvir.plugins.jenkinsocr.notes.content;

import java.util.Objects;

/**
 * An inline element of a block's content.
 */
public sealed interface Inline {

    /**
     * A run of text sharing one {@link Style}. A {@code '\n'} inside the text is a line break.
     */
    record Text(String text, Style style) implements Inline {
        public Text {
            Objects.requireNonNull(text, "text");
            style = style == null ? Style.PLAIN : style;
        }

        public Text(String text) {
            this(text, Style.PLAIN);
        }
    }

    /**
     * An inline equation, written in TeX math syntax without surrounding delimiters.
     */
    record Equation(String expression) implements Inline {
        public Equation {
            Objects.requireNonNull(expression, "expression");
        }
    }

    /**
     * Character formatting of a {@link Text} run.
     *
     * @param link the URL the text links to, or {@code null}
     */
    record Style(boolean bold, boolean italic, boolean strikethrough, boolean underline, boolean code, String link) {
        public static final Style PLAIN = new Style(false, false, false, false, false, null);

        public Style withBold() {
            return new Style(true, italic, strikethrough, underline, code, link);
        }

        public Style withItalic() {
            return new Style(bold, true, strikethrough, underline, code, link);
        }

        public Style withStrikethrough() {
            return new Style(bold, italic, true, underline, code, link);
        }

        public Style withUnderline() {
            return new Style(bold, italic, strikethrough, true, code, link);
        }

        public Style withCode() {
            return new Style(bold, italic, strikethrough, underline, true, link);
        }

        public Style withLink(String url) {
            return new Style(bold, italic, strikethrough, underline, code, url);
        }

        /**
         * Combines two styles: a flag is set if it is set in either style, and {@code other}'s link wins.
         */
        public Style merge(Style other) {
            return new Style(
                    bold || other.bold,
                    italic || other.italic,
                    strikethrough || other.strikethrough,
                    underline || other.underline,
                    code || other.code,
                    other.link != null ? other.link : link);
        }
    }
}
