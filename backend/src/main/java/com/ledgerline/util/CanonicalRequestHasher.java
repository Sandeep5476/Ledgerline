package com.ledgerline.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class CanonicalRequestHasher {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CanonicalRequestHasher() {
    }

    public static String computeRequestHash(String method, String path, Long userId, Object body) {
        String canonicalBody = canonicalizeBody(body);
        String canonicalPayload = (method != null ? method.toUpperCase(Locale.ROOT) : "") + "\n" +
                                 (path != null ? path : "") + "\n" +
                                 (userId != null ? userId.toString() : "") + "\n" +
                                 canonicalBody;
        return HashUtils.sha256Hex(canonicalPayload);
    }

    public static String canonicalizeBody(Object body) {
        if (body == null) {
            return "";
        }
        try {
            JsonNode node = OBJECT_MAPPER.valueToTree(body);
            JsonNode sortedNode = sortJsonNode(node);
            return OBJECT_MAPPER.writeValueAsString(sortedNode);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to canonicalize request body for idempotency hashing", e);
        }
    }

    public static String canonicalizeJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            JsonNode sortedNode = sortJsonNode(node);
            return OBJECT_MAPPER.writeValueAsString(sortedNode);
        } catch (Exception e) {
            return json.trim();
        }
    }

    private static JsonNode sortJsonNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sortedObject = OBJECT_MAPPER.createObjectNode();
            Map<String, JsonNode> map = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                map.put(entry.getKey(), sortJsonNode(entry.getValue()));
            }
            map.forEach(sortedObject::set);
            return sortedObject;
        } else if (node.isArray()) {
            ArrayNode sortedArray = OBJECT_MAPPER.createArrayNode();
            for (JsonNode element : node) {
                sortedArray.add(sortJsonNode(element));
            }
            return sortedArray;
        }
        return node;
    }
}

