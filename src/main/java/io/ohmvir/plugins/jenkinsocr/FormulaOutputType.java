package io.ohmvir.plugins.jenkinsocr;

public enum FormulaOutputType {
    /**
     * Allows the model to output math formulas in full latex for rendering.
     */
    LATEX,
    /**
     * Allows the model to output math formulas in katex, a reduced version of latex which doesn't have support for things like margins, headers, bibliographies, figures, tables of contents .etc
     */
    KATEX,
    /**
     * The model will only output pure text.
     */
    PURE_TEXT
}
