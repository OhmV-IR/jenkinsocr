package io.ohmvir.plugins.jenkinsocr.notes.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates inline content, merging adjacent text runs that share a style and dropping empty ones.
 */
final class InlineListBuilder {
    private final List<Inline> inlines = new ArrayList<>();

    void text(String text, Inline.Style style) {
        if (text.isEmpty()) {
            return;
        }
        if (!inlines.isEmpty() && inlines.get(inlines.size() - 1) instanceof Inline.Text last) {
            if (last.style().equals(style)) {
                inlines.set(inlines.size() - 1, new Inline.Text(last.text() + text, style));
                return;
            }
        }
        inlines.add(new Inline.Text(text, style));
    }

    void add(Inline inline) {
        if (inline instanceof Inline.Text text) {
            text(text.text(), text.style());
        } else {
            inlines.add(inline);
        }
    }

    void addAll(List<Inline> content) {
        content.forEach(this::add);
    }

    boolean isEmpty() {
        return inlines.isEmpty();
    }

    /**
     * The last character of text content, or {@code 0} if the content is empty or ends with an equation.
     */
    char lastChar() {
        if (!inlines.isEmpty() && inlines.get(inlines.size() - 1) instanceof Inline.Text last) {
            return last.text().charAt(last.text().length() - 1);
        }
        return 0;
    }

    /**
     * Removes trailing spaces and tabs from the end of the content.
     */
    void trimTrailingSpaces() {
        while (!inlines.isEmpty() && inlines.get(inlines.size() - 1) instanceof Inline.Text last) {
            String trimmed = stripTrailing(last.text());
            if (trimmed.isEmpty()) {
                inlines.remove(inlines.size() - 1);
                continue;
            }
            inlines.set(inlines.size() - 1, new Inline.Text(trimmed, last.style()));
            return;
        }
    }

    List<Inline> build() {
        return trim(inlines);
    }

    /**
     * Removes leading and trailing whitespace (including line breaks) from a list of inlines.
     */
    static List<Inline> trim(List<Inline> content) {
        List<Inline> result = new ArrayList<>(content);
        while (!result.isEmpty() && result.get(0) instanceof Inline.Text first) {
            String stripped = first.text().stripLeading();
            if (!stripped.isEmpty()) {
                result.set(0, new Inline.Text(stripped, first.style()));
                break;
            }
            result.remove(0);
        }
        while (!result.isEmpty() && result.get(result.size() - 1) instanceof Inline.Text last) {
            String stripped = last.text().stripTrailing();
            if (!stripped.isEmpty()) {
                result.set(result.size() - 1, new Inline.Text(stripped, last.style()));
                break;
            }
            result.remove(result.size() - 1);
        }
        return List.copyOf(result);
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        return text.substring(0, end);
    }
}
