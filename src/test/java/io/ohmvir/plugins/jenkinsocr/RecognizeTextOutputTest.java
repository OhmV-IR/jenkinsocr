package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.junit.jupiter.api.Test;

class RecognizeTextOutputTest {
    @Test
    void gettersAreWhitelistedForSandboxedPipelines() throws Exception {
        for (String getter : new String[] {"getText", "getPath", "getTitle"}) {
            assertTrue(
                    RecognizeTextOutput.class.getMethod(getter).isAnnotationPresent(Whitelisted.class),
                    getter + " should be @Whitelisted");
        }
    }

    @Test
    void survivesSerialization() throws Exception {
        RecognizeTextOutput original = new RecognizeTextOutput("text", "Math/Algebra", "Title");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        RecognizeTextOutput copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (RecognizeTextOutput) in.readObject();
        }

        assertEquals("text", copy.getText());
        assertEquals("Math/Algebra", copy.getPath());
        assertEquals("Title", copy.getTitle());
    }
}
