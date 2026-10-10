package io.ohmvir.plugins.jenkinsocr.notes.notion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NotionIdsTest {
    private static final String ID = "0123abcd-4567-89ef-0123-456789abcdef";

    @Test
    void acceptsIdsAndPageUrls() {
        assertEquals(ID, NotionIds.normalize(ID));
        assertEquals(ID, NotionIds.normalize("0123ABCD456789EF0123456789ABCDEF"));
        assertEquals(
                ID,
                NotionIds.normalize(" https://www.notion.so/team/My-Notes-0123abcd456789ef0123456789abcdef?pvs=4 "));
        assertEquals(ID, NotionIds.normalize("https://www.notion.so/Cafe-0123abcd456789ef0123456789abcdef#section"));
    }

    @Test
    void rejectsValuesWithoutAnId() {
        assertThrows(IllegalArgumentException.class, () -> NotionIds.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> NotionIds.normalize("https://www.notion.so/My-Notes"));
        assertThrows(IllegalArgumentException.class, () -> NotionIds.normalize("../../v1/users"));
    }

    @Test
    void buildsPageUrls() {
        assertEquals("https://www.notion.so/0123abcd456789ef0123456789abcdef", NotionIds.pageUrl(ID));
    }
}
