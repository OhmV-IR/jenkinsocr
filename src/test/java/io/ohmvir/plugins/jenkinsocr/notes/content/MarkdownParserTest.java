package io.ohmvir.plugins.jenkinsocr.notes.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownParserTest {
    private static final Inline.Style BOLD = Inline.Style.PLAIN.withBold();
    private static final Inline.Style ITALIC = Inline.Style.PLAIN.withItalic();

    private static List<Block> parse(String markdown) {
        return NoteContentParser.parse(markdown, FormulaOutputType.KATEX).blocks();
    }

    private static Inline.Text text(String text) {
        return new Inline.Text(text);
    }

    private static Inline.Text text(String text, Inline.Style style) {
        return new Inline.Text(text, style);
    }

    private static Inline.Equation eq(String expression) {
        return new Inline.Equation(expression);
    }

    private static Block.Paragraph paragraph(Inline... content) {
        return new Block.Paragraph(List.of(content));
    }

    @Test
    void headingsParagraphsAndDisplayMath() {
        List<Block> blocks = parse("""
                # Derivatives

                The derivative of $f(x) = x^2$ is:

                $$
                f'(x) = \\lim_{h \\to 0} \\frac{f(x+h) - f(x)}{h}
                $$

                ## Rules
                Setext Title
                ===
                """);
        assertEquals(
                List.of(
                        new Block.Heading(1, List.of(text("Derivatives"))),
                        paragraph(text("The derivative of "), eq("f(x) = x^2"), text(" is:")),
                        new Block.Equation("f'(x) = \\lim_{h \\to 0} \\frac{f(x+h) - f(x)}{h}"),
                        new Block.Heading(2, List.of(text("Rules"))),
                        new Block.Heading(1, List.of(text("Setext Title")))),
                blocks);
    }

    @Test
    void singleLineDisplayMathAndBracketDelimiters() {
        assertEquals(List.of(new Block.Equation("a^2 + b^2 = c^2")), parse("$$a^2 + b^2 = c^2$$"));
        assertEquals(List.of(new Block.Equation("E = mc^2")), parse("\\[ E = mc^2 \\]"));
        assertEquals(List.of(paragraph(text("Inline "), eq("x_1"), text(" here"))), parse("Inline \\(x_1\\) here"));
    }

    @Test
    void displayMathInterruptsParagraphAndKeepsTrailingText() {
        assertEquals(
                List.of(paragraph(text("Before")), new Block.Equation("x = 1"), paragraph(text("after"))),
                parse("Before\n$$x = 1$$ after"));
    }

    @Test
    void mathIsNotParsedAsMarkdown() {
        // Underscores and asterisks inside formulas must not become emphasis.
        assertEquals(
                List.of(paragraph(eq("a_1 * b_2"), text(" and "), eq("c_3 * d_4"))),
                parse("$a_1 * b_2$ and $c_3 * d_4$"));
        assertEquals(
                List.of(new Block.Equation("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}")),
                parse("$$\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}$$"));
    }

    @Test
    void dollarSignsThatAreNotMathStayLiteral() {
        assertEquals(List.of(paragraph(text("It costs $5 and $10 today"))), parse("It costs $5 and $10 today"));
        assertEquals(List.of(paragraph(text("Price: $ 20"))), parse("Price: $ 20"));
        assertEquals(List.of(paragraph(text("escaped $x$"))), parse("escaped \\$x\\$"));
    }

    @Test
    void emphasisFollowsCommonMarkRules() {
        assertEquals(
                List.of(paragraph(
                        text("bold", BOLD),
                        text(" and "),
                        text("italic", ITALIC),
                        text(" and "),
                        text("both", BOLD.withItalic()),
                        text(" and "),
                        text("struck", Inline.Style.PLAIN.withStrikethrough()))),
                parse("**bold** and *italic* and ***both*** and ~~struck~~"));
        assertEquals(List.of(paragraph(text("snake_case_name and 2 * 3 * 4"))), parse("snake_case_name and 2 * 3 * 4"));
        assertEquals(List.of(paragraph(text("a "), text("b", ITALIC), text(" c"))), parse("a _b_ c"));
    }

    @Test
    void codeSpansAndLinks() {
        assertEquals(
                List.of(paragraph(
                        text("Use "),
                        text("x * y", Inline.Style.PLAIN.withCode()),
                        text(" or see "),
                        text("the docs", Inline.Style.PLAIN.withLink("https://example.com/a_(b)")),
                        text(" and "),
                        text("https://x.org", Inline.Style.PLAIN.withLink("https://x.org")))),
                parse("Use `x * y` or see [the docs](https://example.com/a_(b)) and <https://x.org>"));
        assertEquals(
                List.of(paragraph(text("bold link", BOLD.withLink("https://e.com")))),
                parse("**[bold link](https://e.com)**"));
    }

    @Test
    void lineBreaksInsideParagraphsArePreserved() {
        assertEquals(
                List.of(paragraph(text("line one\nline two\nline three"))),
                parse("line one\nline two  \nline three\\"));
    }

    @Test
    void nestedListsWithLenientIndentation() {
        List<Block> blocks = parse("""
                1. First step
                  - detail a
                  - detail b
                2. Second step

                   continued paragraph
                - bullet
                """);
        Block.ListItem first = new Block.ListItem(
                true,
                List.of(text("First step")),
                List.of(
                        new Block.ListItem(false, List.of(text("detail a")), List.of()),
                        new Block.ListItem(false, List.of(text("detail b")), List.of())));
        Block.ListItem second =
                new Block.ListItem(true, List.of(text("Second step")), List.of(paragraph(text("continued paragraph"))));
        assertEquals(List.of(first, second, new Block.ListItem(false, List.of(text("bullet")), List.of())), blocks);
    }

    @Test
    void listItemsCanHoldMathAndCode() {
        List<Block> blocks = parse("- Formula:\n  $$x^2$$\n- ```\n  code\n  ```");
        assertEquals(
                List.of(
                        new Block.ListItem(false, List.of(text("Formula:")), List.of(new Block.Equation("x^2"))),
                        new Block.ListItem(false, List.of(), List.of(new Block.Code("", "code")))),
                blocks);
    }

    @Test
    void quotesCodeFencesAndDividers() {
        List<Block> blocks = parse("""
                > Margin Note: check $x$
                > again

                ```python
                def f(x):
                    return x * 2
                ```

                ---

                ```math
                \\int_0^1 x\\,dx
                ```
                """);
        assertEquals(
                List.of(
                        new Block.Quote(List.of(paragraph(text("Margin Note: check "), eq("x"), text("\nagain")))),
                        new Block.Code("python", "def f(x):\n    return x * 2"),
                        new Block.Divider(),
                        new Block.Equation("\\int_0^1 x\\,dx")),
                blocks);
    }

    @Test
    void unclosedDisplayMathIsReported() {
        NoteDocument document = NoteContentParser.parse("$$\nx = 1", FormulaOutputType.KATEX);
        assertEquals(List.of(new Block.Equation("x = 1")), document.blocks());
        assertTrue(document.warnings().get(0).contains("not closed"));
    }

    @Test
    void plainTextKeepsEverythingLiteral() {
        NoteDocument document = NoteContentParser.parse(
                "# not a heading\n*not bold* $5\n\n\n  indented line", FormulaOutputType.PURE_TEXT);
        assertEquals(
                List.of(paragraph(text("# not a heading\n*not bold* $5")), paragraph(text("  indented line"))),
                document.blocks());
    }
}
