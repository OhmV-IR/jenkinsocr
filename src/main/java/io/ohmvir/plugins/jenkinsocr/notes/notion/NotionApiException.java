package io.ohmvir.plugins.jenkinsocr.notes.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * A request to the Notion API was answered with an error.
 */
public class NotionApiException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String errorCode;

    public NotionApiException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Notion's error code, such as {@code validation_error}, or {@code null} if the response had none.
     */
    public String getErrorCode() {
        return errorCode;
    }

    static NotionApiException fromResponse(int statusCode, String body, ObjectMapper mapper) {
        String errorCode = null;
        String detail = null;
        try {
            JsonNode error = mapper.readTree(body == null ? "" : body);
            if (error != null) {
                errorCode = error.path("code").asText(null);
                detail = error.path("message").asText(null);
            }
        } catch (IOException e) {
            // not a JSON error object; fall back to the raw body below
        }
        if (detail == null) {
            detail = body == null || body.isBlank() ? "no details were returned" : truncate(body);
        }
        StringBuilder message = new StringBuilder("Notion API request failed with HTTP ").append(statusCode);
        if (errorCode != null) {
            message.append(" (").append(errorCode).append(')');
        }
        message.append(": ").append(detail);
        if (statusCode == 401) {
            message.append(" Check that the configured Notion integration token is valid.");
        } else if (statusCode == 404) {
            message.append(" Check that the root page is shared with the Notion integration.");
        }
        return new NotionApiException(statusCode, errorCode, message.toString());
    }

    private static String truncate(String body) {
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }
}
