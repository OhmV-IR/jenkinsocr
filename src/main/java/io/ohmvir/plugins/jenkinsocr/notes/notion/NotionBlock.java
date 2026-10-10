package io.ohmvir.plugins.jenkinsocr.notes.notion;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * A Notion block ready to be appended to a page. Its children are kept apart from its content so that
 * {@link NotionClient} can decide whether to send them in the same request or in follow-up requests, depending on
 * Notion's request limits.
 */
public final class NotionBlock {
    private final String type;
    private final ObjectNode content;
    private final List<NotionBlock> children;

    /**
     * @param type     the Notion block type, e.g. {@code "paragraph"}
     * @param content  the type-specific block object, without {@code children}
     * @param children nested blocks; only block types that support children may have any
     */
    public NotionBlock(String type, ObjectNode content, List<NotionBlock> children) {
        this.type = type;
        this.content = content.deepCopy();
        this.children = List.copyOf(children);
    }

    public String getType() {
        return type;
    }

    public List<NotionBlock> getChildren() {
        return children;
    }

    /**
     * A copy of the type-specific block object, without children.
     */
    public ObjectNode getContent() {
        return content.deepCopy();
    }

    /**
     * Whether any child has children of its own.
     */
    boolean hasGrandchildren() {
        return children.stream().anyMatch(child -> !child.children.isEmpty());
    }

    /**
     * @param includeChildren whether to embed the children in the JSON
     * @return the block object as accepted by the "append block children" endpoint
     */
    ObjectNode toJson(boolean includeChildren) {
        ObjectNode block = JsonNodeFactory.instance.objectNode();
        block.put("object", "block");
        block.put("type", type);
        ObjectNode body = content.deepCopy();
        if (includeChildren && !children.isEmpty()) {
            ArrayNode childArray = body.putArray("children");
            children.forEach(child -> childArray.add(child.toJson(false)));
        }
        block.set(type, body);
        return block;
    }
}
