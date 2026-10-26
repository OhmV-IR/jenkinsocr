package io.ohmvir.plugins.jenkinsocr.notes.obsidian;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import io.ohmvir.plugins.jenkinsocr.notes.content.Block;
import io.ohmvir.plugins.jenkinsocr.notes.content.Inline;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteContentParser;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObsidianMarkdownTest {

    private static String render(Block... blocks) {
        return ObsidianMarkdown.render(new NoteDocument(List.of(blocks), List.of()));
    }

    /** Rendering then parsing the Markdown again must give back the same document. */
    private static void assertRoundTrip(NoteDocument document) {
        String markdown = ObsidianMarkdown.render(document);
        assertEquals(
                document.blocks(),
                NoteContentParser.parse(markdown, FormulaOutputType.KATEX).blocks(),
                () -> "Markdown was:\n" + markdown);
    }

    @Test
    void latexDocumentsBecomeObsidianMarkdown() {
        NoteDocument document = NoteContentParser.parse("""
                \\section*{Derivatives}
                The derivative of \\(x^2\\) is \\textbf{important}:
                \\begin{align*}
                  f'(x) &= 2x
                \\end{align*}
                \\begin{enumerate}
                  \\item First \\emph{step}
                  \\begin{itemize}\\item nested\\end{itemize}
                  \\item Second
                \\end{enumerate}
                \\begin{quote}Margin note\\end{quote}
                """, FormulaOutputType.LATEX);
        assertEquals("""
                # Derivatives

                The derivative of $x^2$ is **important**:

                $$
                \\begin{aligned}
                  f'(x) &= 2x
                \\end{aligned}
                $$

                1. First *step*
                   - nested
                2. Second

                > Margin note""", ObsidianMarkdown.render(document));
        assertRoundTrip(document);
    }

    @Test
    void plainTextIsEscapedSoThatObsidianShowsItLiterally() {
        NoteDocument document = NoteContentParser.parse(
                "# not a heading #tag\n- not a list\n1. not ordered\n*a* _b_ `c` [d](e) <f> $5 == x ~~ y %% z | &amp; \\",
                FormulaOutputType.PURE_TEXT);
        String markdown = ObsidianMarkdown.render(document);
        assertEquals(
                "\\# not a heading \\#tag\n\\- not a list\n1\\. not ordered\n"
                        + "\\*a\\* \\_b\\_ \\`c\\` \\[d](e) \\<f\\> \\$5 \\== x \\~~ y \\%% z \\| \\&amp; \\\\",
                markdown);
        assertRoundTrip(document);
    }

    @Test
    void indentationOfPlainTextIsKeptWithoutCreatingCodeBlocks() {
        String markdown =
                ObsidianMarkdown.render(NoteContentParser.parse("Steps\n    indented", FormulaOutputType.PURE_TEXT));
        assertEquals("Steps\n\u00A0\u00A0\u00A0\u00A0indented", markdown);
    }

    @Test
    void stylesAreRenderedAroundTrimmedText() {
        Inline.Style bold = Inline.Style.PLAIN.withBold();
        String markdown = render(new Block.Paragraph(List.of(
                new Inline.Text("a "),
                new Inline.Text(" bold ", bold),
                new Inline.Text("link", Inline.Style.PLAIN.withLink("https://e.com/a b")),
                new Inline.Text(" "),
                new Inline.Text("x`y", Inline.Style.PLAIN.withCode()),
                new Inline.Text(" "),
                new Inline.Text("under", Inline.Style.PLAIN.withUnderline()))));
        assertEquals("a  **bold** [link](https://e.com/a%20b) ``x`y`` <u>under</u>", markdown);
    }

    @Test
    void codeBlocksUseAFenceLongerThanTheirContent() {
        assertEquals("````md\n```\ncode\n```\n````", render(new Block.Code("md", "```\ncode\n```")));
    }

    @Test
    void richDocumentsRoundTrip() {
        Inline.Style italic = Inline.Style.PLAIN.withItalic();
        assertRoundTrip(new NoteDocument(
                List.of(
                        new Block.Heading(2, List.of(new Inline.Text("Title with $ and #"))),
                        new Block.Paragraph(List.of(
                                new Inline.Text("Energy "),
                                new Inline.Equation("E = mc^2"),
                                new Inline.Text(" is "),
                                new Inline.Text("famous", italic),
                                new Inline.Text("\nsecond line"))),
                        new Block.ListItem(
                                false,
                                List.of(new Inline.Text("item")),
                                List.of(
                                        new Block.Paragraph(List.of(new Inline.Text("more"))),
                                        new Block.Equation("\\sum_i x_i"))),
                        new Block.ListItem(false, List.of(new Inline.Text("second")), List.of()),
                        new Block.Divider(),
                        new Block.Code("python", "print('*not* markdown')"),
                        new Block.Quote(List.of(
                                new Block.Paragraph(List.of(new Inline.Text("quoted"))),
                                new Block.ListItem(true, List.of(new Inline.Text("one")), List.of()))),
                        new Block.Equation("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}")),
                List.of()));
    }

    @Test
    void katexMathDelimitersAreNormalisedForObsidian() {
        assertEquals(
                "Inline $x^2$ and\n$$a = b$$\n`\\(code\\)` \\\\(not math)\n```\n\\(fenced\\)\n```\n$y$",
                ObsidianMarkdown.normalizeMathDelimiters(
                        "Inline \\( x^2 \\) and\n\\[a = b\\]\n`\\(code\\)` \\\\(not math)\n```\n\\(fenced\\)\n```\n$y$"));
    }
}
