package io.ohmvir.plugins.jenkinsocr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Parses the JSON object the OCR prompts ask the model to return. */
final class OcrResponseParser {
    private OcrResponseParser() {}

    /**
     * @param modelText The raw text produced by the model
     * @return The parsed output
     * @throws IllegalArgumentException if the text is not a JSON object with string text, path and title fields
     */
    static RecognizeTextOutput parse(String modelText) {
        if (modelText == null || modelText.isBlank()) {
            throw new IllegalArgumentException("Model returned empty text");
        }
        JsonElement element;
        try {
            element = JsonParser.parseString(extractJsonObject(modelText));
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Model output is not valid JSON: " + e.getMessage(), e);
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Model output is not a JSON object");
        }
        JsonObject output = element.getAsJsonObject();
        return new RecognizeTextOutput(
                requireString(output, "text"), requireString(output, "path"), requireString(output, "title"));
    }

    /**
     * Models frequently wrap the requested JSON in a markdown code fence or a sentence of prose,
     * so only keep the outermost object.
     */
    static String extractJsonObject(String modelText) {
        int start = modelText.indexOf('{');
        int end = modelText.lastIndexOf('}');
        if (start < 0 || end < start) {
            return modelText.trim();
        }
        return modelText.substring(start, end + 1);
    }

    private static String requireString(JsonObject output, String field) {
        JsonElement value = output.get(field);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("Model didn't produce the '" + field + "' field in the JSON");
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("The '" + field + "' field in the model's JSON must be a string");
        }
        return value.getAsString();
    }
}
