package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotionClientTest {
    private static final String ROOT = "root-page";
    private static final String TOKEN = "secret-token";

    private FakeNotionServer notion;
    private NotionClient client;

    @BeforeEach
    void setUp() throws IOException {
        notion = new FakeNotionServer();
        client = new NotionClient(notion.baseUri(), TOKEN, HttpClient.newHttpClient(), Duration.ofSeconds(10), 10L);
    }

    @AfterEach
    void tearDown() {
        notion.close();
    }

    @Test
    void directoryPathsWalkNestedPagesAcrossPaginatedResponses() throws Exception {
        String math = notion.addPage(ROOT, "Math");
        notion.addParagraph(ROOT);
        String algebra = notion.addPage(math, "Algebra");
        notion.addPage(algebra, "Linear");
        notion.addPage(math, "Calculus");
        notion.addPage(ROOT, "History");
        notion.addPage(ROOT, "Physics");
        notion.setChildrenPageSize(2);

        List<String> paths = client.getDirectoryPaths(ROOT);

        assertEquals(
                List.of("Math", "Math/Algebra", "Math/Algebra/Linear", "Math/Calculus", "History", "Physics"), paths);
        long rootListings = notion.requests().stream()
                .filter(r -> r.rawPath().equals("/v1/blocks/" + ROOT + "/children"))
                .count();
        assertEquals(2, rootListings, "four root blocks at two per response need two requests");
    }

    @Test
    void directoryTreeIsIndentedByDepth() throws Exception {
        String math = notion.addPage(ROOT, "Math");
        notion.addPage(math, "Algebra");
        notion.addPage(ROOT, "History");

        assertEquals(
                "- Math (Path: Math)\n" + "  - Algebra (Path: Math/Algebra)\n" + "- History (Path: History)\n",
                client.getDirectoryTreeFormatted(ROOT));
    }

    @Test
    void emptyDirectoryTreeIsEmptyString() throws Exception {
        assertEquals("", client.getDirectoryTreeFormatted(ROOT));
        assertEquals("", NotionClient.formatDirectoryTree(List.of()));
    }

    @Test
    void requestsCarryAuthenticationAndVersionHeaders() throws Exception {
        client.getDirectoryPaths(ROOT);

        FakeNotionServer.RecordedRequest request = notion.requests().get(0);
        assertEquals("Bearer " + TOKEN, request.headers().get("authorization"));
        assertEquals(NotionClient.NOTION_VERSION, request.headers().get("notion-version"));
        assertEquals("page_size=100", request.query());
    }

    @Test
    void createPageReusesExistingPagesAndCreatesMissingOnes() throws Exception {
        String math = notion.addPage(ROOT, "Math");

        String pageId = client.createPageAtPathWithKatex(ROOT, "Math/Calculus", "Limits", "\\lim_{x \\to 0} x");

        List<FakeNotionServer.Page> mathChildren = notion.childPages(math);
        assertEquals(1, mathChildren.size());
        FakeNotionServer.Page calculus = mathChildren.get(0);
        assertEquals("Calculus", calculus.title());
        FakeNotionServer.Page limits = notion.page(pageId);
        assertEquals("Limits", limits.title());
        assertEquals(calculus.id(), limits.parentId());
        assertEquals(1, notion.childPages(ROOT).size(), "Math must be reused rather than duplicated");

        List<JsonNode> blocks = notion.appendedBlocks(pageId);
        assertEquals(1, blocks.size());
        assertEquals("equation", blocks.get(0).path("type").asText());
        assertEquals(
                "\\lim_{x \\to 0} x",
                blocks.get(0).path("equation").path("expression").asText());
    }

    @Test
    void existingPageLookupRequiresTheWholeTitleToMatch() throws Exception {
        String math = notion.addPage(ROOT, "Math");
        String calculusTwo = notion.addPage(math, "Calculus II");

        String pageId = client.createPageAtPathWithKatex(ROOT, "Math", "Calculus", "x");

        assertNotEquals(calculusTwo, pageId, "'Calculus' must not resolve to 'Calculus II'");
        assertEquals("Calculus", notion.page(pageId).title());
        assertEquals(2, notion.childPages(math).size());
    }

    @Test
    void existingPageLookupIgnoresCaseAndSurroundingWhitespace() throws Exception {
        String math = notion.addPage(ROOT, "Math");
        String algebra = notion.addPage(math, " Algebra ");

        String pageId = client.createPageAtPathWithKatex(ROOT, " math / /", "algebra", "x");

        assertEquals(algebra, pageId);
        assertEquals(1, notion.childPages(ROOT).size());
        assertEquals(1, notion.childPages(math).size());
    }

    @Test
    void blankPathAndTitleAppendToTheRootPage() throws Exception {
        String pageId = client.createPageAtPathWithKatex(ROOT, "  ", null, "x");

        assertEquals(ROOT, pageId);
        assertEquals(1, notion.appendedBlocks(ROOT).size());
        assertTrue(notion.requests().stream().noneMatch(r -> r.method().equals("POST")));
    }

    @Test
    void findChildPageIdReturnsNullWhenMissing() throws Exception {
        notion.addPage(ROOT, "Math");

        assertNull(client.findChildPageId(ROOT, "History"));
    }

    @Test
    void failedAppendIsReportedWithNotionsMessage() {
        notion.failRequestsTo(
                "/v1/blocks/" + ROOT + "/children",
                400,
                "{\"object\":\"error\",\"code\":\"validation_error\","
                        + "\"message\":\"body.children[0].equation.expression.length should be <= 1000\"}");

        NotionClient.NotionApiException e =
                assertThrows(NotionClient.NotionApiException.class, () -> client.appendKatexBlock(ROOT, "x"));

        assertEquals(400, e.getStatusCode());
        assertTrue(e.getMessage().contains("validation_error"), e.getMessage());
        assertTrue(e.getMessage().contains("should be <= 1000"), e.getMessage());
    }

    @Test
    void failedPageCreationIsReported() {
        notion.failRequestsTo(
                "/v1/pages", 401, "{\"object\":\"error\",\"code\":\"unauthorized\",\"message\":\"bad token\"}");

        NotionClient.NotionApiException e = assertThrows(
                NotionClient.NotionApiException.class, () -> client.createPageAtPathWithKatex(ROOT, "Math", null, "x"));

        assertEquals(401, e.getStatusCode());
        assertTrue(e.getMessage().contains("unauthorized: bad token"), e.getMessage());
        assertTrue(notion.appendedBlocks(ROOT).isEmpty());
    }

    @Test
    void failedListingIsReportedInsteadOfReturningAnEmptyTree() {
        notion.failRequestsTo("/v1/blocks/", 404, "not json");

        NotionClient.NotionApiException e =
                assertThrows(NotionClient.NotionApiException.class, () -> client.getDirectoryPaths(ROOT));

        assertEquals(404, e.getStatusCode());
        assertTrue(e.getMessage().contains("not json"), e.getMessage());
    }

    @Test
    void rateLimitedRequestsAreRetried() throws Exception {
        notion.addPage(ROOT, "Math");
        notion.rateLimitNextResponses(2);

        assertEquals(List.of("Math"), client.getDirectoryPaths(ROOT));
        assertEquals(4, notion.requests().size());
    }

    @Test
    void rateLimitingGivesUpEventually() {
        notion.rateLimitNextResponses(100);

        NotionClient.NotionApiException e =
                assertThrows(NotionClient.NotionApiException.class, () -> client.getDirectoryPaths(ROOT));

        assertEquals(429, e.getStatusCode());
    }

    @Test
    void pageIdsAreEncodedIntoASinglePathSegment() throws Exception {
        client.getDirectoryPaths("../pages");

        FakeNotionServer.RecordedRequest request = notion.requests().get(0);
        assertEquals("GET", request.method());
        assertEquals("/v1/blocks/..%2Fpages/children", request.rawPath());
    }

    @Test
    void blankPageIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> client.getDirectoryPaths(" "));
        assertThrows(IllegalArgumentException.class, () -> client.getDirectoryPaths(null));
    }

    @Test
    void blankTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NotionClient(null));
        assertThrows(IllegalArgumentException.class, () -> new NotionClient(" "));
    }
}
