package io.ohmvir.plugins.jenkinsocr.notes.obsidian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObsidianVaultTest {
    @TempDir
    Path vault;

    private ObsidianVault.WriteResult write(List<String> folders, String title, String markdown) throws IOException {
        return new ObsidianVault.WriteNote(folders, title, markdown).invoke(vault.toFile(), null);
    }

    @Test
    void createsFoldersAndNotes() throws IOException {
        ObsidianVault.WriteResult result = write(List.of("Math", "Algebra"), "Groups", "# Groups\n\n$G$");
        Path note = vault.resolve("Math/Algebra/Groups.md");
        assertEquals(note.toString(), result.path());
        assertFalse(result.appended());
        assertEquals("# Groups\n\n$G$\n", Files.readString(note, StandardCharsets.UTF_8));
    }

    @Test
    void appendsToTheExistingNoteIgnoringCase() throws IOException {
        Files.createDirectories(vault.resolve("Physics"));
        Files.writeString(vault.resolve("Physics/Optics.md"), "old content");
        ObsidianVault.WriteResult result = write(List.of("physics"), "optics", "new content");
        assertTrue(result.appended());
        assertEquals(vault.resolve("Physics/Optics.md").toString(), result.path());
        assertEquals("old content\n\n---\n\nnew content\n", Files.readString(vault.resolve("Physics/Optics.md")));
        try (var entries = Files.list(vault)) {
            assertEquals(1, entries.count(), "no second folder differing only in case");
        }
    }

    @Test
    void unsafeNamesCannotEscapeTheVault() throws IOException {
        ObsidianVault.WriteResult result = write(List.of("..", "../..", ".obsidian"), "../../etc/passwd", "x");
        Path note = Path.of(result.path());
        assertTrue(note.startsWith(vault), note.toString());
        assertEquals(vault.resolve("Untitled/Untitled/obsidian/etc passwd.md"), note);
    }

    @Test
    void sanitisedTitlesKeepTheOriginalAsAnAlias() throws IOException {
        write(List.of(), "Chapter 3: Limits #1", "body");
        assertEquals(
                "---\naliases:\n  - \"Chapter 3: Limits #1\"\n---\n\nbody\n",
                Files.readString(vault.resolve("Chapter 3 Limits 1.md")));
    }

    @Test
    void fileNamesAreSafeOnEveryPlatform() {
        assertEquals("a b c", ObsidianVault.fileName("a/b\\c"));
        assertEquals("What is x", ObsidianVault.fileName("What is x?"));
        assertEquals("hidden", ObsidianVault.fileName(".hidden"));
        assertEquals("trailing", ObsidianVault.fileName("trailing..."));
        assertEquals("CON_", ObsidianVault.fileName("CON"));
        assertEquals("Untitled", ObsidianVault.fileName(" ** "));
        String longName = ObsidianVault.fileName("é".repeat(300));
        assertTrue(longName.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertEquals(100, longName.length());
    }

    @Test
    void listsFoldersSkippingHiddenOnes() throws IOException {
        Files.createDirectories(vault.resolve("Math/Algebra"));
        Files.createDirectories(vault.resolve("biology"));
        Files.createDirectories(vault.resolve(".obsidian/plugins"));
        Files.writeString(vault.resolve("Math/note.md"), "");
        assertEquals(
                List.of("biology", "Math", "Math/Algebra"),
                new ObsidianVault.ListFolders().invoke(vault.toFile(), null));
    }

    @Test
    void aMissingVaultIsAnError() {
        IOException e = assertThrows(
                IOException.class,
                () -> new ObsidianVault.ListFolders()
                        .invoke(vault.resolve("missing").toFile(), null));
        assertTrue(e.getMessage().contains("does not exist"));
    }
}
