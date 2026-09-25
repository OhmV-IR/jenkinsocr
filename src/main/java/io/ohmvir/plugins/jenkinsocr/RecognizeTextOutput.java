package io.ohmvir.plugins.jenkinsocr;

import lombok.Getter;

import java.io.Serializable;

public class RecognizeTextOutput implements Serializable {
    private @Getter final String text;
    private @Getter final String title;

    public RecognizeTextOutput(String text, String title) {
        this.text = text;
        this.title = title;
    }
}
