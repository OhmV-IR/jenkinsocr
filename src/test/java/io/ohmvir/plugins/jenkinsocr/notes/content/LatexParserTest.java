package io.ohmvir.plugins.jenkinsocr.notes.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import java.util.List;
import org.junit.jupiter.api.Test;

class LatexParserTest {
    private static final Inline.Style BOLD = Inline.Style.PLAIN.withBold();
    private static final Inline.Style ITALIC = Inline.Style.PLAIN.withItalic();

    private static NoteDocument document(String latex) {
        return NoteContentParser.parse(latex, FormulaOutputType.LATEX);
    }

    private static List<Block> parse(String latex) {
        return document(latex).blocks();
    }

    private static Inline.Text text(String text) {
        return new Inline.Text(text);
    }

    private static Block.Paragraph paragraph(Inline... content) {
        return new Block.Paragraph(List.of(content));
    }

    @Test
    void sectionsParagraphsAndInlineMath() {
        List<Block> blocks = parse("""
                \\section*{Kinematics}
                The position is \\(x(t) = x_0 + v t\\) and
                the speed is $v$.

                \\subsection{Units}
                Measured in \\textbf{metres} per \\emph{second}.
                """);
        assertEquals(
                List.of(
                        new Block.Heading(1, List.of(text("Kinematics"))),
                        paragraph(
                                text("The position is "),
                                new Inline.Equation("x(t) = x_0 + v t"),
                                text(" and the speed is "),
                                new Inline.Equation("v"),
                                text(".")),
                        new Block.Heading(2, List.of(text("Units"))),
                        paragraph(
                                text("Measured in "),
                                new Inline.Text("metres", BOLD),
                                text(" per "),
                                new Inline.Text("second", ITALIC),
                                text("."))),
                blocks);
    }

    @Test
    void displayMathEnvironmentsBecomeKatexCompatible() {
        List<Block> blocks = parse("""
                \\[ a^2 + b^2 = c^2 \\]
                \\begin{align*}
                  f(x) &= x^2 \\label{eq:f} \\\\
                  f'(x) &= 2x \\nonumber
                \\end{align*}
                \\begin{equation}
                  E = mc^2 \\tag{1}
                \\end{equation}
                \\begin{gather}
                  a \\\\ b
                \\end{gather}
                $$\\int_0^1 x\\,dx$$
                """);
        assertEquals(
                List.of(
                        new Block.Equation("a^2 + b^2 = c^2"),
                        new Block.Equation("\\begin{aligned}\n  f(x) &= x^2  \\\\\n  f'(x) &= 2x \n\\end{aligned}"),
                        new Block.Equation("E = mc^2"),
                        new Block.Equation("\\begin{gathered}\n  a \\\\ b\n\\end{gathered}"),
                        new Block.Equation("\\int_0^1 x\\,dx")),
                blocks);
    }

    @Test
    void nestedListsAndDescriptions() {
        List<Block> blocks = parse("""
                \\begin{enumerate}
                  \\item First
                  \\begin{itemize}
                    \\item Sub $x$
                  \\end{itemize}
                  \\item Second
                \\end{enumerate}
                \\begin{description}
                  \\item[Term] Meaning
                \\end{description}
                """);
        assertEquals(
                List.of(
                        new Block.ListItem(
                                true,
                                List.of(text("First")),
                                List.of(new Block.ListItem(
                                        false, List.of(text("Sub "), new Inline.Equation("x")), List.of()))),
                        new Block.ListItem(true, List.of(text("Second")), List.of()),
                        new Block.ListItem(false, List.of(new Inline.Text("Term", BOLD), text(" Meaning")), List.of())),
                blocks);
    }

    @Test
    void lineBreaksCommentsAndTypography() {
        List<Block> blocks =
                parse("Line one\\\\ line two % a comment\nstill ``quoted'' -- and --- 50\\% \\& more~here");
        assertEquals(
                List.of(paragraph(text("Line one\nline two still “quoted” – and — 50% & more\u00A0here"))), blocks);
    }

    @Test
    void commentFollowedByBlankLineEndsParagraph() {
        assertEquals(List.of(paragraph(text("a")), paragraph(text("b"))), parse("a % note\n\nb"));
    }

    @Test
    void accentsAndSymbols() {
        assertEquals(
                List.of(paragraph(text("café naïve Erdős ç ı… LaTeX is"))),
                parse("caf\\'e na\\\"{\\i}ve Erd\\H{o}s \\c{c} \\i\\ldots{} \\LaTeX is"));
    }

    @Test
    void verbatimEnvironmentsBecomeCodeBlocks() {
        List<Block> blocks = parse("""
                \\begin{lstlisting}[language=Python, caption=Demo]
                print("50% done") # \\textbf{not bold}
                \\end{lstlisting}
                Use \\verb|x_1| here.
                """);
        assertEquals(
                List.of(
                        new Block.Code("python", "print(\"50% done\") # \\textbf{not bold}"),
                        paragraph(text("Use "), new Inline.Text("x_1", Inline.Style.PLAIN.withCode()), text(" here."))),
                blocks);
    }

    @Test
    void figuresAndTikzAreKeptAsSourceWithAWarning() {
        String figure = """
                \\begin{figure}[h]
                \\centering
                % [Diagram Description: a right triangle]
                \\caption{Triangle}
                \\end{figure}""";
        NoteDocument document = document("Before\n" + figure + "\nAfter");
        assertEquals(
                List.of(paragraph(text("Before")), new Block.Code("latex", figure), paragraph(text("After"))),
                document.blocks());
        assertEquals(1, document.warnings().size());
        assertTrue(document.warnings().get(0).contains("'figure' environment"));
    }

    @Test
    void quotesLinksAndUnknownCommands() {
        NoteDocument document = document(
                "\\begin{quote}Margin note\\end{quote}\\href{https://e.com}{site} \\mycommand{kept} \\url{https://x.org}");
        assertEquals(
                List.of(
                        new Block.Quote(List.of(paragraph(text("Margin note")))),
                        paragraph(
                                new Inline.Text("site", Inline.Style.PLAIN.withLink("https://e.com")),
                                text(" kept "),
                                new Inline.Text("https://x.org", Inline.Style.PLAIN.withLink("https://x.org")))),
                document.blocks());
        assertEquals(
                List.of("The unsupported LaTeX command \\mycommand was replaced by its arguments."),
                document.warnings());
    }

    @Test
    void preambleAndDocumentWrapperAreIgnored() {
        List<Block> blocks = parse("""
                \\documentclass[11pt]{article}
                \\usepackage{amsmath}
                \\begin{document}
                \\noindent Hello {\\bf world}.
                \\end{document}
                """);
        assertEquals(List.of(paragraph(text("Hello "), new Inline.Text("world", BOLD), text("."))), blocks);
    }
}
