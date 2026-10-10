package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OcrPromptsTest {
    @Test
    void everyOutputTypeHasAPromptWithOneTreePlaceholder() throws Exception {
        for (FormulaOutputType type : FormulaOutputType.values()) {
            String prompt = OcrPrompts.load(type);

            int occurrences = prompt.split(Pattern.quote(OcrPrompts.DIR_TREE_PLACEHOLDER), -1).length - 1;
            assertEquals(1, occurrences, type + " prompt should insert the directory tree exactly once");
            for (String field : new String[] {"\"title\"", "\"path\"", "\"text\""}) {
                assertTrue(prompt.contains(field), type + " prompt should ask for " + field);
            }
        }
    }

    @Test
    void renderSubstitutesTheDirectoryTree() throws Exception {
        String tree = "- Math (Path: Math)\n";
        for (FormulaOutputType type : FormulaOutputType.values()) {
            String prompt = OcrPrompts.render(type, tree);

            assertTrue(prompt.contains(tree), type.name());
            assertFalse(prompt.contains(OcrPrompts.DIR_TREE_PLACEHOLDER), type.name());
        }
    }

    @Test
    void renderExplainsAnEmptyTree() throws Exception {
        assertTrue(OcrPrompts.render(FormulaOutputType.KATEX, "").contains(OcrPrompts.EMPTY_DIR_TREE));
        assertTrue(OcrPrompts.render(FormulaOutputType.KATEX, null).contains(OcrPrompts.EMPTY_DIR_TREE));
    }

    @Test
    void missingOutputTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OcrPrompts.load(null));
    }
}
