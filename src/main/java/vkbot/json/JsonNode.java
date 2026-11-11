package vkbot.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Удобная «безопасная» обёртка над распарсенным JSON.
 * Обращение к отсутствующему полю возвращает пустой узел, а не NPE.
 */
public final class JsonNode implements Iterable<JsonNode> {

    private static final JsonNode MISSING = new JsonNode(null);

    private final Object value;

    JsonNode(Object value) {
        this.value = value;
    }

    public Object raw() {
        return value;
    }

    public boolean isMissing() {
        return value == null;
    }

    public boolean isObject() {
        return value instanceof Map;
    }

    public boolean isArray() {
        return value instanceof List;
    }

    public boolean has(String key) {
        return isObject() && ((Map<?, ?>) value).containsKey(key);
    }

    public JsonNode get(String key) {
        if (!isObject()) {
            return MISSING;
        }
        Object v = ((Map<?, ?>) value).get(key);
        return v == null ? MISSING : new JsonNode(v);
    }

    public JsonNode get(int index) {
        if (!isArray()) {
            return MISSING;
        }
        List<?> list = (List<?>) value;
        return index < 0 || index >= list.size() ? MISSING : new JsonNode(list.get(index));
    }

    public int size() {
        if (isArray()) {
            return ((List<?>) value).size();
        }
        if (isObject()) {
            return ((Map<?, ?>) value).size();
        }
        return 0;
    }

    public long asLong(long def) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        }
        return def;
    }

    public long asLong() {
        return asLong(0);
    }

    public boolean asBoolean() {
        return asLong(0) != 0;
    }

    public String asText(String def) {
        if (value == null || value instanceof Map || value instanceof List) {
            return def;
        }
        return String.valueOf(value);
    }

    public String asText() {
        return asText("");
    }

    @Override
    public Iterator<JsonNode> iterator() {
        if (!isArray()) {
            return Collections.emptyIterator();
        }
        List<JsonNode> nodes = new ArrayList<>();
        for (Object o : (List<?>) value) {
            nodes.add(new JsonNode(o));
        }
        return nodes.iterator();
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
