package io.ohmvir.plugins.jenkinsocr;

import java.io.Serializable;
import lombok.Getter;

public class RecognizeTextOutput implements Serializable {
    private @Getter final String text;
    private @Getter final String path;
    private @Getter final String title;

    public RecognizeTextOutput(String text, String path, String title) {
        this.text = text;
        this.path = path;
        this.title = title;
    }
}
