package io.ohmvir.plugins.jenkinsocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory stand-in for the subset of the Notion API used by {@link NotionClient}. */
class FakeNotionServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code rawPath} is the path as sent, before percent-decoding. */
    record RecordedRequest(String method, String rawPath, String query, Map<String, String> headers, String body) {
        JsonNode json() throws IOException {
            return MAPPER.readTree(body);
        }
    }

    record Page(String id, String title, String parentId) {}

    private final HttpServer server;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, List<String>> childBlocks = new LinkedHashMap<>();
    private final Map<String, List<JsonNode>> appendedBlocks = new LinkedHashMap<>();
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger idCounter = new AtomicInteger();
    private int childrenPageSize = 100;
    private int rateLimitedResponsesRemaining = 0;
    private int forcedErrorStatus = 0;
    private String forcedErrorBody;
    private String forcedErrorPathPrefix;

    FakeNotionServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/", this::handle);
        server.start();
    }

    URI baseUri() {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/v1/");
    }

    synchronized String addPage(String parentId, String title) {
        String id = "page-" + idCounter.incrementAndGet();
        pages.put(id, new Page(id, title, parentId));
        childBlocks.computeIfAbsent(parentId, k -> new ArrayList<>()).add(id);
        return id;
    }

    /** Adds a non-page block (e.g. a paragraph) that listings must skip. */
    synchronized void addParagraph(String parentId) {
        String id = "paragraph-" + idCounter.incrementAndGet();
        childBlocks.computeIfAbsent(parentId, k -> new ArrayList<>()).add(id);
    }

    synchronized Page page(String id) {
        return pages.get(id);
    }

    synchronized List<Page> childPages(String parentId) {
        List<Page> result = new ArrayList<>();
        for (String id : childBlocks.getOrDefault(parentId, List.of())) {
            if (pages.containsKey(id)) {
                result.add(pages.get(id));
            }
        }
        return result;
    }

    synchronized List<JsonNode> appendedBlocks(String pageId) {
        return appendedBlocks.getOrDefault(pageId, List.of());
    }

    List<RecordedRequest> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    synchronized void setChildrenPageSize(int childrenPageSize) {
        this.childrenPageSize = childrenPageSize;
    }

    synchronized void rateLimitNextResponses(int count) {
        this.rateLimitedResponsesRemaining = count;
    }

    synchronized void failRequestsTo(String pathPrefix, int status, String body) {
        this.forcedErrorPathPrefix = pathPrefix;
        this.forcedErrorStatus = status;
        this.forcedErrorBody = body;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String rawPath = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            requests.add(new RecordedRequest(method, rawPath, query, headers, body));

            synchronized (this) {
                if (rateLimitedResponsesRemaining > 0) {
                    rateLimitedResponsesRemaining--;
                    exchange.getResponseHeaders().add("Retry-After", "0");
                    respond(
                            exchange,
                            429,
                            "{\"object\":\"error\",\"code\":\"rate_limited\",\"message\":\"slow down\"}");
                    return;
                }
                if (forcedErrorPathPrefix != null && path.startsWith(forcedErrorPathPrefix)) {
                    respond(exchange, forcedErrorStatus, forcedErrorBody);
                    return;
                }
                String rest = path.substring("/v1/".length());
                if (method.equals("POST") && rest.equals("pages")) {
                    handleCreatePage(exchange, body);
                } else if (rest.startsWith("blocks/") && rest.endsWith("/children")) {
                    String blockId = rest.substring("blocks/".length(), rest.length() - "/children".length());
                    if (method.equals("GET")) {
                        handleListChildren(exchange, blockId, query);
                    } else if (method.equals("PATCH")) {
                        handleAppend(exchange, blockId, body);
                    } else {
                        respond(exchange, 405, "{}");
                    }
                } else {
                    respond(exchange, 404, "{\"object\":\"error\",\"code\":\"not_found\",\"message\":\"no route\"}");
                }
            }
        }
    }

    private void handleCreatePage(HttpExchange exchange, String body) throws IOException {
        JsonNode json = MAPPER.readTree(body);
        String parentId = json.path("parent").path("page_id").asText();
        String title = json.path("properties")
                .path("title")
                .path("title")
                .path(0)
                .path("text")
                .path("content")
                .asText();
        String id = addPage(parentId, title);
        ObjectNode response = MAPPER.createObjectNode();
        response.put("object", "page");
        response.put("id", id);
        respond(exchange, 200, response.toString());
    }

    private void handleListChildren(HttpExchange exchange, String blockId, String query) throws IOException {
        int start = 0;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("start_cursor=")) {
                    start = Integer.parseInt(param.substring("start_cursor=".length()));
                }
            }
        }
        List<String> children = childBlocks.getOrDefault(blockId, List.of());
        int end = Math.min(children.size(), start + childrenPageSize);

        ObjectNode response = MAPPER.createObjectNode();
        response.put("object", "list");
        ArrayNode results = response.putArray("results");
        for (String childId : children.subList(start, end)) {
            ObjectNode block = results.addObject();
            block.put("object", "block");
            block.put("id", childId);
            Page page = pages.get(childId);
            if (page != null) {
                block.put("type", "child_page");
                block.putObject("child_page").put("title", page.title());
            } else {
                block.put("type", "paragraph");
                block.putObject("paragraph").putArray("rich_text");
            }
        }
        boolean hasMore = end < children.size();
        response.put("has_more", hasMore);
        if (hasMore) {
            response.put("next_cursor", String.valueOf(end));
        } else {
            response.putNull("next_cursor");
        }
        respond(exchange, 200, response.toString());
    }

    private void handleAppend(HttpExchange exchange, String blockId, String body) throws IOException {
        JsonNode json = MAPPER.readTree(body);
        for (JsonNode child : json.path("children")) {
            appendedBlocks.computeIfAbsent(blockId, k -> new ArrayList<>()).add(child);
        }
        respond(exchange, 200, "{\"object\":\"list\",\"results\":[]}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
