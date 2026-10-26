package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import io.ohmvir.plugins.jenkinsocr.notes.content.Block;
import io.ohmvir.plugins.jenkinsocr.notes.content.Inline;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteContentParser;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteDocument;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

class EnmlRendererTest {

    private static String render(String markdown) {
        return EnmlRenderer.render(NoteContentParser.parse(markdown, FormulaOutputType.KATEX));
    }

    /** ENML must be well-formed XML. */
    private static void assertWellFormed(String enml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.newDocumentBuilder().parse(new InputSource(new StringReader(enml)));
    }

    @Test
    void rendersStructureAndKeepsFormulasAsTexSource() throws Exception {
        String body = render("""
                # Title
                Energy is $E = mc^2$ and **bold *both*** [link](https://e.com?a=1&b=2)
                second line

                $$\\frac{a}{b} < c$$

                1. one
                   - nested
                2. two

                > quote

                ```
                if (a < b && c) {}
                ```
                ---
                """);
        assertEquals(
                "<h1>Title</h1>"
                        + "<p>Energy is <code>$E = mc^2$</code> and <b>bold </b><b><i>both</i></b> "
                        + "<a href=\"https://e.com?a=1&amp;b=2\">link</a><br/>second line</p>"
                        + "<pre>$$\\frac{a}{b} &lt; c$$</pre>"
                        + "<ol><li>one<ul><li>nested</li></ul></li><li>two</li></ol>"
                        + "<blockquote><p>quote</p></blockquote>"
                        + "<pre>if (a &lt; b &amp;&amp; c) {}</pre>"
                        + "<hr/>",
                body);
        assertWellFormed(EnmlRenderer.document(body));
    }

    @Test
    void unsupportedLinksAreDroppedAndInvalidXmlCharactersRemoved() throws Exception {
        String body = EnmlRenderer.render(new NoteDocument(
                List.of(new Block.Paragraph(List.of(
                        new Inline.Text("js", Inline.Style.PLAIN.withLink("javascript:alert(1)")),
                        new Inline.Text(" bad\u0001char \"quoted\" 'single' 😀")))),
                List.of()));
        assertEquals("<p>js badchar &quot;quoted&quot; &#39;single&#39; 😀</p>", body);
        assertWellFormed(EnmlRenderer.document(body));
    }

    @Test
    void appendsBeforeTheClosingTag() throws IOException {
        String existing = EnmlRenderer.document("<p>old</p>");
        assertEquals(EnmlRenderer.document("<p>old</p><hr/><p>new</p>"), EnmlRenderer.append(existing, "<p>new</p>"));
        assertThrows(IOException.class, () -> EnmlRenderer.append("<html/>", "<p>new</p>"));
    }

    @Test
    void documentsHaveTheEnmlDoctype() {
        assertEquals(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<!DOCTYPE en-note SYSTEM \"http://xml.evernote.com/pub/enml2.dtd\">\n<en-note></en-note>",
                EnmlRenderer.document(""));
    }
}
