package io.ohmvir.plugins.jenkinsocr.notes.notion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import io.ohmvir.plugins.jenkinsocr.notes.content.Block;
import io.ohmvir.plugins.jenkinsocr.notes.content.Inline;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteContentParser;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotionBlockRendererTest {

    private static List<NotionBlock> render(String markdown) {
        return new NotionBlockRenderer().render(NoteContentParser.parse(markdown, FormulaOutputType.KATEX));
    }

    private static List<NotionBlock> render(Block... blocks) {
        return new NotionBlockRenderer().render(new NoteDocument(List.of(blocks), List.of()));
    }

    @Test
    void rendersNativeBlocksWithEquations() {
        List<NotionBlock> blocks = render("""
                # Title
                ##### Deep heading
                Text with $x^2$ and **bold [link](https://e.com)** and [relative](notes/a.md).

                $$\\int_0^1 x\\,dx$$

                > Quote $y$
                >
                > - inside

                ```cpp
                int main() {}
                ```
                ---
                """);
        List<String> types = blocks.stream().map(NotionBlock::getType).toList();
        assertEquals(List.of("heading_1", "heading_3", "paragraph", "equation", "quote", "code", "divider"), types);

        JsonNode paragraph = blocks.get(2).toJson(true).path("paragraph").path("rich_text");
        assertEquals(
                "Text with ", paragraph.path(0).path("text").path("content").asText());
        assertEquals("equation", paragraph.path(1).path("type").asText());
        assertEquals(
                "x^2", paragraph.path(1).path("equation").path("expression").asText());
        JsonNode boldLink = paragraph.path(4);
        assertEquals("link", boldLink.path("text").path("content").asText());
        assertEquals(
                "https://e.com", boldLink.path("text").path("link").path("url").asText());
        assertTrue(boldLink.path("annotations").path("bold").asBoolean());
        JsonNode relative = paragraph.path(6);
        assertEquals("relative", relative.path("text").path("content").asText());
        assertTrue(relative.path("text").path("link").isMissingNode(), "relative links are rejected by Notion");

        assertEquals(
                "\\int_0^1 x\\,dx",
                blocks.get(3).toJson(true).path("equation").path("expression").asText());

        JsonNode quote = blocks.get(4).toJson(true).path("quote");
        assertEquals(
                "Quote ",
                quote.path("rich_text").path(0).path("text").path("content").asText());
        assertEquals(
                "bulleted_list_item",
                quote.path("children").path(0).path("type").asText());

        JsonNode code = blocks.get(5).toJson(true).path("code");
        assertEquals("c++", code.path("language").asText());
        assertEquals(
                "int main() {}",
                code.path("rich_text").path(0).path("text").path("content").asText());
    }

    @Test
    void listItemsKeepTheirChildrenSeparateFromTheirContent() {
        List<NotionBlock> blocks = render("1. Step\n   - detail\n2. Next");
        assertEquals(2, blocks.size());
        assertEquals("numbered_list_item", blocks.get(0).getType());
        assertEquals(1, blocks.get(0).getChildren().size());
        assertEquals("bulleted_list_item", blocks.get(0).getChildren().get(0).getType());
        assertFalse(blocks.get(0).getContent().has("children"));
        assertTrue(blocks.get(0).toJson(true).path("numbered_list_item").has("children"));
        assertFalse(blocks.get(0).toJson(false).path("numbered_list_item").has("children"));
    }

    @Test
    void longTextIsSplitIntoTextObjectsOfAtMost2000Characters() {
        String text = "a".repeat(4500);
        JsonNode richText = render(new Block.Paragraph(List.of(new Inline.Text(text))))
                .get(0)
                .toJson(true)
                .path("paragraph")
                .path("rich_text");
        assertEquals(3, richText.size());
        assertEquals(
                2000, richText.path(0).path("text").path("content").asText().length());
        assertEquals(500, richText.path(2).path("text").path("content").asText().length());
    }

    @Test
    void chunkingNeverSplitsSurrogatePairs() {
        String text = "a".repeat(1999) + "😀" + "b";
        List<String> chunks = NotionBlockRenderer.chunk(text, 2000);
        assertEquals(List.of("a".repeat(1999), "😀b"), chunks);
    }

    @Test
    void moreThan100RichTextObjectsContinueInFollowingParagraphs() {
        List<Inline> content = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            content.add(new Inline.Equation("x_" + i));
        }
        Block.ListItem child = new Block.ListItem(false, List.of(new Inline.Text("child")), List.of());
        List<NotionBlock> blocks = render(new Block.ListItem(false, content, List.of(child)));
        assertEquals(
                List.of("bulleted_list_item", "paragraph"),
                blocks.stream().map(NotionBlock::getType).toList());
        assertEquals(100, blocks.get(0).getContent().path("rich_text").size());
        assertEquals(50, blocks.get(1).getContent().path("rich_text").size());
        assertTrue(blocks.get(0).getChildren().isEmpty());
        assertEquals(1, blocks.get(1).getChildren().size(), "children follow the last part of the text");
    }

    @Test
    void equationsOverTheLimitBecomeLatexCode() {
        String longExpression = "x+".repeat(600) + "1";
        NotionBlockRenderer renderer = new NotionBlockRenderer();
        List<NotionBlock> blocks = renderer.render(new NoteDocument(
                List.of(
                        new Block.Equation(longExpression),
                        new Block.Paragraph(List.of(new Inline.Equation(longExpression)))),
                List.of()));
        assertEquals("code", blocks.get(0).getType());
        assertEquals("latex", blocks.get(0).getContent().path("language").asText());
        JsonNode inline = blocks.get(1).getContent().path("rich_text").path(0);
        assertEquals("text", inline.path("type").asText());
        assertEquals(
                "$" + longExpression + "$", inline.path("text").path("content").asText());
        assertTrue(inline.path("annotations").path("code").asBoolean());
        assertEquals(2, renderer.getWarnings().size());
    }

    @Test
    void codeLanguagesAreMappedToNotionLanguages() {
        assertEquals("python", NotionBlockRenderer.language("py"));
        assertEquals("javascript", NotionBlockRenderer.language("JavaScript"));
        assertEquals("latex", NotionBlockRenderer.language("tex"));
        assertEquals("plain text", NotionBlockRenderer.language(""));
        assertEquals("plain text", NotionBlockRenderer.language("brainfuck"));
    }

    @Test
    void latexDocumentsRenderAsStructuredBlocks() {
        List<NotionBlock> blocks = new NotionBlockRenderer()
                .render(NoteContentParser.parse(
                        "\\section*{Laws}\n\\begin{itemize}\\item $F = ma$\\end{itemize}\n\\begin{align*}a&=b\\end{align*}",
                        FormulaOutputType.LATEX));
        assertEquals(
                List.of("heading_1", "bulleted_list_item", "equation"),
                blocks.stream().map(NotionBlock::getType).toList());
        assertEquals(
                "\\begin{aligned}a&=b\\end{aligned}",
                blocks.get(2).getContent().path("expression").asText());
    }
}
