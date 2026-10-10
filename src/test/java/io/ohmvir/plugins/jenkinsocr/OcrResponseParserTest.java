package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OcrResponseParserTest {
    @Test
    void parsesPlainJson() {
        RecognizeTextOutput output =
                OcrResponseParser.parse("{\"title\": \"Limits\", \"path\": \"Math/Calculus\", \"text\": \"x -> 0\"}");

        assertEquals("Limits", output.getTitle());
        assertEquals("Math/Calculus", output.getPath());
        assertEquals("x -> 0", output.getText());
    }

    @Test
    void parsesJsonWrappedInMarkdownFence() {
        RecognizeTextOutput output =
                OcrResponseParser.parse("```json\n{\"title\": \"T\", \"path\": \"P\", \"text\": \"body\"}\n```");

        assertEquals("T", output.getTitle());
        assertEquals("body", output.getText());
    }

    @Test
    void parsesJsonSurroundedByProse() {
        RecognizeTextOutput output = OcrResponseParser.parse(
                "Here is the transcription:\n{\"title\": \"T\", \"path\": \"P\", \"text\": \"body\"}\nLet me know!");

        assertEquals("P", output.getPath());
    }

    @Test
    void keepsEscapedLatexAndBracesInText() {
        RecognizeTextOutput output = OcrResponseParser.parse(
                "{\"title\": \"T\", \"path\": \"P\", \"text\": \"$$\\\\frac{a}{b}$$\\nnext line\"}");

        assertEquals("$$\\frac{a}{b}$$\nnext line", output.getText());
    }

    @Test
    void missingFieldIsReportedByName() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> OcrResponseParser.parse("{\"title\": \"T\", \"text\": \"x\"}"));

        assertTrue(e.getMessage().contains("'path'"), e.getMessage());
    }

    @Test
    void nullFieldIsReportedByName() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> OcrResponseParser.parse("{\"title\": null, \"path\": \"P\", \"text\": \"x\"}"));

        assertTrue(e.getMessage().contains("'title'"), e.getMessage());
    }

    @Test
    void nonStringFieldIsRejected() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> OcrResponseParser.parse("{\"title\": \"T\", \"path\": [\"P\"], \"text\": \"x\"}"));

        assertTrue(e.getMessage().contains("'path'"), e.getMessage());
    }

    @Test
    void invalidJsonIsRejected() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> OcrResponseParser.parse("{\"title\": \"T\", \"path\": \"P\", \"text\": \"\\section{x}\"}"));

        assertTrue(e.getMessage().startsWith("Model output is not valid JSON"), e.getMessage());
    }

    @Test
    void textWithoutJsonObjectIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OcrResponseParser.parse("I cannot read this image."));
        assertThrows(IllegalArgumentException.class, () -> OcrResponseParser.parse("[1, 2]"));
    }

    @Test
    void emptyTextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OcrResponseParser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> OcrResponseParser.parse("  "));
    }
}
