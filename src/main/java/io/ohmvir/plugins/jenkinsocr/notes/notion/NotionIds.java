package io.ohmvir.plugins.jenkinsocr.notes.notion;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for Notion object IDs.
 */
public final class NotionIds {
    private static final Pattern TRAILING_ID = Pattern.compile("([0-9a-fA-F]{32})$");

    private NotionIds() {}

    /**
     * Extracts a page ID from either a raw ID (with or without dashes) or a Notion page URL, such as
     * {@code https://www.notion.so/My-Notes-0123456789abcdef0123456789abcdef?pvs=4}.
     *
     * @return the ID in its canonical dashed, lower-case form
     * @throws IllegalArgumentException if no page ID can be found
     */
    public static String normalize(String idOrUrl) {
        if (idOrUrl == null || idOrUrl.isBlank()) {
            throw new IllegalArgumentException("A Notion page ID is required");
        }
        String value = idOrUrl.strip();
        int end = value.length();
        for (char separator : new char[] {'?', '#'}) {
            int index = value.indexOf(separator);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        String compact = value.substring(0, end).replace("-", "");
        Matcher matcher = TRAILING_ID.matcher(compact);
        if (!matcher.find()) {
            throw new IllegalArgumentException("'" + idOrUrl + "' is not a Notion page ID or page URL");
        }
        String hex = matcher.group(1).toLowerCase(Locale.ROOT);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /**
     * The web URL of a page.
     */
    public static String pageUrl(String pageId) {
        return "https://www.notion.so/" + pageId.replace("-", "");
    }
}
