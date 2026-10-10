package io.ohmvir.plugins.jenkinsocr.notes.notion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotionClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Request(String method, String path, String authorization, String version, JsonNode body) {}

    private record Response(int status, String body, Map<String, String> headers) {
        static Response json(String body) {
            return new Response(200, body, Map.of());
        }
    }

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();
    private Function<Request, Response> handler;
    private NotionClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Notion-Version"),
                    raw.isEmpty() ? null : MAPPER.readTree(raw));
            requests.add(request);
            Response response = handler.apply(request);
            response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        HttpClient http =
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        client = new NotionClient(http, base, "secret-token", sleeps::add);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String childPage(String id, String title) {
        return "{\"object\":\"block\",\"id\":\"" + id + "\",\"type\":\"child_page\",\"child_page\":{\"title\":\""
                + title + "\"}}";
    }

    @Test
    void listsChildPagesAcrossPagesOfResults() throws Exception {
        handler = request -> {
            if (request.path().contains("start_cursor=c2")) {
                return Response.json("{\"results\":[" + childPage("p3", "Physics") + "],\"has_more\":false}");
            }
            return Response.json("{\"results\":[" + childPage("p1", "Math")
                    + ",{\"id\":\"b1\",\"type\":\"paragraph\",\"paragraph\":{}},"
                    + "{\"id\":\"p2\",\"type\":\"child_page\",\"archived\":true,\"child_page\":{\"title\":\"Old\"}}],"
                    + "\"has_more\":true,\"next_cursor\":\"c2\"}");
        };
        assertEquals(
                List.of(new NotionClient.ChildPage("p1", "Math"), new NotionClient.ChildPage("p3", "Physics")),
                client.listChildPages("root"));
        assertEquals("GET", requests.get(0).method());
        assertEquals("/v1/blocks/root/children?page_size=100", requests.get(0).path());
        assertEquals(
                "/v1/blocks/root/children?page_size=100&start_cursor=c2",
                requests.get(1).path());
        assertEquals("Bearer secret-token", requests.get(0).authorization());
        assertEquals(NotionClient.NOTION_VERSION, requests.get(0).version());
    }

    @Test
    void findChildPagePrefersExactTitleMatches() throws Exception {
        handler = request -> Response.json("{\"results\":[" + childPage("lower", "math") + ","
                + childPage("exact", "Math") + "," + childPage("other", "Math Notes") + "],\"has_more\":false}");
        assertEquals(Optional.of("exact"), client.findChildPage("root", "Math"));
        assertEquals(Optional.of("lower"), client.findChildPage("root", "MATH"));
        assertEquals(Optional.empty(), client.findChildPage("root", "Mat"));
    }

    @Test
    void createsPagesBelowTheirParent() throws Exception {
        handler = request -> Response.json("{\"object\":\"page\",\"id\":\"new-page\"}");
        assertEquals("new-page", client.createPage("parent-id", "Calculus"));
        Request request = requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("/v1/pages", request.path());
        assertEquals("parent-id", request.body().path("parent").path("page_id").asText());
        assertEquals(
                "Calculus",
                request.body()
                        .path("properties")
                        .path("title")
                        .path("title")
                        .path(0)
                        .path("text")
                        .path("content")
                        .asText());
    }

    private static NotionBlock paragraph(String text, List<NotionBlock> children) {
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.putArray("rich_text")
                .addObject()
                .put("type", "text")
                .putObject("text")
                .put("content", text);
        return new NotionBlock(children.isEmpty() ? "paragraph" : "bulleted_list_item", content, children);
    }

    /** Answers append requests like Notion: with one created block (and a fresh ID) per appended child. */
    private Response appendResponse(Request request) {
        StringBuilder results = new StringBuilder();
        int count = request.body().path("children").size();
        for (int i = 0; i < count; i++) {
            results.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"created-")
                    .append(requests.size())
                    .append('-')
                    .append(i)
                    .append("\"}");
        }
        return Response.json("{\"results\":[" + results + "]}");
    }

    @Test
    void appendsInBatchesOfAtMost100Blocks() throws Exception {
        handler = this::appendResponse;
        List<NotionBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            blocks.add(paragraph("p" + i, List.of()));
        }
        client.appendBlocks("page", blocks);
        assertEquals(3, requests.size());
        assertEquals(
                List.of(100, 100, 50),
                requests.stream().map(r -> r.body().path("children").size()).toList());
        assertTrue(requests.stream()
                .allMatch(r -> r.method().equals("PATCH") && r.path().equals("/v1/blocks/page/children")));
        assertEquals(
                "p100",
                requests.get(1)
                        .body()
                        .path("children")
                        .path(0)
                        .path("paragraph")
                        .path("rich_text")
                        .path(0)
                        .path("text")
                        .path("content")
                        .asText());
    }

    @Test
    void shallowChildrenAreSentInlineAndDeeperOnesAreAppendedToTheCreatedBlocks() throws Exception {
        handler = this::appendResponse;
        NotionBlock shallow = paragraph("shallow", List.of(paragraph("child", List.of())));
        NotionBlock deep = paragraph("deep", List.of(paragraph("level 1", List.of(paragraph("level 2", List.of())))));
        client.appendBlocks("page", List.of(shallow, deep));

        assertEquals(2, requests.size());
        JsonNode first = requests.get(0).body().path("children");
        assertEquals(2, first.size());
        assertEquals(
                1, first.path(0).path("bulleted_list_item").path("children").size());
        assertTrue(first.path(1).path("bulleted_list_item").path("children").isMissingNode());

        // The deep block's children go to the ID Notion returned for it, again inlining the shallow level.
        assertEquals("/v1/blocks/created-1-1/children", requests.get(1).path());
        JsonNode second = requests.get(1).body().path("children");
        assertEquals(1, second.size());
        assertEquals(
                1, second.path(0).path("bulleted_list_item").path("children").size());
    }

    @Test
    void retriesRateLimitedRequestsHonouringRetryAfter() throws Exception {
        List<Integer> statuses = new ArrayList<>(List.of(429, 503));
        handler = request -> {
            if (!statuses.isEmpty()) {
                int status = statuses.remove(0);
                return new Response(status, "{}", status == 429 ? Map.of("Retry-After", "3") : Map.of());
            }
            return Response.json("{\"id\":\"page\"}");
        };
        assertEquals("page", client.createPage("parent", "Title"));
        assertEquals(3, requests.size());
        assertEquals(List.of(3000L, 2000L), sleeps);
    }

    @Test
    void errorsCarryNotionsMessage() {
        handler = request -> new Response(
                400,
                "{\"object\":\"error\",\"status\":400,\"code\":\"validation_error\","
                        + "\"message\":\"body.children[0].equation.expression.length should be ≤ 1000\"}",
                Map.of());
        NotionApiException e = assertThrows(NotionApiException.class, () -> client.createPage("parent", "Title"));
        assertEquals(400, e.getStatusCode());
        assertEquals("validation_error", e.getErrorCode());
        assertTrue(e.getMessage().contains("expression.length should be ≤ 1000"), e.getMessage());
        assertEquals(1, requests.size(), "client errors are not retried");
    }

    @Test
    void giveUpAfterRepeatedServerErrors() {
        handler = request -> new Response(502, "Bad gateway", Map.of());
        NotionApiException e = assertThrows(NotionApiException.class, () -> client.listChildPages("root"));
        assertEquals(502, e.getStatusCode());
        assertTrue(e.getMessage().contains("Bad gateway"));
        assertEquals(5, requests.size());
    }
}
