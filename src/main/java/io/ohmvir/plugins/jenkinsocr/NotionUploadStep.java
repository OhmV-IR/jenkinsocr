package io.ohmvir.plugins.jenkinsocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import jenkins.tasks.SimpleBuildStep;
import lombok.Getter;
import org.jenkinsci.Symbol;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;

public class NotionUploadStep extends Builder implements SimpleBuildStep {
    private final @Getter String notionText;
    private final @Getter String pagePath;
    private final @Getter String pageTitle;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    @DataBoundConstructor
    public NotionUploadStep(String notionText, String pageTitle, String pagePath) {
        this.notionText = notionText;
        this.pageTitle = pageTitle;
        this.pagePath = pagePath;
        httpClient = HttpClient.newBuilder().build();
        mapper = new ObjectMapper();
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull EnvVars env, @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        listener.getLogger().println("Uploading notion text: " + notionText);
        String apiToken = SecretsUtils.getSecretText(NoteOCRSettings.get().getNotionApiTokenCredentialId(), null);
        try {
            String createdPageId =
                    createPageAtPathWithKatex(NoteOCRSettings.get().getRootPageId(), pagePath, pageTitle, notionText, apiToken);
            listener.getLogger().println("Created page with id: " + createdPageId);
        } catch (Exception e) {
            listener.error("Failed to create notion page: " + e.getMessage());
            throw new AbortException("Notion sync failed: " + e.getMessage());
        }
    }

    /**
     * Traverses or creates pages along a path (e.g. "Math/Algebra")
     * creates or finds the final page using pageTitle, and adds 
     * a KaTeX equation block to that final destination page.
     *
     * @param rootPageId The root Notion page ID where path resolution begins
     * @param path       Path elements separated by slashes (e.g., "Math/Semester 1/Calculus")
     * @param pageTitle  The name of the final page to hold the KaTeX block
     * @param katexText  Raw LaTeX/KaTeX string
     * @param apiToken   Notion API bearer token
     * @return The ID of the final created/resolved page
     */
    public String createPageAtPathWithKatex(String rootPageId, String path, String pageTitle, String katexText, String apiToken)
            throws Exception {
        String currentParentId = rootPageId;

        // 1. Walk through each segment, creating missing pages/folders along the way
        if (path != null && !path.trim().isEmpty()) {
            String[] segments = path.split("/");
            for (String segment : segments) {
                String trimmed = segment.trim();
                if (trimmed.isEmpty()) continue;

                String existingId = findChildPageId(currentParentId, trimmed, apiToken);
                if (existingId != null) {
                    currentParentId = existingId;
                } else {
                    currentParentId = createBlankPage(currentParentId, trimmed, apiToken);
                }
            }
        }

        // 2. Resolve or create the final page using the pageTitle
        if (pageTitle != null && !pageTitle.trim().isEmpty()) {
            String trimmedTitle = pageTitle.trim();
            String finalPageId = findChildPageId(currentParentId, trimmedTitle, apiToken);
            if (finalPageId != null) {
                currentParentId = finalPageId;
            } else {
                currentParentId = createBlankPage(currentParentId, trimmedTitle, apiToken);
            }
        }

        // 3. Append the KaTeX equation block to the final resolved page
        appendKatexBlock(currentParentId, katexText, apiToken);

        return currentParentId;
    }

    private String findChildPageId(String parentId, String title, String apiToken) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", title);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.notion.com/v1/search"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Notion-Version", "2022-06-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        if (root.has("results")) {
            for (JsonNode node : root.get("results")) {
                if ("page".equals(node.path("object").asText())) {
                    String nodeParentId = node.path("parent").path("page_id").asText();
                    if (parentId.replace("-", "").equalsIgnoreCase(nodeParentId.replace("-", ""))) {
                        return node.path("id").asText();
                    }
                }
            }
        }
        return null;
    }

    private String createBlankPage(String parentId, String title, String apiToken) throws Exception {
        ObjectNode body = mapper.createObjectNode();

        // Set parent
        ObjectNode parent = body.putObject("parent");
        parent.put("type", "page_id");
        parent.put("page_id", parentId);

        // Set title property
        ObjectNode properties = body.putObject("properties");
        ObjectNode titleProp = properties.putObject("title");
        ArrayNode titleArray = titleProp.putArray("title");
        ObjectNode textObj = titleArray.addObject();
        textObj.putObject("text").put("content", title);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.notion.com/v1/pages"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Notion-Version", "2022-06-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        return root.get("id").asText();
    }

    private void appendKatexBlock(String pageId, String katexText, String apiToken) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode children = body.putArray("children");

        // KaTeX block object
        ObjectNode block = children.addObject();
        block.put("object", "block");
        block.put("type", "equation");
        block.putObject("equation").put("expression", katexText);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.notion.com/v1/blocks/" + pageId + "/children"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Notion-Version", "2022-06-28")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Recursively traverses the Notion workspace starting at rootPageId
     * and returns a list of all valid directory path categories (e.g., "Math/Algebra").
     *
     * @param rootPageId The root page ID to start listing directories from
     * @param apiToken   The Notion API bearer token
     * @return List of relative path strings representing category folders
     */
    public static List<String> getDirectoryPaths(String rootPageId, String apiToken) throws Exception {
        List<String> directoryPaths = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().build();
        ObjectMapper objectMapper = new ObjectMapper();

        fetchChildDirectories(rootPageId, "", directoryPaths, apiToken, client, objectMapper);
        return directoryPaths;
    }

    /**
     * Formats the directory paths into a bulleted tree string suitable for LLM prompts.
     */
    public static String getDirectoryTreeFormatted(String rootPageId, String apiToken) throws Exception {
        List<String> paths = getDirectoryPaths(rootPageId, apiToken);
        StringBuilder tree = new StringBuilder();

        for (String path : paths) {
            int depth = path.split("/").length - 1;
            String indent = "  ".repeat(depth);
            String folderName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            tree.append(indent).append("- ").append(folderName).append(" (Path: ").append(path).append(")\n");
        }

        return tree.toString();
    }

    private static void fetchChildDirectories(
            String parentId,
            String currentPath,
            List<String> directoryPaths,
            String apiToken,
            HttpClient client,
            ObjectMapper mapper) throws Exception {

        String startCursor = null;
        boolean hasMore = true;

        // Notion API pagination loop
        while (hasMore) {
            StringBuilder urlBuilder = new StringBuilder("https://api.notion.com/v1/blocks/")
                    .append(parentId)
                    .append("/children?page_size=100");

            if (startCursor != null) {
                urlBuilder.append("&start_cursor=").append(startCursor);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlBuilder.toString()))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Notion-Version", "2022-06-28")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());

            if (root.has("results")) {
                for (JsonNode block : root.get("results")) {
                    // Notion represents sub-folders/sub-pages as blocks of type "child_page"
                    if ("child_page".equals(block.path("type").asText())) {
                        String pageTitle = block.path("child_page").path("title").asText();
                        String childPageId = block.path("id").asText();

                        String newPath = currentPath.isEmpty() ? pageTitle : currentPath + "/" + pageTitle;
                        directoryPaths.add(newPath);

                        // Recurse into sub-pages to build deeper category paths
                        fetchChildDirectories(childPageId, newPath, directoryPaths, apiToken, client, mapper);
                    }
                }
            }

            hasMore = root.path("has_more").asBoolean(false);
            if (hasMore) {
                startCursor = root.path("next_cursor").asText();
            }
        }
    }

    @Extension
    @Symbol("notionUpload")
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Upload Text Block to Notion";
        }
    }
}