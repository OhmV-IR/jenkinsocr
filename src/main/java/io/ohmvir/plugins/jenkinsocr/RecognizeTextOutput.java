package io.ohmvir.plugins.jenkinsocr;

import java.io.Serializable;
import lombok.Getter;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;

public class RecognizeTextOutput implements Serializable {
    private static final long serialVersionUID = 1L;

    // Whitelisted so sandboxed pipelines can read e.g. ocrOutput.text without script approval
    private @Getter(onMethod_ = {@Whitelisted}) final String text;
    private @Getter(onMethod_ = {@Whitelisted}) final String path;
    private @Getter(onMethod_ = {@Whitelisted}) final String title;

    public RecognizeTextOutput(String text, String path, String title) {
        this.text = text;
        this.path = path;
        this.title = title;
    }
}
