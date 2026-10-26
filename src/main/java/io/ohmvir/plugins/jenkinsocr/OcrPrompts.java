package io.ohmvir.plugins.jenkinsocr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads the OCR prompt templates bundled under {@code /prompts}. */
final class OcrPrompts {
    static final String DIR_TREE_PLACEHOLDER = "${DIR_TREE}";
    static final String EMPTY_DIR_TREE = "(no existing folders)\n";

    private OcrPrompts() {}

    static String load(FormulaOutputType type) throws IOException {
        if (type == null) {
            throw new IllegalArgumentException("Formula output type is not configured");
        }
        String resource = "/prompts/" + type.name() + ".md";
        try (InputStream is = OcrPrompts.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("Could not find prompt file " + resource);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Loads the prompt for the given output type with the Notion folder tree substituted in. */
    static String render(FormulaOutputType type, String dirTree) throws IOException {
        String tree = dirTree == null || dirTree.isBlank() ? EMPTY_DIR_TREE : dirTree;
        return load(type).replace(DIR_TREE_PLACEHOLDER, tree);
    }
}
