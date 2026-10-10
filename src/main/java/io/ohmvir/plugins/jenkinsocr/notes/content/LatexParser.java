package io.ohmvir.plugins.jenkinsocr.notes.content;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a LaTeX document segment (the {@link io.ohmvir.plugins.jenkinsocr.FormulaOutputType#LATEX} output) into
 * a {@link NoteDocument}.
 *
 * <p>Note applications cannot typeset LaTeX documents, only TeX math, so the document structure is translated:
 * sectioning commands become headings, {@code itemize}/{@code enumerate}/{@code description} become lists,
 * {@code quote} becomes a block quote, verbatim environments become code blocks and text formatting commands become
 * inline styles. Math ({@code $...$}, {@code \(...\)}, {@code $$...$$}, {@code \[...\]} and the amsmath display
 * environments) is kept as TeX, with display environments rewritten to their KaTeX/MathJax-compatible inner forms
 * (e.g. {@code align*} to {@code aligned}). Constructs with no equivalent, such as TikZ pictures, figures and
 * tables, are kept verbatim as LaTeX code blocks and reported as warnings.
 */
final class LatexParser {
    private static final Set<String> LIST_ENVIRONMENTS = Set.of("itemize", "enumerate", "description");
    private static final Set<String> QUOTE_ENVIRONMENTS = Set.of("quote", "quotation", "verse");
    private static final Set<String> MATH_ENVIRONMENTS = Set.of(
            "equation",
            "equation*",
            "align",
            "align*",
            "alignat",
            "alignat*",
            "flalign",
            "flalign*",
            "gather",
            "gather*",
            "multline",
            "multline*",
            "eqnarray",
            "eqnarray*",
            "displaymath",
            "math");
    /** Environments that are only valid inside math, but which models sometimes emit in text mode. */
    private static final Set<String> INNER_MATH_ENVIRONMENTS = Set.of(
            "aligned",
            "alignedat",
            "gathered",
            "split",
            "cases",
            "array",
            "matrix",
            "pmatrix",
            "bmatrix",
            "Bmatrix",
            "vmatrix",
            "Vmatrix",
            "smallmatrix");

    private static final Set<String> VERBATIM_ENVIRONMENTS =
            Set.of("verbatim", "verbatim*", "Verbatim", "lstlisting", "minted", "alltt");
    private static final Set<String> SOURCE_ENVIRONMENTS = Set.of(
            "tikzpicture",
            "circuitikz",
            "pgfpicture",
            "picture",
            "axis",
            "forest",
            "figure",
            "figure*",
            "wrapfigure",
            "subfigure",
            "table",
            "table*",
            "tabular",
            "tabular*",
            "tabularx",
            "longtable",
            "algorithm",
            "algorithmic",
            "thebibliography");
    private static final Set<String> TRANSPARENT_ENVIRONMENTS =
            Set.of("document", "center", "flushleft", "flushright", "minipage", "multicols", "sloppypar", "landscape");

    private static final Map<String, String> SYMBOLS = Map.ofEntries(
            Map.entry("LaTeX", "LaTeX"),
            Map.entry("LaTeXe", "LaTeX2e"),
            Map.entry("TeX", "TeX"),
            Map.entry("ldots", "…"),
            Map.entry("dots", "…"),
            Map.entry("textellipsis", "…"),
            Map.entry("textbackslash", "\\"),
            Map.entry("textasciitilde", "~"),
            Map.entry("textasciicircum", "^"),
            Map.entry("textbar", "|"),
            Map.entry("textless", "<"),
            Map.entry("textgreater", ">"),
            Map.entry("textendash", "–"),
            Map.entry("textemdash", "—"),
            Map.entry("textbullet", "•"),
            Map.entry("textdegree", "°"),
            Map.entry("textquoteleft", "‘"),
            Map.entry("textquoteright", "’"),
            Map.entry("textquotedblleft", "“"),
            Map.entry("textquotedblright", "”"),
            Map.entry("S", "§"),
            Map.entry("P", "¶"),
            Map.entry("copyright", "©"),
            Map.entry("textcopyright", "©"),
            Map.entry("textregistered", "®"),
            Map.entry("texttrademark", "™"),
            Map.entry("dag", "†"),
            Map.entry("ddag", "‡"),
            Map.entry("pounds", "£"),
            Map.entry("euro", "€"),
            Map.entry("checkmark", "✓"),
            Map.entry("i", "ı"),
            Map.entry("j", "ȷ"),
            Map.entry("ss", "ß"),
            Map.entry("ae", "æ"),
            Map.entry("AE", "Æ"),
            Map.entry("oe", "œ"),
            Map.entry("OE", "Œ"),
            Map.entry("o", "ø"),
            Map.entry("O", "Ø"),
            Map.entry("aa", "å"),
            Map.entry("AA", "Å"),
            Map.entry("l", "ł"),
            Map.entry("L", "Ł"),
            Map.entry("quad", " "),
            Map.entry("qquad", " "),
            Map.entry("enspace", " "),
            Map.entry("thinspace", " "),
            Map.entry("space", " "),
            Map.entry("today", ""));

    private static final Set<String> IGNORED_COMMANDS = Set.of(
            "noindent",
            "indent",
            "centering",
            "raggedright",
            "raggedleft",
            "maketitle",
            "tableofcontents",
            "listoffigures",
            "listoftables",
            "newpage",
            "clearpage",
            "cleardoublepage",
            "pagebreak",
            "nopagebreak",
            "smallskip",
            "medskip",
            "bigskip",
            "hfill",
            "vfill",
            "hfil",
            "vfil",
            "protect",
            "relax",
            "nobreak",
            "allowbreak",
            "sloppy",
            "fussy",
            "normalsize",
            "tiny",
            "scriptsize",
            "footnotesize",
            "small",
            "large",
            "Large",
            "LARGE",
            "huge",
            "Huge",
            "normalfont",
            "rmfamily",
            "sffamily",
            "upshape",
            "mdseries",
            "scshape",
            "selectfont",
            "frenchspacing",
            "onecolumn",
            "twocolumn",
            "appendix",
            "centerline");

    /** Commands whose arguments are dropped, mapped to their number of mandatory arguments. */
    private static final Map<String, Integer> IGNORED_COMMANDS_WITH_ARGUMENTS = Map.ofEntries(
            Map.entry("label", 1),
            Map.entry("index", 1),
            Map.entry("vspace", 1),
            Map.entry("hspace", 1),
            Map.entry("addvspace", 1),
            Map.entry("setlength", 2),
            Map.entry("addtolength", 2),
            Map.entry("setcounter", 2),
            Map.entry("addtocounter", 2),
            Map.entry("pagestyle", 1),
            Map.entry("thispagestyle", 1),
            Map.entry("pagenumbering", 1),
            Map.entry("documentclass", 1),
            Map.entry("usepackage", 1),
            Map.entry("RequirePackage", 1),
            Map.entry("usetikzlibrary", 1),
            Map.entry("geometry", 1),
            Map.entry("hypersetup", 1),
            Map.entry("title", 1),
            Map.entry("author", 1),
            Map.entry("date", 1),
            Map.entry("thanks", 1),
            Map.entry("bibliographystyle", 1),
            Map.entry("bibliography", 1),
            Map.entry("input", 1),
            Map.entry("include", 1),
            Map.entry("linespread", 1),
            Map.entry("fontsize", 2),
            Map.entry("color", 1),
            Map.entry("newtheorem", 2));

    private static final Set<String> MACRO_DEFINITIONS =
            Set.of("newcommand", "renewcommand", "providecommand", "DeclareMathOperator");

    private static final Map<Character, Character> ACCENTS = Map.ofEntries(
            Map.entry('`', '\u0300'),
            Map.entry('\'', '\u0301'),
            Map.entry('^', '\u0302'),
            Map.entry('~', '\u0303'),
            Map.entry('=', '\u0304'),
            Map.entry('u', '\u0306'),
            Map.entry('.', '\u0307'),
            Map.entry('"', '\u0308'),
            Map.entry('r', '\u030A'),
            Map.entry('H', '\u030B'),
            Map.entry('v', '\u030C'),
            Map.entry('d', '\u0323'),
            Map.entry('c', '\u0327'),
            Map.entry('k', '\u0328'),
            Map.entry('b', '\u0331'));

    private static final Pattern MATH_NUMBERING =
            Pattern.compile("\\\\label\\s*\\{[^}]*}|\\\\tag\\*?\\s*\\{[^}]*}|\\\\(?:nonumber|notag)(?![A-Za-z])");
    private static final Pattern LISTING_LANGUAGE = Pattern.compile("(?:^|,)\\s*language\\s*=\\s*\\{?([^,}\\]]+)");
    private static final String SPECIAL_CHARACTERS = "\\{}$%~`'- \t\n\r";

    private String src;
    private int pos;
    private final DocumentBuilder out = new DocumentBuilder();
    private final Deque<String> openEnvironments = new ArrayDeque<>();
    private final Set<String> warnings = new LinkedHashSet<>();

    private LatexParser(String src) {
        this.src = src;
    }

    static NoteDocument parse(String latex) {
        LatexParser parser = new LatexParser(latex.replace("\r\n", "\n").replace('\r', '\n'));
        parser.parseContent(null, false);
        return parser.out.build(new ArrayList<>(parser.warnings));
    }

    /**
     * Parses until the end of the input, the closing brace of the current group ({@code inGroup}), or
     * {@code \end{endEnvironment}}. An {@code \end} of an enclosing environment is left unconsumed for that
     * environment to handle.
     */
    private void parseContent(String endEnvironment, boolean inGroup) {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            switch (c) {
                case '}' -> {
                    pos++;
                    if (inGroup) {
                        return;
                    }
                }
                case '{' -> {
                    pos++;
                    Inline.Style saved = out.style();
                    parseContent(null, true);
                    out.setStyle(saved);
                }
                case '\\' -> {
                    if (src.startsWith("\\end", pos) && !isLetter(pos + 4)) {
                        int start = pos;
                        pos += 4;
                        String name = readArgumentRaw().strip();
                        if (name.equals(endEnvironment)) {
                            return;
                        }
                        if (openEnvironments.contains(name)) {
                            pos = start;
                            return;
                        }
                    } else {
                        parseCommand();
                    }
                }
                case '%' -> skipComment();
                case '$' -> dollarMath();
                case '~' -> {
                    out.text("\u00A0");
                    pos++;
                }
                case '`' -> {
                    boolean twice = src.startsWith("``", pos);
                    out.text(twice ? "“" : "‘");
                    pos += twice ? 2 : 1;
                }
                case '\'' -> {
                    boolean twice = src.startsWith("''", pos);
                    out.text(twice ? "”" : "'");
                    pos += twice ? 2 : 1;
                }
                case '-' -> dashes();
                case ' ', '\t', '\n', '\r' -> whitespace();
                default -> {
                    int start = pos;
                    while (pos < src.length() && SPECIAL_CHARACTERS.indexOf(src.charAt(pos)) < 0) {
                        pos++;
                    }
                    if (pos == start) {
                        pos++;
                    }
                    out.text(src.substring(start, pos));
                }
            }
        }
    }

    private void whitespace() {
        int newlines = 0;
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            if (src.charAt(pos) == '\n') {
                newlines++;
            }
            pos++;
        }
        if (newlines >= 2) {
            out.paragraphBreak();
        } else {
            out.text(" ");
        }
    }

    private void skipComment() {
        while (pos < src.length() && src.charAt(pos) != '\n') {
            pos++;
        }
        // Like TeX, a comment swallows its line break and the next line's indentation; an empty line after it
        // still ends the paragraph.
        pos = Math.min(pos + 1, src.length());
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) {
            pos++;
        }
        if (pos < src.length() && src.charAt(pos) == '\n') {
            out.paragraphBreak();
        }
    }

    private void dashes() {
        int count = 0;
        while (pos < src.length() && src.charAt(pos) == '-') {
            count++;
            pos++;
        }
        StringBuilder result = new StringBuilder();
        for (; count >= 3; count -= 3) {
            result.append('—');
        }
        result.append(count == 2 ? "–" : count == 1 ? "-" : "");
        out.text(result.toString());
    }

    private void dollarMath() {
        boolean display = src.startsWith("$$", pos);
        String delimiter = display ? "$$" : "$";
        int end = indexOfUnescaped(delimiter, pos + delimiter.length());
        if (end < 0) {
            out.text(delimiter);
            pos += delimiter.length();
            return;
        }
        String expression = src.substring(pos + delimiter.length(), end);
        pos = end + delimiter.length();
        if (display) {
            displayEquation(expression);
        } else {
            out.inlineEquation(cleanMath(expression));
        }
    }

    private void displayEquation(String expression) {
        String cleaned = cleanMath(expression);
        if (!cleaned.isEmpty()) {
            out.addBlock(new Block.Equation(cleaned));
        }
    }

    private void parseCommand() {
        pos++; // the backslash
        if (pos >= src.length()) {
            out.text("\\");
            return;
        }
        if (!isLetter(pos)) {
            controlSymbol(src.charAt(pos++));
            return;
        }
        int start = pos;
        while (isLetter(pos)) {
            pos++;
        }
        String name = src.substring(start, pos);
        if (pos < src.length() && src.charAt(pos) == '*') {
            pos++;
        }
        command(name);
    }

    private void controlSymbol(char c) {
        switch (c) {
            case '\\' -> {
                if (pos < src.length() && src.charAt(pos) == '*') {
                    pos++;
                }
                readOptionalRaw();
                out.lineBreak();
            }
            case '%', '&', '$', '#', '_', '{', '}' -> out.text(String.valueOf(c));
            case ' ', '\t', '\n', ',', ';', ':', '>' -> out.text(" ");
            case '!', '-', '/', '@' -> {
                // spacing and hyphenation hints have no visible equivalent
            }
            case '(' -> mathUntil("\\)", false);
            case '[' -> mathUntil("\\]", true);
            default -> {
                Character combining = ACCENTS.get(c);
                if (combining != null) {
                    accent(combining);
                } else {
                    out.text(String.valueOf(c));
                }
            }
        }
    }

    private void mathUntil(String close, boolean display) {
        int end = src.indexOf(close, pos);
        String expression;
        if (end < 0) {
            warnings.add("A formula opened with " + (display ? "\\[" : "\\(") + " was never closed.");
            expression = src.substring(pos);
            pos = src.length();
        } else {
            expression = src.substring(pos, end);
            pos = end + close.length();
        }
        if (display) {
            displayEquation(expression);
        } else {
            out.inlineEquation(cleanMath(expression));
        }
    }

    private void command(String name) {
        switch (name) {
            case "begin" -> beginEnvironment(readArgumentRaw().strip());
            case "end" -> readArgumentRaw(); // an \end without a matching \begin
            case "part", "chapter", "section" -> heading(1);
            case "subsection" -> heading(2);
            case "subsubsection" -> heading(3);
            case "paragraph", "subparagraph" -> {
                readOptionalRaw();
                styledArgument(Inline.Style::withBold);
                out.text(" ");
            }
            case "textbf" -> styledArgument(Inline.Style::withBold);
            case "textit", "emph", "textsl" -> styledArgument(Inline.Style::withItalic);
            case "texttt" -> styledArgument(Inline.Style::withCode);
            case "underline", "uline", "uuline" -> styledArgument(Inline.Style::withUnderline);
            case "sout", "st", "xout" -> styledArgument(Inline.Style::withStrikethrough);
            case "textrm", "textsf", "textup", "textmd", "textnormal", "textsc", "text", "mbox", "hbox" ->
                styledArgument(UnaryOperator.identity());
            case "makebox", "framebox", "fbox", "parbox" -> {
                readOptionalRaw();
                readOptionalRaw();
                if (name.equals("parbox")) {
                    readArgumentRaw();
                }
                styledArgument(UnaryOperator.identity());
            }
            case "bf", "bfseries" -> out.setStyle(out.style().withBold());
            case "it", "itshape", "sl", "slshape", "em" ->
                out.setStyle(out.style().withItalic());
            case "tt", "ttfamily" -> out.setStyle(out.style().withCode());
            case "item" -> item();
            case "href" -> {
                String url = readArgumentRaw().strip();
                styledArgument(style -> style.withLink(url));
            }
            case "url", "nolinkurl" -> {
                String url = readArgumentRaw().strip();
                Inline.Style saved = out.style();
                out.setStyle(saved.withLink(url));
                out.text(url);
                out.setStyle(saved);
            }
            case "footnote" -> {
                readOptionalRaw();
                out.text(" (");
                styledArgument(UnaryOperator.identity());
                out.text(")");
            }
            case "cite", "citep", "citet" -> {
                readOptionalRaw();
                readOptionalRaw();
                out.text("[" + readArgumentRaw().strip() + "]");
            }
            case "ref", "autoref", "cref", "Cref", "pageref", "nameref" ->
                out.text(readArgumentRaw().strip());
            case "eqref" -> out.text("(" + readArgumentRaw().strip() + ")");
            case "verb" -> verb();
            case "caption" -> {
                readOptionalRaw();
                styledArgument(Inline.Style::withItalic);
            }
            case "includegraphics" -> {
                readOptionalRaw();
                String file = readArgumentRaw().strip();
                Inline.Style saved = out.style();
                out.setStyle(saved.withItalic());
                out.text("[Image: " + file + "]");
                out.setStyle(saved);
                warnings.add("Images (\\includegraphics) cannot be uploaded and were replaced by their file name.");
            }
            case "textcolor", "colorbox" -> {
                readOptionalRaw();
                readArgumentRaw();
                styledArgument(UnaryOperator.identity());
            }
            case "fcolorbox" -> {
                readOptionalRaw();
                readArgumentRaw();
                readArgumentRaw();
                styledArgument(UnaryOperator.identity());
            }
            case "par" -> out.paragraphBreak();
            case "newline", "linebreak" -> {
                readOptionalRaw();
                out.lineBreak();
            }
            case "newenvironment", "renewenvironment" -> {
                warnings.add("Custom LaTeX environment definitions (\\" + name + ") are not supported and were"
                        + " ignored.");
                readArgumentRaw();
                skipOptionalArguments();
                readArgumentRaw();
                readArgumentRaw();
            }
            case "def" -> {
                warnings.add("Custom LaTeX macro definitions (\\def) are not supported and were ignored.");
                while (pos < src.length() && src.charAt(pos) != '{') {
                    pos++;
                }
                readArgumentRaw();
            }
            default -> otherCommand(name);
        }
    }

    private void otherCommand(String name) {
        if (SYMBOLS.containsKey(name)) {
            out.text(SYMBOLS.get(name));
            if (src.startsWith("{}", pos)) {
                pos += 2;
            }
        } else if (IGNORED_COMMANDS.contains(name)) {
            skipSpaces();
        } else if (IGNORED_COMMANDS_WITH_ARGUMENTS.containsKey(name)) {
            skipOptionalArguments();
            for (int i = 0; i < IGNORED_COMMANDS_WITH_ARGUMENTS.get(name); i++) {
                readArgumentRaw();
                skipOptionalArguments();
            }
            skipSpaces();
        } else if (MACRO_DEFINITIONS.contains(name)) {
            warnings.add("Custom LaTeX macro definitions (\\" + name + ") are not supported and were ignored.");
            readArgumentRaw();
            skipOptionalArguments();
            readArgumentRaw();
        } else if (name.length() == 1 && ACCENTS.containsKey(name.charAt(0))) {
            accent(ACCENTS.get(name.charAt(0)));
        } else {
            warnings.add("The unsupported LaTeX command \\" + name + " was replaced by its arguments.");
            skipOptionalArguments();
            while (nextNonSpace() == '{') {
                styledArgument(UnaryOperator.identity());
            }
        }
    }

    private void heading(int level) {
        readOptionalRaw();
        List<Inline> content = out.capture(() -> styledArgument(UnaryOperator.identity()));
        if (!content.isEmpty()) {
            out.addBlock(new Block.Heading(level, content));
        }
    }

    private void item() {
        String label = readOptionalRaw();
        out.item();
        if (label != null) {
            Inline.Style saved = out.style();
            out.setStyle(saved.withBold());
            parseFragment(label);
            out.setStyle(saved);
            out.text(" ");
        }
    }

    private void verb() {
        if (pos < src.length() && src.charAt(pos) == '*') {
            pos++;
        }
        if (pos >= src.length()) {
            return;
        }
        char delimiter = src.charAt(pos);
        int end = src.indexOf(delimiter, pos + 1);
        if (end < 0) {
            end = src.length();
        }
        String code = src.substring(pos + 1, end);
        pos = Math.min(end + 1, src.length());
        Inline.Style saved = out.style();
        out.setStyle(saved.withCode());
        out.text(code);
        out.setStyle(saved);
    }

    private void accent(char combining) {
        skipSpaces();
        String base;
        if (pos < src.length() && src.charAt(pos) == '{') {
            base = readArgumentRaw().strip();
        } else if (pos < src.length()) {
            base = String.valueOf(src.charAt(pos++));
        } else {
            base = "";
        }
        // LaTeX accents the dotless \i and \j so the accent replaces the dot; in Unicode the accent goes on i and j.
        if (base.equals("\\i")) {
            base = "i";
        } else if (base.equals("\\j")) {
            base = "j";
        }
        if (base.isEmpty()) {
            out.text(String.valueOf(combining));
            return;
        }
        int split = base.offsetByCodePoints(0, 1);
        out.text(Normalizer.normalize(base.substring(0, split) + combining, Normalizer.Form.NFC)
                + base.substring(split));
    }

    private void beginEnvironment(String name) {
        if (LIST_ENVIRONMENTS.contains(name)) {
            readOptionalRaw();
            out.beginList(name.equals("enumerate"));
            parseEnvironmentBody(name);
            out.endList();
        } else if (QUOTE_ENVIRONMENTS.contains(name)) {
            out.beginQuote();
            parseEnvironmentBody(name);
            out.endQuote();
        } else if (MATH_ENVIRONMENTS.contains(name)) {
            String columns = name.startsWith("alignat") ? readArgumentRaw() : null;
            String body = readRawUntilEnd(name);
            if (name.equals("math")) {
                out.inlineEquation(cleanMath(body));
            } else {
                displayEquation(mathEnvironmentToInnerForm(name, columns, body));
            }
        } else if (INNER_MATH_ENVIRONMENTS.contains(name)) {
            displayEquation("\\begin{" + name + "}" + readRawUntilEnd(name) + "\\end{" + name + "}");
        } else if (VERBATIM_ENVIRONMENTS.contains(name)) {
            String language = "";
            if (name.equals("lstlisting")) {
                String options = readOptionalRaw();
                if (options != null) {
                    Matcher matcher = LISTING_LANGUAGE.matcher(options);
                    if (matcher.find()) {
                        language = matcher.group(1).strip();
                    }
                }
            } else if (name.equals("minted")) {
                readOptionalRaw();
                language = readArgumentRaw().strip();
            }
            String body = readRawUntilEnd(name).replaceFirst("^[ \\t]*\\n", "").stripTrailing();
            out.addBlock(new Block.Code(language.toLowerCase(Locale.ROOT), body));
        } else if (SOURCE_ENVIRONMENTS.contains(name)) {
            String source = "\\begin{" + name + "}" + readRawUntilEnd(name) + "\\end{" + name + "}";
            out.addBlock(new Block.Code("latex", source));
            warnings.add("The LaTeX '" + name + "' environment cannot be rendered by note applications and was kept as"
                    + " LaTeX source code.");
        } else if (name.equals("comment")) {
            readRawUntilEnd(name);
        } else if (name.equals("abstract")) {
            out.addBlock(new Block.Heading(2, List.of(new Inline.Text("Abstract"))));
            parseEnvironmentBody(name);
            out.paragraphBreak();
        } else {
            if (!TRANSPARENT_ENVIRONMENTS.contains(name)) {
                warnings.add("The unsupported LaTeX '" + name + "' environment was converted as plain text.");
            }
            if (name.equals("minipage")) {
                readOptionalRaw();
                readOptionalRaw();
                readOptionalRaw();
                readArgumentRaw();
            } else if (name.equals("multicols")) {
                readArgumentRaw();
            }
            parseEnvironmentBody(name);
            out.paragraphBreak();
        }
    }

    private void parseEnvironmentBody(String name) {
        openEnvironments.push(name);
        try {
            parseContent(name, false);
        } finally {
            openEnvironments.pop();
        }
    }

    /**
     * Rewrites a top-level amsmath display environment into the form accepted inside {@code $$...$$} by KaTeX and
     * MathJax.
     */
    private static String mathEnvironmentToInnerForm(String name, String columns, String body) {
        String base = name.endsWith("*") ? name.substring(0, name.length() - 1) : name;
        return switch (base) {
            case "align", "flalign", "eqnarray" -> "\\begin{aligned}" + body + "\\end{aligned}";
            case "alignat" ->
                "\\begin{alignedat}{" + (columns == null ? "1" : columns.strip()) + "}" + body + "\\end{alignedat}";
            case "gather", "multline" -> "\\begin{gathered}" + body + "\\end{gathered}";
            default -> body;
        };
    }

    /**
     * Removes equation numbering commands, which KaTeX and MathJax reject inside {@code aligned}-style environments,
     * and surrounding whitespace.
     */
    static String cleanMath(String expression) {
        return MATH_NUMBERING.matcher(expression).replaceAll("").strip();
    }

    /**
     * Parses a nested piece of LaTeX (such as an {@code \item} label) into the current output.
     */
    private void parseFragment(String fragment) {
        String savedSource = src;
        int savedPosition = pos;
        src = fragment;
        pos = 0;
        try {
            parseContent(null, false);
        } finally {
            src = savedSource;
            pos = savedPosition;
        }
    }

    /**
     * Parses the next argument (a braced group or a single token) into the current paragraph with a style applied.
     */
    private void styledArgument(UnaryOperator<Inline.Style> styler) {
        Inline.Style saved = out.style();
        out.setStyle(styler.apply(saved));
        skipSpaces();
        if (pos < src.length() && src.charAt(pos) == '{') {
            pos++;
            parseContent(null, true);
        } else if (pos < src.length() && src.charAt(pos) == '\\') {
            parseCommand();
        } else if (pos < src.length()) {
            out.text(String.valueOf(src.charAt(pos++)));
        }
        out.setStyle(saved);
    }

    /**
     * Reads the next argument verbatim: the content of a braced group, or a single character or control word.
     */
    private String readArgumentRaw() {
        skipSpaces();
        if (pos >= src.length()) {
            return "";
        }
        char c = src.charAt(pos);
        if (c == '{') {
            int end = matchingBrace(pos);
            String content = src.substring(pos + 1, end);
            pos = Math.min(end + 1, src.length());
            return content;
        }
        if (c == '\\') {
            int start = pos++;
            while (isLetter(pos)) {
                pos++;
            }
            if (pos == start + 1 && pos < src.length()) {
                pos++;
            }
            return src.substring(start, pos);
        }
        pos++;
        return String.valueOf(c);
    }

    /**
     * Reads an optional {@code [...]} argument, or returns {@code null} (consuming nothing) if there is none.
     */
    private String readOptionalRaw() {
        int start = pos;
        skipSpaces();
        if (pos >= src.length() || src.charAt(pos) != '[') {
            pos = start;
            return null;
        }
        int depth = 0;
        for (int i = pos; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ']' && depth == 0) {
                String content = src.substring(pos + 1, i);
                pos = i + 1;
                return content;
            }
        }
        pos = start;
        return null;
    }

    private void skipOptionalArguments() {
        while (readOptionalRaw() != null) {
            // keep skipping
        }
    }

    private int matchingBrace(int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return src.length();
    }

    /**
     * Returns the raw source up to the matching {@code \end{name}} and moves past it.
     */
    private String readRawUntilEnd(String name) {
        String begin = "\\begin{" + name + "}";
        String end = "\\end{" + name + "}";
        int depth = 1;
        int i = pos;
        while (i < src.length()) {
            if (src.startsWith(begin, i)) {
                depth++;
                i += begin.length();
            } else if (src.startsWith(end, i)) {
                depth--;
                if (depth == 0) {
                    String body = src.substring(pos, i);
                    pos = i + end.length();
                    return body;
                }
                i += end.length();
            } else {
                i++;
            }
        }
        warnings.add("The LaTeX '" + name + "' environment was never closed.");
        String body = src.substring(pos);
        pos = src.length();
        return body;
    }

    private int indexOfUnescaped(String needle, int from) {
        for (int i = from; i <= src.length() - needle.length(); i++) {
            if (src.charAt(i) == '\\') {
                i++;
            } else if (src.startsWith(needle, i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Skips spaces and at most one line break, like TeX does after a control word and between arguments.
     */
    private void skipSpaces() {
        boolean newline = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t') {
                pos++;
            } else if (c == '\n' && !newline) {
                newline = true;
                pos++;
            } else {
                return;
            }
        }
    }

    private char nextNonSpace() {
        int start = pos;
        skipSpaces();
        char c = pos < src.length() ? src.charAt(pos) : 0;
        pos = start;
        return c;
    }

    private boolean isLetter(int index) {
        if (index >= src.length()) {
            return false;
        }
        char c = src.charAt(index);
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
