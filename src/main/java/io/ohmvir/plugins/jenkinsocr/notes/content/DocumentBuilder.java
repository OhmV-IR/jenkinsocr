package io.ohmvir.plugins.jenkinsocr.notes.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Builds a {@link NoteDocument} from a stream of events, for parsers whose input does not map line by line onto
 * blocks (such as LaTeX). Text is collected into the current paragraph; blocks go into the innermost open
 * container (the document, a quote, or a list item).
 */
final class DocumentBuilder {
    private final Deque<Container> containers = new ArrayDeque<>();
    private InlineListBuilder paragraph = new InlineListBuilder();
    private Inline.Style style = Inline.Style.PLAIN;

    DocumentBuilder() {
        containers.push(new Container(Kind.ROOT, false));
    }

    Inline.Style style() {
        return style;
    }

    void setStyle(Inline.Style style) {
        this.style = style;
    }

    /**
     * Appends text to the current paragraph. A leading space is dropped at the start of a paragraph or line, and
     * after another space, so that callers can emit one space per whitespace run.
     */
    void text(String text) {
        String value = text;
        char last = paragraph.lastChar();
        if (paragraph.isEmpty() || last == ' ' || last == '\n') {
            value = value.replaceFirst("^ +", "");
        }
        paragraph.text(value, style);
    }

    void lineBreak() {
        if (!paragraph.isEmpty()) {
            paragraph.trimTrailingSpaces();
            paragraph.text("\n", style);
        }
    }

    void inlineEquation(String expression) {
        if (!expression.isBlank()) {
            paragraph.add(new Inline.Equation(expression.strip()));
        }
    }

    void paragraphBreak() {
        flushParagraph();
    }

    void addBlock(Block block) {
        flushParagraph();
        append(block);
    }

    void beginList(boolean ordered) {
        flushParagraph();
        containers.push(new Container(Kind.LIST, ordered));
    }

    /**
     * Starts a new list item; outside of a list this only ends the current paragraph.
     */
    void item() {
        flushParagraph();
        if (containers.peek().kind == Kind.ITEM) {
            close();
        }
        Container top = containers.peek();
        if (top.kind == Kind.LIST) {
            containers.push(new Container(Kind.ITEM, top.ordered));
        }
    }

    void endList() {
        closeThrough(Kind.LIST);
    }

    void beginQuote() {
        flushParagraph();
        containers.push(new Container(Kind.QUOTE, false));
    }

    void endQuote() {
        closeThrough(Kind.QUOTE);
    }

    /**
     * Runs {@code action} and returns the inline content it produced instead of adding it to the current
     * paragraph.
     */
    List<Inline> capture(Runnable action) {
        InlineListBuilder saved = paragraph;
        Inline.Style savedStyle = style;
        paragraph = new InlineListBuilder();
        try {
            action.run();
            return paragraph.build();
        } finally {
            paragraph = saved;
            style = savedStyle;
        }
    }

    NoteDocument build(List<String> warnings) {
        flushParagraph();
        while (containers.size() > 1) {
            close();
        }
        return new NoteDocument(containers.peek().blocks, warnings);
    }

    private void flushParagraph() {
        List<Inline> content = paragraph.build();
        paragraph = new InlineListBuilder();
        if (!content.isEmpty()) {
            append(new Block.Paragraph(content));
        }
    }

    private void append(Block block) {
        Container top = containers.peek();
        if (top.kind == Kind.LIST) {
            // Content before the first \item: give it an item of its own rather than dropping it.
            top = new Container(Kind.ITEM, top.ordered);
            containers.push(top);
        }
        if (top.kind == Kind.ITEM
                && top.itemContent == null
                && top.blocks.isEmpty()
                && block instanceof Block.Paragraph p) {
            top.itemContent = p.content();
            return;
        }
        top.blocks.add(block);
    }

    private void closeThrough(Kind kind) {
        flushParagraph();
        if (containers.stream().noneMatch(c -> c.kind == kind)) {
            return;
        }
        while (true) {
            Kind closed = containers.peek().kind;
            close();
            if (closed == kind) {
                return;
            }
        }
    }

    private void close() {
        Container container = containers.pop();
        switch (container.kind) {
            case ITEM -> {
                List<Inline> content = container.itemContent == null ? List.of() : container.itemContent;
                containers.peek().blocks.add(new Block.ListItem(container.ordered, content, container.blocks));
            }
            case LIST -> container.blocks.forEach(this::append);
            case QUOTE -> append(new Block.Quote(container.blocks));
            case ROOT -> throw new IllegalStateException("The document itself cannot be closed");
        }
    }

    private enum Kind {
        ROOT,
        QUOTE,
        LIST,
        ITEM
    }

    private static final class Container {
        final Kind kind;
        final boolean ordered;
        final List<Block> blocks = new ArrayList<>();
        List<Inline> itemContent;

        Container(Kind kind, boolean ordered) {
            this.kind = kind;
            this.ordered = ordered;
        }
    }
}
