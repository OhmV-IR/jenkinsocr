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
import jenkins.tasks.SimpleBuildStep;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class NotionUploadStep extends Builder implements SimpleBuildStep {
    private final @Getter String notionText;
    private final @Getter String pagePath;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    @DataBoundConstructor
    public NotionUploadStep(String notionText, String pagePath) {
        this.notionText = notionText;
        this.pagePath = pagePath;
        httpClient = HttpClient.newBuilder().build();
        mapper = new ObjectMapper();
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull EnvVars env, @NonNull TaskListener listener) throws InterruptedException, IOException {
        listener.getLogger().println("Uploading notion text: " + notionText);
        String apiToken = SecretsUtils.getSecretText(NoteOCRSettings.get().getNotionApiTokenCredentialId(), null);
        try {
            String createdPageId = createPageAtPathWithKatex(NoteOCRSettings.get().getRootPageId(), pagePath, notionText, apiToken);
            listener.getLogger().println("Created page with id: " + createdPageId);
        } catch (Exception e) {
            listener.error("Failed to create notion page: " + e.getMessage());
            throw new AbortException("Notion sync failed: " + e.getMessage());
        }
    }

    /**
     * Traverses or creates pages along a path (e.g. "Math/Algebra/Quadratics")
     * and adds a KaTeX equation block to the final destination page.
     *
     * @param rootPageId The root Notion page ID where path resolution begins
     * @param path       Path elements separated by slashes (e.g., "Math/Semester 1/Calculus")
     * @param katexText  Raw LaTeX/KaTeX string
     * @return The ID of the final created/resolved page
     */
    public String createPageAtPathWithKatex(String rootPageId, String path, String katexText, String apiToken) throws Exception {
        String[] segments = path.split("/");
        String currentParentId = rootPageId;

        // 1. Walk through each segment, creating missing pages along the way
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

        // 2. Append the KaTeX equation block to the final resolved page
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

    @Extension
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
