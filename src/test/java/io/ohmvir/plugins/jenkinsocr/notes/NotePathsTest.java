package io.ohmvir.plugins.jenkinsocr.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotePathsTest {

    @Test
    void splitsPathsIntoTrimmedSegments() {
        assertEquals(List.of("Math", "Linear Algebra"), NotePaths.segments(" Math / Linear Algebra/ "));
        assertEquals(List.of(), NotePaths.segments(""));
        assertEquals(List.of(), NotePaths.segments(null));
    }

    @Test
    void formatsTheFolderTreeForThePrompt() {
        assertEquals(
                "- Math (Path: Math)\n  - Algebra (Path: Math/Algebra)\n- Physics (Path: Physics)\n",
                NotePaths.formatTree(List.of("Math", "Math/Algebra", "Physics")));
        assertEquals("(no folders exist yet)\n", NotePaths.formatTree(List.of()));
    }

    @Test
    void notesRequireATitle() {
        assertThrows(IllegalArgumentException.class, () -> new Note(" ", "a", "text", FormulaOutputType.KATEX));
        Note note = new Note(" Title ", null, null, FormulaOutputType.KATEX);
        assertEquals("Title", note.title());
        assertEquals("", note.text());
        assertEquals(List.of(), note.pathSegments());
    }
}
