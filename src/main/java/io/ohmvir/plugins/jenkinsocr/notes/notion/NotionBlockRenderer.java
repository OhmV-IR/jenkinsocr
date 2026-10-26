package io.ohmvir.plugins.jenkinsocr.notes.notion;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ohmvir.plugins.jenkinsocr.notes.content.Block;
import io.ohmvir.plugins.jenkinsocr.notes.content.Inline;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteDocument;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a {@link NoteDocument} as native Notion blocks: headings, paragraphs, lists, quotes, code blocks,
 * dividers, equation blocks for display math and inline equations for inline math.
 *
 * <p>Notion's per-object limits are respected by splitting content: text objects hold at most
 * {@value #MAX_TEXT_LENGTH} characters, rich text arrays at most {@value #MAX_RICH_TEXT_ELEMENTS} elements, and
 * equations at most {@value #MAX_EQUATION_LENGTH} characters (longer equations are kept as LaTeX code instead).
 */
public final class NotionBlockRenderer {
    static final int MAX_TEXT_LENGTH = 2000;
    static final int MAX_EQUATION_LENGTH = 1000;
    static final int MAX_RICH_TEXT_ELEMENTS = 100;
    static final int MAX_URL_LENGTH = 2000;

    private static final Pattern SUPPORTED_LINK = Pattern.compile("^(?i)(?:https?://|mailto:)\\S+$");

    /** The code block languages accepted by Notion. */
    private static final Set<String> LANGUAGES = Set.of(
            "abap",
            "arduino",
            "bash",
            "basic",
            "c",
            "clojure",
            "coffeescript",
            "c++",
            "c#",
            "css",
            "dart",
            "diff",
            "docker",
            "elixir",
            "elm",
            "erlang",
            "flow",
            "fortran",
            "f#",
            "gherkin",
            "glsl",
            "go",
            "graphql",
            "groovy",
            "haskell",
            "html",
            "java",
            "javascript",
            "json",
            "julia",
            "kotlin",
            "latex",
            "less",
            "lisp",
            "livescript",
            "lua",
            "makefile",
            "markdown",
            "markup",
            "matlab",
            "mermaid",
            "nix",
            "objective-c",
            "ocaml",
            "pascal",
            "perl",
            "php",
            "plain text",
            "powershell",
            "prolog",
            "protobuf",
            "python",
            "r",
            "reason",
            "ruby",
            "rust",
            "sass",
            "scala",
            "scheme",
            "scss",
            "shell",
            "sql",
            "swift",
            "typescript",
            "vb.net",
            "verilog",
            "vhdl",
            "visual basic",
            "webassembly",
            "xml",
            "yaml");

    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("", "plain text"),
            Map.entry("text", "plain text"),
            Map.entry("txt", "plain text"),
            Map.entry("plaintext", "plain text"),
            Map.entry("sh", "shell"),
            Map.entry("zsh", "shell"),
            Map.entry("console", "shell"),
            Map.entry("js", "javascript"),
            Map.entry("jsx", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "typescript"),
            Map.entry("py", "python"),
            Map.entry("python3", "python"),
            Map.entry("rb", "ruby"),
            Map.entry("rs", "rust"),
            Map.entry("kt", "kotlin"),
            Map.entry("cpp", "c++"),
            Map.entry("cxx", "c++"),
            Map.entry("h", "c"),
            Map.entry("hpp", "c++"),
            Map.entry("cs", "c#"),
            Map.entry("csharp", "c#"),
            Map.entry("fsharp", "f#"),
            Map.entry("objc", "objective-c"),
            Map.entry("tex", "latex"),
            Map.entry("katex", "latex"),
            Map.entry("md", "markdown"),
            Map.entry("yml", "yaml"),
            Map.entry("dockerfile", "docker"),
            Map.entry("ps1", "powershell"),
            Map.entry("vb", "visual basic"),
            Map.entry("wasm", "webassembly"),
            Map.entry("proto", "protobuf"),
            Map.entry("make", "makefile"),
            Map.entry("ml", "ocaml"),
            Map.entry("hs", "haskell"),
            Map.entry("clj", "clojure"),
            Map.entry("ex", "elixir"),
            Map.entry("erl", "erlang"),
            Map.entry("pl", "perl"),
            Map.entry("m", "matlab"));

    private final Set<String> warnings = new LinkedHashSet<>();

    /**
     * Renders the document's blocks.
     */
    public List<NotionBlock> render(NoteDocument document) {
        return renderBlocks(document.blocks());
    }

    /**
     * Problems found while rendering, such as equations that exceeded Notion's limits.
     */
    public List<String> getWarnings() {
        return List.copyOf(warnings);
    }

    private List<NotionBlock> renderBlocks(List<Block> blocks) {
        List<NotionBlock> result = new ArrayList<>();
        for (Block block : blocks) {
            result.addAll(renderBlock(block));
        }
        return result;
    }

    private List<NotionBlock> renderBlock(Block block) {
        if (block instanceof Block.Heading heading) {
            return textBlocks("heading_" + Math.min(heading.level(), 3), heading.content(), List.of());
        }
        if (block instanceof Block.Paragraph paragraph) {
            return textBlocks("paragraph", paragraph.content(), List.of());
        }
        if (block instanceof Block.ListItem item) {
            String type = item.ordered() ? "numbered_list_item" : "bulleted_list_item";
            return textBlocks(type, item.content(), renderBlocks(item.children()));
        }
        if (block instanceof Block.Quote quote) {
            List<Block> children = quote.children();
            List<Inline> text = List.of();
            if (!children.isEmpty() && children.get(0) instanceof Block.Paragraph first) {
                text = first.content();
                children = children.subList(1, children.size());
            }
            return textBlocks("quote", text, renderBlocks(children));
        }
        if (block instanceof Block.Code code) {
            return codeBlocks(language(code.language()), code.code());
        }
        if (block instanceof Block.Equation equation) {
            String expression = equation.expression();
            if (expression.length() > MAX_EQUATION_LENGTH) {
                warnings.add("A display equation is longer than Notion's limit of " + MAX_EQUATION_LENGTH
                        + " characters, so it was added as a LaTeX code block instead.");
                return codeBlocks("latex", expression);
            }
            ObjectNode content = JsonNodeFactory.instance.objectNode().put("expression", expression);
            return List.of(new NotionBlock("equation", content, List.of()));
        }
        if (block instanceof Block.Divider) {
            return List.of(new NotionBlock("divider", JsonNodeFactory.instance.objectNode(), List.of()));
        }
        throw new IllegalArgumentException(
                "Unknown block type: " + block.getClass().getName());
    }

    /**
     * Creates a block holding rich text. If the text needs more than {@value #MAX_RICH_TEXT_ELEMENTS} rich text
     * objects, the rest continues in paragraphs below it, and the children go under the last of those paragraphs
     * so that the reading order is kept.
     */
    private List<NotionBlock> textBlocks(String type, List<Inline> content, List<NotionBlock> children) {
        List<List<ObjectNode>> chunks = partition(richText(content), MAX_RICH_TEXT_ELEMENTS);
        List<NotionBlock> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            ArrayNode richText = body.putArray("rich_text");
            chunks.get(i).forEach(richText::add);
            boolean last = i == chunks.size() - 1;
            result.add(new NotionBlock(i == 0 ? type : "paragraph", body, last ? children : List.of()));
        }
        return result;
    }

    private List<NotionBlock> codeBlocks(String language, String code) {
        List<ObjectNode> richText = new ArrayList<>();
        for (String chunk : chunk(code, MAX_TEXT_LENGTH)) {
            richText.add(textObject(chunk, Inline.Style.PLAIN, null));
        }
        List<NotionBlock> result = new ArrayList<>();
        for (List<ObjectNode> part : partition(richText, MAX_RICH_TEXT_ELEMENTS)) {
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("language", language);
            part.forEach(body.putArray("rich_text")::add);
            result.add(new NotionBlock("code", body, List.of()));
        }
        return result;
    }

    private List<ObjectNode> richText(List<Inline> content) {
        List<ObjectNode> result = new ArrayList<>();
        for (Inline inline : content) {
            if (inline instanceof Inline.Text text) {
                String link = supportedLink(text.style().link());
                for (String chunk : chunk(text.text(), MAX_TEXT_LENGTH)) {
                    result.add(textObject(chunk, text.style(), link));
                }
            } else if (inline instanceof Inline.Equation equation) {
                String expression = equation.expression();
                if (expression.length() <= MAX_EQUATION_LENGTH) {
                    ObjectNode object = JsonNodeFactory.instance.objectNode();
                    object.put("type", "equation");
                    object.putObject("equation").put("expression", expression);
                    result.add(object);
                } else {
                    warnings.add("An inline equation is longer than Notion's limit of " + MAX_EQUATION_LENGTH
                            + " characters, so it was added as LaTeX code instead.");
                    for (String chunk : chunk("$" + expression + "$", MAX_TEXT_LENGTH)) {
                        result.add(textObject(chunk, Inline.Style.PLAIN.withCode(), null));
                    }
                }
            }
        }
        return result;
    }

    private static ObjectNode textObject(String content, Inline.Style style, String link) {
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        object.put("type", "text");
        ObjectNode text = object.putObject("text");
        text.put("content", content);
        if (link != null) {
            text.putObject("link").put("url", link);
        }
        ObjectNode annotations = JsonNodeFactory.instance.objectNode();
        if (style.bold()) {
            annotations.put("bold", true);
        }
        if (style.italic()) {
            annotations.put("italic", true);
        }
        if (style.strikethrough()) {
            annotations.put("strikethrough", true);
        }
        if (style.underline()) {
            annotations.put("underline", true);
        }
        if (style.code()) {
            annotations.put("code", true);
        }
        if (!annotations.isEmpty()) {
            object.set("annotations", annotations);
        }
        return object;
    }

    /**
     * Notion rejects requests containing links it considers invalid (such as relative URLs), so only absolute web
     * and mail links are kept; the link text is kept either way.
     */
    private static String supportedLink(String link) {
        if (link == null) {
            return null;
        }
        String trimmed = link.strip();
        return trimmed.length() <= MAX_URL_LENGTH
                        && SUPPORTED_LINK.matcher(trimmed).matches()
                ? trimmed
                : null;
    }

    static String language(String language) {
        String normalized = language.strip().toLowerCase(Locale.ROOT);
        if (LANGUAGES.contains(normalized)) {
            return normalized;
        }
        return LANGUAGE_ALIASES.getOrDefault(normalized, "plain text");
    }

    /**
     * Splits text into pieces of at most {@code maxLength} UTF-16 code units, never splitting a surrogate pair.
     */
    static List<String> chunk(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxLength);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            parts.add(items.subList(i, Math.min(items.size(), i + size)));
        }
        if (parts.isEmpty()) {
            parts.add(List.of());
        }
        return parts;
    }
}
