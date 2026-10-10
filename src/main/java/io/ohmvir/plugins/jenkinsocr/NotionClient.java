package io.ohmvir.plugins.jenkinsocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * Minimal client for the parts of the Notion API used by this plugin.
 * Kept free of Jenkins types so it can be unit tested against a fake HTTP server.
 */
public class NotionClient {
    static final URI DEFAULT_BASE_URI = URI.create("https://api.notion.com/v1/");
    static final String NOTION_VERSION = "2022-06-28";
    private static final HttpClient DEFAULT_HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RATE_LIMIT_RETRIES = 5;

    private final URI baseUri;
    private final String apiToken;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final long maxRetryDelayMillis;

    public NotionClient(String apiToken) {
        this(DEFAULT_BASE_URI, apiToken, DEFAULT_HTTP_CLIENT, Duration.ofSeconds(60), 30_000L);
    }

    NotionClient(
            URI baseUri, String apiToken, HttpClient httpClient, Duration requestTimeout, long maxRetryDelayMillis) {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("Notion API token must not be blank");
        }
        this.baseUri = baseUri;
        this.apiToken = apiToken;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.maxRetryDelayMillis = maxRetryDelayMillis;
    }

    /** A page nested directly under another page. */
    record ChildPage(String id, String title) {}

    /**
     * Traverses or creates pages along a path (e.g. "Math/Algebra"),
     * creates or finds the final page using pageTitle, and appends
     * a KaTeX equation block to that final destination page.
     *
     * @param rootPageId The root Notion page ID where path resolution begins
     * @param path       Path elements separated by slashes (e.g., "Math/Semester 1/Calculus")
     * @param pageTitle  The name of the final page to hold the KaTeX block
     * @param katexText  Raw LaTeX/KaTeX string
     * @return The ID of the final created/resolved page
     */
    public String createPageAtPathWithKatex(String rootPageId, String path, String pageTitle, String katexText)
            throws IOException, InterruptedException {
        String currentParentId = rootPageId;

        if (path != null && !path.isBlank()) {
            for (String segment : path.split("/")) {
                String trimmed = segment.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                currentParentId = findOrCreateChildPage(currentParentId, trimmed);
            }
        }

        if (pageTitle != null && !pageTitle.isBlank()) {
            currentParentId = findOrCreateChildPage(currentParentId, pageTitle.trim());
        }

        appendKatexBlock(currentParentId, katexText);
        return currentParentId;
    }

    private String findOrCreateChildPage(String parentId, String title) throws IOException, InterruptedException {
        String existingId = findChildPageId(parentId, title);
        return existingId != null ? existingId : createBlankPage(parentId, title);
    }

    /**
     * Finds a page directly under parentId whose title matches (ignoring case and surrounding whitespace).
     * Lists the parent's children rather than using the search endpoint, which matches partial titles
     * and lags behind recently created pages.
     */
    String findChildPageId(String parentId, String title) throws IOException, InterruptedException {
        for (ChildPage child : listChildPages(parentId)) {
            if (child.title().trim().equalsIgnoreCase(title)) {
                return child.id();
            }
        }
        return null;
    }

    String createBlankPage(String parentId, String title) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();

        ObjectNode parent = body.putObject("parent");
        parent.put("type", "page_id");
        parent.put("page_id", parentId);

        ObjectNode properties = body.putObject("properties");
        ObjectNode titleProp = properties.putObject("title");
        ArrayNode titleArray = titleProp.putArray("title");
        ObjectNode textObj = titleArray.addObject();
        textObj.putObject("text").put("content", title);

        JsonNode root = send(newRequest("pages").POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        String id = root.path("id").asText("");
        if (id.isEmpty()) {
            throw new IOException("Notion did not return an id for created page '" + title + "'");
        }
        return id;
    }

    void appendKatexBlock(String pageId, String katexText) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode children = body.putArray("children");

        ObjectNode block = children.addObject();
        block.put("object", "block");
        block.put("type", "equation");
        block.putObject("equation").put("expression", katexText);

        send(newRequest("blocks/" + encodePathSegment(pageId) + "/children")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    /** Lists every page directly under parentId, following pagination. */
    List<ChildPage> listChildPages(String parentId) throws IOException, InterruptedException {
        List<ChildPage> pages = new ArrayList<>();
        String startCursor = null;
        do {
            StringBuilder url = new StringBuilder("blocks/")
                    .append(encodePathSegment(parentId))
                    .append("/children?page_size=100");
            if (startCursor != null) {
                url.append("&start_cursor=").append(URLEncoder.encode(startCursor, StandardCharsets.UTF_8));
            }

            JsonNode root = send(newRequest(url.toString()).GET());
            for (JsonNode block : root.path("results")) {
                // Notion represents sub-folders/sub-pages as blocks of type "child_page"
                if ("child_page".equals(block.path("type").asText())) {
                    pages.add(new ChildPage(
                            block.path("id").asText(),
                            block.path("child_page").path("title").asText()));
                }
            }

            startCursor = root.path("has_more").asBoolean(false)
                    ? root.path("next_cursor").asText(null)
                    : null;
        } while (startCursor != null);
        return pages;
    }

    /**
     * Recursively traverses the Notion workspace starting at rootPageId
     * and returns a list of all valid directory path categories (e.g., "Math/Algebra").
     *
     * @param rootPageId The root page ID to start listing directories from
     * @return List of relative path strings representing category folders
     */
    public List<String> getDirectoryPaths(String rootPageId) throws IOException, InterruptedException {
        List<String> directoryPaths = new ArrayList<>();
        collectDirectoryPaths(rootPageId, "", directoryPaths);
        return directoryPaths;
    }

    private void collectDirectoryPaths(String parentId, String currentPath, List<String> directoryPaths)
            throws IOException, InterruptedException {
        for (ChildPage child : listChildPages(parentId)) {
            String newPath = currentPath.isEmpty() ? child.title() : currentPath + "/" + child.title();
            directoryPaths.add(newPath);
            collectDirectoryPaths(child.id(), newPath, directoryPaths);
        }
    }

    /**
     * Formats the directory paths into a bulleted tree string suitable for LLM prompts.
     */
    public String getDirectoryTreeFormatted(String rootPageId) throws IOException, InterruptedException {
        return formatDirectoryTree(getDirectoryPaths(rootPageId));
    }

    static String formatDirectoryTree(List<String> paths) {
        StringBuilder tree = new StringBuilder();
        for (String path : paths) {
            int depth = path.split("/").length - 1;
            String folderName = path.substring(path.lastIndexOf('/') + 1);
            tree.append("  ".repeat(depth))
                    .append("- ")
                    .append(folderName)
                    .append(" (Path: ")
                    .append(path)
                    .append(")\n");
        }
        return tree.toString();
    }

    private HttpRequest.Builder newRequest(String relativePath) {
        return HttpRequest.newBuilder()
                .uri(baseUri.resolve(relativePath))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiToken)
                .header("Notion-Version", NOTION_VERSION)
                .header("Content-Type", "application/json");
    }

    /**
     * Sends the request, retrying when rate limited, and fails on any non-2xx response
     * so that Notion errors are not silently ignored.
     */
    private JsonNode send(HttpRequest.Builder requestBuilder) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder.build();
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                Thread.sleep(retryDelayMillis(response, attempt));
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new NotionApiException(status, describeError(response.body()), request);
            }
            String body = response.body();
            return body == null || body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        }
    }

    private long retryDelayMillis(HttpResponse<String> response, int attempt) {
        long delay = 1000L << attempt;
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        if (retryAfter != null) {
            try {
                delay = (long) (Double.parseDouble(retryAfter.trim()) * 1000);
            } catch (NumberFormatException ignored) {
                // Fall back to exponential backoff
            }
        }
        return Math.max(0, Math.min(delay, maxRetryDelayMillis));
    }

    private static String describeError(String body) {
        if (body == null || body.isBlank()) {
            return "(empty response body)";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            String code = root.path("code").asText("");
            String message = root.path("message").asText("");
            if (!message.isEmpty()) {
                return code.isEmpty() ? message : code + ": " + message;
            }
        } catch (IOException ignored) {
            // Not JSON, report the raw body below
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    private static String encodePathSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("Notion page ID must not be blank");
        }
        return URLEncoder.encode(segment.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Thrown when the Notion API answers with a non-successful status code. */
    public static class NotionApiException extends IOException {
        private static final long serialVersionUID = 1L;
        private final @Getter int statusCode;

        NotionApiException(int statusCode, String detail, HttpRequest request) {
            super("Notion API request " + request.method() + " " + request.uri().getPath() + " failed with HTTP "
                    + statusCode + ": " + detail);
            this.statusCode = statusCode;
        }
    }
}
