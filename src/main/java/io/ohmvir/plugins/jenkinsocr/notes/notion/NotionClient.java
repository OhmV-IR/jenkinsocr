package io.ohmvir.plugins.jenkinsocr.notes.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.ProxyConfiguration;
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
import java.util.Optional;

/**
 * A minimal client for the parts of the Notion API used to store notes: listing and creating pages, and
 * appending blocks.
 *
 * <p>Rate limited (HTTP 429), conflicting (409) and temporarily failing (5xx) requests are retried, honouring the
 * {@code Retry-After} header. Any other error is raised as a {@link NotionApiException} carrying Notion's own
 * error message.
 */
public class NotionClient {
    public static final URI DEFAULT_BASE_URI = URI.create("https://api.notion.com/v1/");
    static final String NOTION_VERSION = "2022-06-28";
    /** Notion accepts at most 100 blocks in one "append block children" request. */
    static final int MAX_BLOCKS_PER_REQUEST = 100;

    private static final int MAX_ATTEMPTS = 5;
    private static final long MAX_RETRY_DELAY_MILLIS = 60_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI baseUri;
    private final String token;
    private final Sleeper sleeper;

    /**
     * A page directly below another page.
     */
    public record ChildPage(String id, String title) {}

    /**
     * Waits between retries; replaceable in tests.
     */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public NotionClient(HttpClient httpClient, String token) {
        this(httpClient, DEFAULT_BASE_URI, token, Thread::sleep);
    }

    NotionClient(HttpClient httpClient, URI baseUri, String token, Sleeper sleeper) {
        this.httpClient = httpClient;
        this.baseUri = baseUri;
        this.token = token;
        this.sleeper = sleeper;
    }

    /**
     * Lists the pages directly below a page, skipping archived ones.
     */
    public List<ChildPage> listChildPages(String parentId) throws IOException, InterruptedException {
        List<ChildPage> pages = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode response = send("GET", childrenPath(parentId, cursor), null);
            for (JsonNode block : response.path("results")) {
                if ("child_page".equals(block.path("type").asText())
                        && !block.path("archived").asBoolean(false)
                        && !block.path("in_trash").asBoolean(false)) {
                    pages.add(new ChildPage(
                            block.path("id").asText(),
                            block.path("child_page").path("title").asText()));
                }
            }
            cursor = response.path("has_more").asBoolean(false)
                    ? response.path("next_cursor").asText(null)
                    : null;
        } while (cursor != null);
        return pages;
    }

    private static String childrenPath(String parentId, String cursor) {
        String path = "blocks/" + parentId + "/children?page_size=100";
        return cursor == null ? path : path + "&start_cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
    }

    /**
     * Finds the page directly below {@code parentId} with the given title, preferring an exact match over a
     * case-insensitive one.
     *
     * @return the page's ID, or empty if there is no such page
     */
    public Optional<String> findChildPage(String parentId, String title) throws IOException, InterruptedException {
        String wanted = title.strip();
        List<ChildPage> pages = listChildPages(parentId);
        return pages.stream()
                .filter(page -> page.title().strip().equals(wanted))
                .findFirst()
                .or(() -> pages.stream()
                        .filter(page -> page.title().strip().equalsIgnoreCase(wanted))
                        .findFirst())
                .map(ChildPage::id);
    }

    /**
     * Creates an empty page below {@code parentId}.
     *
     * @return the new page's ID
     */
    public String createPage(String parentId, String title) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("parent").put("type", "page_id").put("page_id", parentId);
        ArrayNode titleText = body.putObject("properties").putObject("title").putArray("title");
        List<String> chunks = NotionBlockRenderer.chunk(title, NotionBlockRenderer.MAX_TEXT_LENGTH);
        String content = chunks.isEmpty() ? "" : chunks.get(0);
        titleText.addObject().put("type", "text").putObject("text").put("content", content);
        JsonNode page = send("POST", "pages", body);
        String id = page.path("id").asText(null);
        if (id == null) {
            throw new IOException("Notion did not return the ID of the created page '" + title + "'");
        }
        return id;
    }

    /**
     * Appends blocks, with all of their descendants, to the end of a page or block.
     *
     * <p>Blocks are sent in batches of at most {@value #MAX_BLOCKS_PER_REQUEST}. A block's children are sent in the
     * same request when they have no children of their own and fit in the batch; deeper trees are appended level by
     * level to the IDs Notion returns for the created blocks, so any nesting depth is preserved.
     */
    public void appendBlocks(String parentId, List<NotionBlock> blocks) throws IOException, InterruptedException {
        int index = 0;
        while (index < blocks.size()) {
            List<NotionBlock> batch = new ArrayList<>();
            List<Boolean> childrenInline = new ArrayList<>();
            int size = 0;
            while (index < blocks.size()) {
                NotionBlock block = blocks.get(index);
                boolean inline = !block.getChildren().isEmpty()
                        && !block.hasGrandchildren()
                        && block.getChildren().size() < MAX_BLOCKS_PER_REQUEST;
                int blockSize = 1 + (inline ? block.getChildren().size() : 0);
                if (!batch.isEmpty() && size + blockSize > MAX_BLOCKS_PER_REQUEST) {
                    break;
                }
                batch.add(block);
                childrenInline.add(inline);
                size += blockSize;
                index++;
            }

            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode children = body.putArray("children");
            for (int i = 0; i < batch.size(); i++) {
                children.add(batch.get(i).toJson(childrenInline.get(i)));
            }
            JsonNode created =
                    send("PATCH", "blocks/" + parentId + "/children", body).path("results");

            for (int i = 0; i < batch.size(); i++) {
                NotionBlock block = batch.get(i);
                if (childrenInline.get(i) || block.getChildren().isEmpty()) {
                    continue;
                }
                String createdId = created.path(i).path("id").asText(null);
                if (created.size() != batch.size() || createdId == null) {
                    throw new IOException("Notion did not return the IDs of the appended blocks, so their nested"
                            + " content could not be added");
                }
                appendBlocks(createdId, block.getChildren());
            }
        }
    }

    private JsonNode send(String method, String path, JsonNode body) throws IOException, InterruptedException {
        URI uri = baseUri.resolve(path);
        for (int attempt = 1; ; attempt++) {
            HttpRequest.Builder request = ProxyConfiguration.newHttpRequestBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", NOTION_VERSION)
                    .header("Accept", "application/json");
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(
                                method,
                                HttpRequest.BodyPublishers.ofString(
                                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return MAPPER.readTree(response.body());
            }
            if (isRetryable(status) && attempt < MAX_ATTEMPTS) {
                sleeper.sleep(retryDelayMillis(response, attempt));
                continue;
            }
            throw NotionApiException.fromResponse(status, response.body(), MAPPER);
        }
    }

    private static boolean isRetryable(int status) {
        return status == 409 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static long retryDelayMillis(HttpResponse<?> response, int attempt) {
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                long seconds = Long.parseLong(retryAfter.get().strip());
                return Math.min(MAX_RETRY_DELAY_MILLIS, Math.max(0, seconds) * 1000);
            } catch (NumberFormatException e) {
                // an HTTP date; fall back to exponential backoff
            }
        }
        return Math.min(MAX_RETRY_DELAY_MILLIS, 1000L << (attempt - 1));
    }
}
